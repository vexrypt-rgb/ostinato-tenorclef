package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.TerrainBuilder;
import baritone.swarm.center.feed.TerrainFeed;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComboBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Ostinato: tests for remaining command-center design-doc features:
 * P2 Danger overlay (2D &amp; 3D iso), P2 Why-stuck diagnostics &amp; recovery actions,
 * P3 Ctrl+K Command Palette / Stop all / Presets / Dry-run / History &amp; Favorites,
 * P3 Schematic .schem / .litematic / .nbt exporters &amp; .nbt import,
 * P3 Flavor themes (Nether, End, Ocean, Rust) &amp; live Custom Theme Editor.
 */
public class CommandCenterFeaturesTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void dangerOverlayDetectsLavaFallsEntitiesAndLowHealthBots() throws Exception {
        TerrainFeed slice = TerrainBuilder.around("Copper", 1000L, 1L, 'o', 0, 0, new TerrainBuilder.Columns() {
            @Override public String block(int x, int z) {
                if (x == 2 && z == 2) return "lava";
                return "stone";
            }

            @Override public int y(int x, int z) {
                return (x >= 5 && x <= 8 && z >= 5 && z <= 8) ? 72 : 64;
            }
        });
        MapTerrain.Grid grid = MapTerrain.Grid.merge(List.of(slice), 'o');
        List<MapDanger.Hazard> terrain = MapDanger.terrainHazards(grid, -4, -4, 12, 12);
        assertTrue("lava detected", terrain.stream().anyMatch(h -> h.kind() == MapDanger.Kind.LAVA));
        assertTrue("8-block fall detected", terrain.stream().anyMatch(h -> h.kind() == MapDanger.Kind.FALL));

        UiHarness ui = UiHarness.threeBots();
        ui.swarm.terrain(slice);
        ui.swarm.bot("Azalea").health(4);
        ui.swarm.coordinator(new CoordinatorFeed("Copper", ui.now.get(), 1, "active", "test", List.of(),
                List.of(new CoordinatorFeed.Observation("e:o:3,64,3", "entity", 'o',
                        new int[]{3, 64, 3, 3, 64, 3}, "Copper", 90, "creeper", "confirmed", false, 500)), 1));
        ui.shell.show(CenterLayout.MAP);
        ui.refresh();
        List<MapDanger.Hazard> dyn = MapDanger.dynamicHazards(ui.swarm.snapshot(), 'o');
        assertTrue("entity detected", dyn.stream().anyMatch(h -> h.kind() == MapDanger.Kind.ENTITY && h.label().equals("creeper")));
        assertTrue("low health bot detected", dyn.stream().anyMatch(h -> h.kind() == MapDanger.Kind.BOT_DANGER && h.tip().contains("Azalea")));

