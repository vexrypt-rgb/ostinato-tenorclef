package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;
import org.junit.Test;

import javax.swing.JList;
import javax.swing.JSlider;
import javax.swing.JTable;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Ostinato: End-to-end tests for the integrated WIP Command Center panels:
 * AlertToastBar, ReplayBuffer + ReplayPanel scrubber, TaskQueuePanel, SharedStashPanel,
 * LinkMonitorPanel, SpeedrunSplitsPanel, and BotFeed diagnostic extensions (whyStuck, dangers).
 */
public class WipOpsPanelsTest {

    @Test
    public void toastBarSurfacesAlertsAndDismissesWithoutFocusSteal() {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Azalea").health(4).danger("player", "Griefer", 12, 64, 14, 5.0, "warn");
        ui.refresh();

        assertTrue(ui.find("toast:bar").isVisible());
        assertTrue(ui.text("toast:text").contains("Azalea"));

        // Dismiss hides bar for current alerts
        ui.click("toast:dismiss");
        assertFalse(ui.find("toast:bar").isVisible());

        // New alert re-shows toast bar
        ui.swarm.bot("Spelunker").dead(true);
        ui.refresh();
        assertTrue(ui.find("toast:bar").isVisible());
        assertTrue(ui.text("toast:text").contains("Spelunker"));
    }

    @Test
    public void replayBufferAndPanelScrubHistoricallyAndReturnToLive() throws Exception {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Azalea").at(0, 64, 0).health(20);
        ui.refresh();
        ui.advance(1000);
        ui.swarm.bot("Azalea").at(50, 64, 0).health(6);
        ui.refresh();
        ui.advance(1000);
        ui.swarm.bot("Azalea").at(100, 64, 0).health(20);
        ui.refresh();

        assertTrue(ui.shell.replayBuffer().size() >= 3);

        ui.click("footer.replay");
        assertTrue(ui.find("replay:panel").isVisible());
        assertTrue(ui.text("replay:status").startsWith("LIVE"));

        // Scrub back to frame 1 (where Azalea was at x=50 with 6 HP)
        JSlider slider = (JSlider) ui.find("replay:slider");
        slider.setValue(1);
        assertTrue(ui.text("replay:status").startsWith("REPLAY"));
        assertTrue(ui.text("tile:Azalea:position").contains("50, 64, 0"));

        ui.refresh();
        BufferedImage shot = ui.screenshot("replay-toast-dark");
        assertNotNull(shot);

        // Return to Live restores latest frame (x=100)
        ui.click("replay:live");
        assertTrue(ui.text("replay:status").startsWith("LIVE"));
        assertTrue(ui.text("tile:Azalea:position").contains("100, 64, 0"));
    }

    @Test
    public void opsDrawerTasksStashLinksSplitsAndScreenshot() throws Exception {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Copper")
                .task("mine", "mine diamond_ore", "running")
                .task("goto", "goto 10 64 20", "queued")
                .linkBudget(new BotFeed.LinkBudget("/tell", 240, 650, 2))
                .speedrun(new BotFeed.Speedrun("Nether", 185_000, 3700, 210_000, List.of(
                        new BotFeed.Speedrun.Split("Overworld", 92_000, 1840, 95_000),
                        new BotFeed.Speedrun.Split("Nether", 185_000, 3700, 180_000)
                )))
                .whyStuck(new BotFeed.WhyStuck("100 64 200", 3, "no valid path across lava", 420, 1500, "blocked"));
        ui.swarm.bot("Azalea")
                .danger("mob", "Creeper", 15, 64, -10, 4.5, "bad")
                .inventory(new BotFeed.Inventory(0, 36, 2,
                        List.of(new BotFeed.Item("diamond", 12), new BotFeed.Item("cobblestone", 128)),
                        12, "diamond_pickaxe"));
        ui.refresh();

        // WhyStuck surfaces bot-reported PathFailureLog reason
        WhyStuck ws = new WhyStuck();
        WhyStuck.Report rep = ws.report(ui.source.snapshot(), "Copper");
        assertTrue(rep.stuck());
        assertTrue(rep.reason().contains("no valid path across lava"));

        // MapDanger surfaces bot-reported nearby Creeper
        List<MapDanger.Hazard> hazards = MapDanger.dynamicHazards(ui.source.snapshot(), 'o');
        assertTrue(hazards.stream().anyMatch(h -> h.label().equals("Creeper")));

        // Open Ops drawer
        ui.click("header.ops");
        assertTrue(ui.shell.opsVisible());

        // Tasks tab: select Copper, verify queued tasks and dispatch reorder
        ((javax.swing.JComboBox<?>) ui.find("tasks:bot")).setSelectedItem("Copper");
        JList<?> taskList = (JList<?>) ui.find("tasks:list");
        assertEquals(2, taskList.getModel().getSize());
        taskList.setSelectedIndex(1);
        ui.click("tasks:up");
        assertTrue(lines(ui).stream().anyMatch(l -> l.contains("tasks move 1 0")));

        // Stash tab: aggregates items and shows low-durability/full warnings
        ui.click("ops:tab:stash");
        ui.refresh();
        assertTrue(ui.text("stash:totals").contains("diamond"));
        assertTrue(ui.text("stash:warnings").contains("diamond_pickaxe durability 12%"));

        // Links tab: shows learned whisper command and rate limit for Copper
        ui.click("ops:tab:links");
        ui.refresh();
        JTable linkTable = (JTable) ui.find("linkmon:table");
        assertEquals(3, linkTable.getRowCount());
        boolean sawTell = false;
        for (int r = 0; r < linkTable.getRowCount(); r++) {
            if ("Copper".equals(linkTable.getValueAt(r, 0))) {
                assertEquals("/tell", linkTable.getValueAt(r, 3));
                sawTell = true;
            }
        }
        assertTrue(sawTell);

        // Splits tab: shows phase splits & deltas
        ui.click("ops:tab:splits");
        ui.refresh();
        JTable splitsTable = (JTable) ui.find("splits:table");
        assertEquals(2, splitsTable.getRowCount());
        assertTrue(ui.text("splits:summary").contains("Nether"));

        BufferedImage opsShot = ui.screenshot("ops-panels-dark");
        assertNotNull(opsShot);

        // Ops drawer & active tab survive theme rebuild
        Path dir = Files.createTempDirectory("ops-theme");
        ui.shell.setPrefsDir(dir);
        ThemeChoice.save(ThemeChoice.file(dir), "end");
        ui.refresh();
        assertEquals("end", ui.shell.themeName());
        assertTrue(ui.shell.opsVisible());
        assertEquals(3, ui.shell.opsTabIndex());
    }

    private static List<String> lines(UiHarness ui) {
        List<String> out = new ArrayList<>();
        for (CenterRequest r : ui.port.sent()) out.add(r.line());
        return out;
    }
}
