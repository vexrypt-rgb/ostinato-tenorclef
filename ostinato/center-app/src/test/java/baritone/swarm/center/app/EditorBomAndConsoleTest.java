package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.rts.Blueprint;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Ostinato: Tests for P3 Schematic Editor Bill of Materials vs swarm inventory &amp; chests,
 * STRIPS/GRID/LAYERS blueprint splitting, Show on Map ghost projection, and Command Center
 * combined log &amp; console drawer.
 */
public class EditorBomAndConsoleTest {

    @Test
    public void materialCompareTalliesBotsChestsPlacedAndMissing() {
        Blueprint bp = new Blueprint("fort");
        for (int x = 0; x < 10; x++) bp.paint(x, 64, 0, "cobblestone");
        for (int x = 0; x < 6; x++) bp.paint(x, 65, 0, "oak_planks");
        for (int x = 0; x < 4; x++) bp.paint(x, 66, 0, "glass");

        // Mark 2 cobblestone already placed -> 8 cobblestone, 6 oak_planks, 4 glass remaining
        Set<Long> placed = Set.of(Blueprint.cellKey(0, 64, 0), Blueprint.cellKey(1, 64, 0));

        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Copper").inventory(new BotFeed.Inventory(20, 36, 4,
                List.of(new BotFeed.Item("cobblestone", 6), new BotFeed.Item("oak_planks", 10)), 80, "iron_pickaxe"));
        ui.swarm.bot("Azalea").inventory(new BotFeed.Inventory(20, 36, 4,
                List.of(new BotFeed.Item("glass", 1)), 80, "iron_pickaxe"));
        ui.swarm.bot("Spelunker").inventory(new BotFeed.Inventory(20, 36, 4, List.of(), 80, "iron_pickaxe"));

        SwarmView base = ui.source.snapshot();
        CoordinatorFeed coord = new CoordinatorFeed("Copper", 1000L, 1, "active", "overworld", List.of(),
                List.of(
                        new CoordinatorFeed.Observation("c1", "chest", 'o', new int[]{0, 64, 0, 0, 64, 0}, "Copper", 90,
                                "cobblestone:4, 2 glass", "confirmed", false, 500),
                        new CoordinatorFeed.Observation("c2", "chest", 'o', new int[]{5, 64, 5, 5, 64, 5}, "Copper", 90,
                                "glass:99", "gone", false, 500)
                ), 2);
        SwarmView view = new SwarmView(base.readAtMs(), base.bots(), coord, base.catalog(), base.host(), base.terrain(),
                base.alerts(), base.problems());

        MaterialCompare.Report rep = MaterialCompare.compare(bp, placed, view);
        assertEquals(20, rep.totalBlocks());
        assertEquals(2, rep.totalPlaced());
        assertEquals(18, rep.totalRemaining());
        // cobblestone: need 8, bots 6 + chest 4 = 10 -> missing 0
        // oak_planks: need 6, bots 10 -> missing 0
        // glass: need 4, bots 1 + chest 2 = 3 (gone chest ignored!) -> missing 1
        assertEquals(1, rep.totalMissing());
        assertFalse(rep.allReady());
        assertEquals("glass", rep.rows().get(0).block());
        assertEquals(1, rep.rows().get(0).missing());
        assertEquals(1, rep.rows().get(0).inBots());
        assertEquals(2, rep.rows().get(0).inChests());
    }

    @Test
    public void blueprintSplitterSupportsStripsGridAndLayers() {
        Blueprint bp = new Blueprint("tower");
        for (int x = 0; x < 6; x++) {
            for (int y = 64; y < 70; y++) {
                for (int z = 0; z < 6; z++) {
                    bp.paint(x, y, z, "stone_bricks");
                }
            }
        }
        List<String> bots = List.of("Copper", "Azalea", "Spelunker", "Oak");
        for (BlueprintSplitter.Strategy s : BlueprintSplitter.Strategy.values()) {
            RtsOrders.Plan plan = BlueprintSplitter.split(bots, 'o', bp, s);
            assertNull("strategy " + s + " failed: " + plan.reason(), plan.reason());
            assertEquals(4, plan.orders().size());
            for (RtsOrders.Order o : plan.orders()) {
                assertTrue(o.line().contains("blueprint live tower part="));
                assertTrue(o.line().startsWith("swarm lease " + o.bot()));
            }
        }

        // Single-layer blueprint cannot split across 4 bots in LAYERS mode
        Blueprint flat = new Blueprint("pad");
        flat.paint(0, 64, 0, "cobblestone");
        flat.paint(4, 64, 4, "cobblestone");
        RtsOrders.Plan flatLayers = BlueprintSplitter.split(bots, 'o', flat, BlueprintSplitter.Strategy.LAYERS);
        assertNotNull(flatLayers.reason());
    }

    @Test
    public void editorBomSplitGatherGhostAndScreenshot() throws Exception {
        UiHarness ui = UiHarness.demo(6, CenterTheme.DEFAULT_DARK, CenterLayout.EDITOR).refresh();
        EditorView ed = ui.shell.editor();
        ed.paintBox(0, 0, 5, 5, false);
        ui.type("ed:block", "diamond_block").type("ed:y", "-59").type("ed:h", "2");
        ed.paintBox(1, 1, 3, 3, false);
        ui.refresh();

        assertTrue(ui.has("ed:bom:panel"));
        assertTrue(ui.text("ed:bom:text").contains("diamond_block"));
        assertTrue(ui.text("ed:bom:summary").contains("Short"));

        BufferedImage edShot = ui.screenshot("editor-bom-dark");
        assertNotNull(edShot);

        // Gather missing dispatches swarm gather for diamond_block
        ui.click("ed:bom:gather");
        assertTrue(lines(ui).stream().anyMatch(l -> l.startsWith("swarm gather diamond_block")));

        // Split build in GRID mode dispatches lease-bound region builds
        ed.setSplitStrategy(BlueprintSplitter.Strategy.GRID);
        ui.click("ed:split:send");
        assertTrue(lines(ui).stream().anyMatch(l -> l.contains("blueprint live") && l.contains("part=")));

        // Show on Map projects ghost overlay and switches layout to MAP
        ui.click("ed:ghost");
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        MapView map = map(ui);
        assertTrue(map.ghostCount() > 0);
    }

    @Test
    public void consoleDrawerLogsAutocompleteTargetsAndThemeSurvival() throws Exception {
        UiHarness ui = UiHarness.demo(6, CenterTheme.DEFAULT_DARK, CenterLayout.WALL).refresh();
        ConsolePanel console = ui.shell.console();
        assertFalse(console.visible());

        ui.click("footer.console");
        assertTrue(console.visible());
        assertTrue(ui.text("console:preview").contains("Will run on"));

        // Alerts from demo swarm are captured in the combined log
        assertTrue(ui.text("console:log").contains("[ALERT]"));

        // Tab autocomplete completes registry/command prefix
        ui.type("console:input", "swar");
        String completed = console.autocomplete();
        assertTrue("completed=" + completed, completed.startsWith("swarm"));

        // Select Azalea on wall and send a command through console
        ui.shell.wall().select("Azalea");
        ui.refresh();
        ui.type("console:input", "goto 10 64 20");
        ui.click("console:send");
        assertTrue(lines(ui).contains("swarm send Azalea goto 10 64 20"));
        assertTrue(ui.text("console:log").contains("[CMD]"));
        assertTrue(ui.text("console:log").contains("[REPLY]"));

        ui.refresh();
        BufferedImage shot = ui.screenshot("console-wall-dark");
        assertNotNull(shot);

        // Console state & log lines survive theme rebuild
        Path dir = Files.createTempDirectory("console-theme");
        ui.shell.setPrefsDir(dir);
        ThemeChoice.save(ThemeChoice.file(dir), "ocean");
        ui.refresh();
        assertEquals("ocean", ui.shell.themeName());
        assertTrue(ui.shell.console().visible());
        assertTrue(ui.text("console:log").contains("[CMD]"));
    }

    private static MapView map(UiHarness ui) {
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof MapView m) return m;
        }
        throw new AssertionError("no MapView");
    }

    private static List<String> lines(UiHarness ui) {
        List<String> out = new ArrayList<>();
        for (CenterRequest r : ui.port.sent()) out.add(r.line());
        return out;
    }
}