        assertEquals("danger=true", CenterApp.mapAuto(ui.shell, "danger:on"));
        assertEquals("iso=true rot=0", CenterApp.mapAuto(ui.shell, "iso:on"));
        ui.refresh();
        assertNotNull(ui.screenshot("iso-danger-dark"));
    }

    @Test public void whyStuckDetectsStallAndRunsRepathAndHome() {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Azalea")
                .process("Goto")
                .path(new BotFeed.Route("pathing", "GoalBlock{10,64,20}", new int[]{10, 64, 20}, 15, 0, 15, List.of()));
        ui.refresh();
        // Select Azalea on the wall so FocusPanel opens
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof WallView w) {
                w.select("Azalea");
            }
        }
        ui.refresh();
        assertTrue(ui.text("focus:stuck"), ui.text("focus:stuck").startsWith("OK:"));

        // Advance clock past STALL_MS without moving Azalea
        ui.now.addAndGet(WhyStuck.STALL_MS + 1000L);
        ui.refresh();
        String stuck = ui.text("focus:stuck");
        assertTrue(stuck, stuck.startsWith("STUCK:"));
        assertTrue(stuck, stuck.contains("stalled"));
        assertTrue(stuck, stuck.contains("10 64 20"));

        ui.click("focus:button:repath");
        ui.click("focus:button:home");
        List<String> sent = lines(ui);
        assertEquals(List.of("swarm send Azalea goto 10 64 20", "swarm send Azalea home"), sent);
    }

    @Test public void commandPaletteDryRunPresetsFavoritesHistoryAndStopAll() throws Exception {
        Path dir = temporary.newFolder("pal").toPath();
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.shell.setPrefsDir(dir);

        // Top-bar Stop All sends stop to every bot
        ui.click("header.stopAll");
        List<String> stopped = lines(ui);
        assertEquals(3, stopped.size());
        assertTrue(stopped.contains("stop"));
        assertTrue(stopped.contains("swarm send Azalea stop"));
        assertTrue(stopped.contains("swarm send Spelunker stop"));
        int before = ui.sent().size();

        // Open Ctrl+K palette
        ui.click("header.palette");
        assertTrue(ui.shell.palette().visible());

        // Switching target to All bots automatically arms Dry-run preview
        @SuppressWarnings("unchecked")
        JComboBox<String> tgt = (JComboBox<String>) ui.find("palette:target");
        tgt.setSelectedItem("All bots");
        ui.type("palette:input", "mine diamond_ore");
        ui.click("palette:run");
        assertEquals("dry-run must not send commands", before, ui.sent().size());
        assertTrue(ui.text("palette:output"), ui.text("palette:output").startsWith("DRY-RUN (3):"));

        // Uncheck dry-run, execute on Host, star favorite, save preset
        ui.click("palette:dryrun");
        tgt.setSelectedItem("Host");
        ui.click("palette:run");
        assertEquals(before + 1, ui.sent().size());
        assertEquals("mine diamond_ore", ui.port.lineFor(ui.sent().get(before)));
        assertTrue(ui.shell.palette().history().contains("mine diamond_ore"));

        ui.click("palette:fav");
        assertTrue(ui.shell.palette().favorites().contains("mine diamond_ore"));
        ui.click("palette:savePreset");
        assertTrue(ui.shell.palette().presets().values().contains("mine diamond_ore"));
        assertTrue(Files.isRegularFile(dir.resolve("prefs").resolve("command-palette.properties")));
    }

    @Test public void schematicExportersAndStructureNbtRoundTrip() throws Exception {
        Path dir = temporary.newFolder("schem").toPath();
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.shell.setPrefsDir(dir);
        ui.shell.show(CenterLayout.EDITOR);
        EditorView ed = ui.shell.editor();
        ed.paintAt(10, -60, 20, false);
        ed.paintAt(11, -60, 20, false);
        ed.paintAt(10, -59, 20, false);
        assertEquals(3, ed.blueprint().size());

        for (String ext : List.of(".schem", ".litematic", ".nbt", ".ost")) {
            Path out = dir.resolve("fort" + ext);
            ui.type("ed:path", out.toString());
            ui.click("ed:export");
            assertTrue(ext + " exported", Files.isRegularFile(out));
            assertTrue(ui.text("ed:status"), ui.text("ed:status").contains("exported 3 blocks"));
            if (!ext.equals(".ost")) {
                OstSchematic back = SchemImport.fromFile(out);
                assertEquals(ext + " cell count", 3, back.cells().size());
                assertEquals(2, back.width());
                assertEquals(2, back.height());
                assertEquals(1, back.length());
            }
        }

        // Import .nbt directly back through EditorView
        ed.clear();
        assertEquals(0, ed.blueprint().size());
        ui.type("ed:path", dir.resolve("fort.nbt").toString());
        ui.click("ed:import");
        assertEquals(3, ed.blueprint().size());
        assertTrue(ui.text("ed:status"), ui.text("ed:status").contains("imported 3 blocks"));
    }

    @Test public void flavorThemesCustomJsonAndLiveThemeEditorWithAaWarning() throws Exception {
        Path dir = temporary.newFolder("themes").toPath();
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.shell.setPrefsDir(dir);

        // Select flavor theme Nether from dropdown
        @SuppressWarnings("unchecked")
        JComboBox<String> more = (JComboBox<String>) ui.find("header.theme.more");
        more.setSelectedItem("nether");
        ui.refresh();
        assertEquals("nether", ui.shell.themeName());
        assertEquals(CenterTheme.NETHER.background(), ui.shell.component().getBackground().getRGB() & 0xFFFFFF);
        assertNotNull(ui.screenshot("flavor-nether"));

        // Open Theme Editor, introduce a low-contrast token and verify WCAG AA warning appears
        ui.click("header.theme.edit");
        ui.refresh();
        assertTrue(ui.shell.themeEditor().visible());
        assertNotNull(ui.screenshot("theme-editor-nether"));
        ui.shell.themeEditor().setTokenColor("text", CenterTheme.NETHER.surface());
        assertTrue(ui.text("theme:ed:aa"), ui.text("theme:ed:aa").contains("WCAG AA warning"));

        // Reset restores passing contrast
        ui.click("theme:ed:reset");
        assertTrue(ui.text("theme:ed:aa"), ui.text("theme:ed:aa").contains("WCAG AA contrast pass"));

        // Edit accent color, save custom theme JSON, export/import, and reload from theme file
        ui.shell.themeEditor().setTokenColor("accent", 0xFF9933);
        ui.type("theme:ed:name", "crimson-forge");
        ui.click("theme:ed:save");
        Path saved = dir.resolve("themes").resolve("crimson-forge.json");
        assertTrue(Files.isRegularFile(saved));
        assertEquals("crimson-forge", ui.shell.themeName());

        // Fresh shell pointing at the same prefsDir loads the custom JSON theme automatically
        ThemeChoice.save(ThemeChoice.file(dir), "crimson-forge");
        UiHarness fresh = UiHarness.threeBots();
        fresh.shell.setPrefsDir(dir);
        fresh.refresh();
        assertEquals("crimson-forge", fresh.shell.themeName());
        assertEquals(0xFF9933, CustomThemeStore.resolve(dir, "crimson-forge").accent());
    }

    private static List<String> lines(UiHarness ui) {
        List<String> out = new ArrayList<>();
        for (CenterRequest r : ui.sent()) {
            out.add(ui.port.lineFor(r));
        }
        return out;
    }
}
