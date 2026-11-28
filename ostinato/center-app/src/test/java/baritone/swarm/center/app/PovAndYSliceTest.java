package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.feed.SwarmView;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Ostinato: Tests for P4 Bot POV switching (render-swap for hidden bots, stream preview for headless bots,
 * UI hooks in Wall/Focus/Map, banner prev/next/return, theme rebuild survival) and P2 Y-level Cave/Maze Cut
 * slider in 2D and 3D Iso War Room map.
 */
public class PovAndYSliceTest {

    @Test
    public void povSwitcherHandlesHiddenRenderSwapHeadlessStreamCycleAndReturn() {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Copper").runMode("visible");
        ui.swarm.bot("Azalea").runMode("hidden");
        ui.swarm.bot("Spelunker").runMode("headless");
        SwarmView view = ui.source.snapshot();

        PovSwitcher sw = new PovSwitcher();
        assertFalse(sw.isWatching());

        // Hidden bot uses render-swap -> routed pov show
        PovSwitcher.Transition t1 = sw.watch("Azalea", view);
        assertEquals("Azalea", t1.watching());
        assertEquals(PovSwitcher.Mode.RENDER_SWAP, t1.mode());
        assertEquals(List.of("swarm send Azalea pov show"), t1.commands());

        // Switching to headless bot releases previous hidden bot first, then uses stream preview
        PovSwitcher.Transition t2 = sw.watch("Spelunker", view);
        assertEquals("Spelunker", t2.watching());
        assertEquals(PovSwitcher.Mode.STREAM, t2.mode());
        assertEquals(List.of("swarm send Azalea pov hide"), t2.commands());

        // Cycle forward wraps back around
        PovSwitcher.Transition t3 = sw.cycle(view, 1);
        assertNotNull(t3.watching());
        assertNotEquals("Spelunker", t3.watching());

        // Switch back to hidden bot then return to own player -> sends pov hide
        sw.watch("Azalea", view);
        PovSwitcher.Transition ret = sw.returnToOwn(view);
        assertNull(ret.watching());
        assertEquals(PovSwitcher.Mode.NONE, ret.mode());
        assertEquals(List.of("swarm send Azalea pov hide"), ret.commands());
        assertFalse(sw.isWatching());
    }

    @Test
    public void povUiHooksBannerControlsAndStreamScreenshot() throws Exception {
        UiHarness ui = UiHarness.demo(6, CenterTheme.DEFAULT_DARK, CenterLayout.WALL);
        ui.swarm.bot("Azalea").runMode("hidden");
        ui.swarm.bot("Spelunker").runMode("headless").yaw(35f);
        ui.refresh();

        // Watch POV button on tile card
        assertTrue(ui.has("tile:Azalea:pov"));
        ui.click("tile:Azalea:pov");
        assertEquals("Azalea", ui.shell.pov().watching());
        assertEquals(PovSwitcher.Mode.RENDER_SWAP, ui.shell.pov().mode());
        assertTrue(ui.shell.povPanel().visible());
        assertTrue(ui.text("pov:info").contains("Azalea"));
        assertTrue(ui.text("pov:info").contains("render-swap"));
        assertEquals(List.of("swarm send Azalea pov show"), lines(ui));

        // Watch POV button in Focus panel for headless bot -> opens stream preview
        ui.shell.wall().select("Spelunker");
        ui.refresh();
        assertTrue(ui.has("focus:button:pov"));
        ui.click("focus:button:pov");
        assertEquals("Spelunker", ui.shell.pov().watching());
        assertEquals(PovSwitcher.Mode.STREAM, ui.shell.pov().mode());
        assertTrue(ui.find("pov:stream").isVisible());
        assertTrue(lines(ui).contains("swarm send Azalea pov hide"));

        ui.refresh();
        BufferedImage shot = ui.screenshot("pov-stream-dark");
        assertNotNull(shot);

        // Next / Prev / Return buttons on banner
        ui.click("pov:next");
        assertNotEquals("Spelunker", ui.shell.pov().watching());
        ui.click("pov:prev");
        assertEquals("Spelunker", ui.shell.pov().watching());

        // Survives theme rebuild
        Path dir = Files.createTempDirectory("pov-theme");
        ui.shell.setPrefsDir(dir);
        ThemeChoice.save(ThemeChoice.file(dir), "nether");
        ui.refresh();
        assertEquals("nether", ui.shell.themeName());
        assertEquals("Spelunker", ui.shell.pov().watching());
        assertTrue(ui.shell.povPanel().visible());

        // Return to own player hides banner
        ui.click("pov:return");
        assertFalse(ui.shell.pov().isWatching());
        assertFalse(ui.shell.povPanel().visible());
    }

    @Test
    public void ySliceControlsAutoHookThemePersistenceAndIsoScreenshot() throws Exception {
        UiHarness ui = UiHarness.demo(8, CenterTheme.DEFAULT_DARK, CenterLayout.MAP).refresh();
        MapView map = map(ui);
        assertFalse(map.ySliceEnabled());
        assertEquals(64, map.ySlice());

        // Toggle Y-cut via header button
        ui.click("map:yslice:toggle");
        assertTrue(map.ySliceEnabled());
        assertTrue(ui.find("map:yslice:val").isVisible());
        assertTrue(ui.find("map:yslice:up").isVisible());
        assertTrue(ui.find("map:yslice:down").isVisible());

        ui.click("map:yslice:up");
        assertEquals(65, map.ySlice());
        assertEquals("65", ui.text("map:yslice:val"));
        ui.click("map:yslice:down");
        assertEquals(64, map.ySlice());

        // Auto hook sets Y-cut level cleanly
        assertEquals("yslice=true:66", CenterApp.mapAuto(ui.shell, "yslice:66"));
        assertTrue(map.ySliceEnabled());
        assertEquals(66, map.ySlice());

        // 3D Iso + stacked blueprint + Y-slice cross-section screenshot
        map.rts().setVisible(true);
        map.setIsometric(true);
        map.camera().setZoom(6);
        map.centerOn("Copper");
        ui.type("rts:bp:y", "65").type("rts:bp:h", "4").type("rts:bp:block", "stone_bricks");
        for (int x = -3; x <= 3; x++) {
            map.rts().paint(x, -3, false);
            map.rts().paint(x, 3, false);
        }
        ui.refresh();
        BufferedImage isoCut = ui.screenshot("iso-yslice-dark");
        assertNotNull(isoCut);

        // Y-slice state survives theme rebuild
        Path dir = Files.createTempDirectory("yslice-theme");
        ui.shell.setPrefsDir(dir);
        ThemeChoice.save(ThemeChoice.file(dir), "light");
        ui.refresh();
        MapView after = map(ui);
        assertTrue(after.ySliceEnabled());
        assertEquals(66, after.ySlice());
        assertTrue(after.isometric());

        // Auto hook turns Y-cut off
        assertEquals("yslice=false:66", CenterApp.mapAuto(ui.shell, "yslice:off"));
        assertFalse(after.ySliceEnabled());
    }

    private static MapView map(UiHarness ui) {
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof MapView m) {
                return m;
            }
        }
        throw new AssertionError("no MapView");
    }

    private static List<String> lines(UiHarness ui) {
        List<String> out = new ArrayList<>();
        for (CenterRequest r : ui.port.sent()) {
            out.add(r.line());
        }
        return out;
    }
}
