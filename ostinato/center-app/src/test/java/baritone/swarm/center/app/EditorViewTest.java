package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.rts.Blueprint;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: Design/Live schematic editor in the command center. */
public class EditorViewTest {

    private static UiHarness ready(Path dir) {
        UiHarness ui = UiHarness.threeBots();
        ui.shell.setPrefsDir(dir);
        ui.shell.show(CenterLayout.EDITOR);
        ui.refresh();
        assertEquals(CenterLayout.EDITOR, ui.shell.shown());
        assertTrue(ui.has("ed:canvas"));
        return ui;
    }

    private static EditorView ed(UiHarness ui) {
        return ui.shell.editor();
    }

    private static List<String> lines(UiHarness ui) {
        List<String> out = new ArrayList<>();
        for (CenterRequest r : ui.sent()) {
            out.add(ui.port.lineFor(r));
        }
        return out;
    }

    @Test public void layoutParseAndHeaderToggleShowTheEditor() {
        assertEquals(CenterLayout.EDITOR, CenterLayout.parse("editor"));
        assertEquals("editor", CenterLayout.EDITOR.wire());
        assertEquals("Editor", CenterLayout.EDITOR.label());
        assertEquals("layout-editor", CenterLayout.EDITOR.formId());
        UiHarness ui = UiHarness.threeBots();
        ui.refresh();
        ui.click("header.layout.editor");
        assertEquals(CenterLayout.EDITOR, ui.shell.shown());
        assertEquals("layout-editor", ui.sent().get(0).formId());
    }

    @Test public void paintBoxWritesOst1AndLoadReadsItBack() throws Exception {
        Path dir = Files.createTempDirectory("ed");
        UiHarness ui = ready(dir);
        ui.type("ed:name", "wall");
        ui.type("ed:block", "cobblestone");
        ui.type("ed:y", "-60");
        ui.type("ed:h", "2");
        assertEquals("blueprint wall 16 blocks v16", CenterApp.editorAuto(ui.shell, "paint:-40,40,-37,41"));
        EditorView e = ed(ui);
        assertEquals(16, e.blueprint().size());
        assertNotNull(e.blueprint().at(-40, -60, 40));
        assertNotNull(e.blueprint().at(-37, -59, 41)); // h=2
        e.writeNow();
        Path ost = dir.resolve("blueprints").resolve("wall.ost");
        assertTrue(Files.isRegularFile(ost));
        Blueprint back = Blueprint.read("wall", Files.readString(ost));
        assertEquals(16, back.size());
        assertTrue(back.write().startsWith("ost1"));

        // mid-build style erase of one top cell
        ui.type("ed:h", "1");
        ui.type("ed:y", "-59");
        assertEquals("blueprint wall 15 blocks v17", CenterApp.editorAuto(ui.shell, "erase:-37,41,-37,41"));
        assertNull(e.blueprint().at(-37, -59, 41));
        assertEquals(15, e.blueprint().size());
        e.writeNow();
        assertEquals(15, Blueprint.read("wall", Files.readString(ost)).size());
    }

    @Test public void paletteAndClearAndImportSchem() throws Exception {
        Path dir = Files.createTempDirectory("ed");
        UiHarness ui = ready(dir);
        ui.click("ed:pal:oak_planks");
        assertEquals("oak_planks", ui.text("ed:block"));
        ui.type("ed:name", "hut");
        ui.type("ed:y", "-60");
        ui.type("ed:h", "1");
        ed(ui).paintBox(0, 0, 1, 0, false);
        assertEquals(2, ed(ui).blueprint().size());
        ui.click("ed:clear");
        assertEquals(0, ed(ui).blueprint().size());

        List<OstSchematic.Cell> cells = List.of(
                new OstSchematic.Cell(0, 0, 0, "stone"),
                new OstSchematic.Cell(1, 0, 0, "stone"),
                new OstSchematic.Cell(0, 1, 0, "glass"));
        Path schem = dir.resolve("tiny.schem");
        Files.write(schem, SchemImport.writeSchem(2, 2, 1, cells));
        ui.type("ed:name", "hut");
        ui.type("ed:origin", "5,-60,9");
        ui.type("ed:path", schem.toString());
        ui.click("ed:import");
        Blueprint b = ed(ui).blueprint();
        assertEquals(3, b.size());
        assertEquals("stone", b.at(5, -60, 9).block());
        assertEquals("glass", b.at(5, -59, 9).block());
        ui.screenshot("editor-dark");
    }

    @Test public void liveBuildSendsBlueprintLiveAndStatusColorsPlacedCells() throws Exception {
        Path dir = Files.createTempDirectory("ed");
        UiHarness ui = ready(dir);
        ui.type("ed:name", "pad");
        ui.type("ed:block", "cobblestone");
        ui.type("ed:y", "-60");
        ui.type("ed:h", "1");
        ed(ui).paintBox(0, 0, 2, 0, false);
        assertEquals(3, ed(ui).blueprint().size());
        // select Copper (host) by switching to the map, then back — or just Build live with no selection uses host
        ui.click("ed:mode:live");
        assertTrue(ed(ui).live());
        ui.click("ed:live");
        List<String> sent = lines(ui);
        assertTrue("sent " + sent, sent.stream().anyMatch(s -> s.contains("blueprint live pad")));
        Path ost = dir.resolve("blueprints").resolve("pad.ost");
        assertTrue(Files.isRegularFile(ost));

        // a bot status file: 2 of 3 placed
        Files.writeString(dir.resolve("blueprints").resolve("pad.status"),
                Blueprint.writeStatus(ed(ui).blueprint().version(),
                        List.of(new int[]{0, -60, 0}, new int[]{1, -60, 0}), 3, "building"));
        ed(ui).readStatus();
        assertEquals(2, ed(ui).placed().size());
        assertTrue(ui.text("ed:status").contains("2/3 placed"));
    }

    @Test public void themeRebuildKeepsTheBlueprintAndCamera() throws Exception {
        Path dir = Files.createTempDirectory("ed");
        UiHarness ui = ready(dir);
        ui.type("ed:name", "keep");
        ui.type("ed:y", "-60");
        ed(ui).paintBox(1, 2, 1, 2, false);
        double yaw = ed(ui).canvas().camera.yaw;
        ed(ui).canvas().camera.orbit(0.2, 0);
        ui.click("header.theme.light");
        assertEquals(CenterLayout.EDITOR, ui.shell.shown());
        assertEquals(1, ed(ui).blueprint().size());
        assertEquals("cobblestone", ed(ui).blueprint().at(1, -60, 2).block());
        assertEquals(yaw + 0.2, ed(ui).canvas().camera.yaw, 1e-9);
        assertEquals("keep", ui.text("ed:name"));
    }
}
