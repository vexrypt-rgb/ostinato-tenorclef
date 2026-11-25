package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.rts.Blueprint;
import org.junit.Test;

import javax.swing.JComboBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: RTS mode panel on the war-room map: roles, right-click orders, blueprint paint and live build. */
public class RtsViewTest {

    private static MapView map(UiHarness ui) {
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof MapView m) {
                return m;
            }
        }
        throw new AssertionError("no map");
    }

    private static List<String> lines(UiHarness ui) {
        List<String> out = new ArrayList<>();
        for (CenterRequest r : ui.sent()) {
            out.add(ui.port.lineFor(r));
        }
        return out;
    }

    private static UiHarness ready(Path dir) {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Spelunker").role("miner", "miner work (working) deposits=2");
        ui.swarm.bot("Azalea").role("farmer", "farmer deposit (batch of 64)");
        ui.shell.show(CenterLayout.MAP);
        ui.shell.setPrefsDir(dir);
        ui.refresh();
        assertEquals("rts=true", CenterApp.mapAuto(ui.shell, "rts:on"));
        assertEquals("selected=[Azalea, Spelunker]", CenterApp.mapAuto(ui.shell, "select:Azalea+Spelunker"));
        return ui;
    }

    @Test public void assignsRoleToEverySelectedBotOverTheSwarmLink() throws Exception {
        UiHarness ui = ready(Files.createTempDirectory("rts"));
        @SuppressWarnings("unchecked") JComboBox<String> role = (JComboBox<String>) ui.find("rts:role");
        role.setSelectedItem("miner");
        ui.type("rts:ore", "iron_ore,coal_ore").type("rts:chest", "1,64,1").type("rts:batch", "32");
        String line = baritone.swarm.rts.RoleOrder.parse("role miner chest=1,64,1 ore=iron_ore,coal_ore batch=32").line();
        assertTrue(ui.text("rts:preview"), ui.text("rts:preview").contains("#" + line));
        ui.click("rts:assign");
        assertEquals(List.of("swarm send Azalea " + line, "swarm send Spelunker " + line), lines(ui));
        ui.click("rts:idle");
        assertTrue(lines(ui).contains("swarm send Azalea role idle"));
    }

    @Test public void badRoleIsRefusedInThePanelNotSent() throws Exception {
        UiHarness ui = ready(Files.createTempDirectory("rts"));
        @SuppressWarnings("unchecked") JComboBox<String> role = (JComboBox<String>) ui.find("rts:role");
        role.setSelectedItem("miner");
        ui.click("rts:assign");
        assertTrue(lines(ui).isEmpty());
        assertTrue(ui.text("rts:reply").contains("miner needs ore"));
    }

    @Test public void rightClickOnOpenGroundMovesTheSelection() throws Exception {
        UiHarness ui = ready(Files.createTempDirectory("rts"));
        String r = CenterApp.mapAuto(ui.shell, "rts:order:120,-40");
        assertTrue(r, r.startsWith("order 120,-40"));
        assertEquals(List.of("swarm send Azalea goto 120 -40", "swarm send Spelunker goto 120 -40"), lines(ui));
    }

    @Test public void paintingWritesTheBlueprintAndBuildLiveStartsIt() throws Exception {
        Path dir = Files.createTempDirectory("rts");
        UiHarness ui = ready(dir);
        ui.type("rts:bp:y", "64").type("rts:bp:block", "cobblestone");
        String painted = CenterApp.mapAuto(ui.shell, "rts:paint:10,10,12,11");
        assertTrue(painted, painted.startsWith("blueprint base 6 blocks"));
        assertEquals(6, map(ui).blueprintCells());
        CenterApp.mapAuto(ui.shell, "rts:erase:12,11,12,11");
        assertEquals(5, map(ui).rts().blueprint().size());
        ui.click("rts:bp:live");
        Path file = dir.resolve("blueprints").resolve("base.ost");
        Blueprint back = Blueprint.read("base", Files.readString(file));
        assertEquals(5, back.size());
        assertEquals(64, back.origin()[1]);
        assertNotNull(back.at(10, 64, 10));
        assertNull(back.at(12, 64, 11));
        assertEquals(List.of("swarm send Azalea blueprint live base", "swarm send Spelunker blueprint live base"), lines(ui));
        // the bot's status turns placed cells solid
        Files.writeString(dir.resolve("blueprints").resolve("base.status"),
                Blueprint.writeStatus(back.version(), List.of(new int[]{10, 64, 10}, new int[]{11, 64, 10}), 5, "building"));
        map(ui).rts().readStatus();
        assertTrue(ui.text("rts:bp:status"), ui.text("rts:bp:status").contains("2/5 placed"));
        map(ui).placeCamera(24, 11, 10.5);
        ui.refresh();
        assertTrue(map(ui).rts().component().getHeight() > 0);
        ui.screenshot("rts-dark");
    }

    @Test public void economyLineCountsRolesAndDepositing() throws Exception {
        UiHarness ui = ready(Files.createTempDirectory("rts"));
        String e = ui.text("rts:economy");
        assertTrue(e, e.contains("F farmer 1"));
        assertTrue(e, e.contains("M miner 1"));
        assertTrue(e, e.contains("depositing 1"));
    }
}
