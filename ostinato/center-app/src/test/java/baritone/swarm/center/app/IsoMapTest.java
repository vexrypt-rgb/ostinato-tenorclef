package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.feed.TerrainBuilder;
import baritone.swarm.center.feed.TerrainFeed;
import baritone.swarm.rts.Blueprint;
import org.junit.Test;

import javax.swing.JComponent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Ostinato: 3D isometric view for RTS mode in the command center.
 * Covers camera projection/inverse/ray-picking math, 4-way rotation, terrain rendering with no cracks,
 * RTS selection/move/area/multi-layer blueprint paint in 3D iso, theme rebuild persistence, and frame budget.
 */
public class IsoMapTest {

    @Test public void cameraRoundTripAndRotationsAtEveryZoom() {
        MapCamera base = new MapCamera();
        base.size(900, 650);
        IsoMapCamera iso = new IsoMapCamera(base);
        iso.setRefY(64.0);
        double[] xs = {0, 12.5, -40.25, 250};
        double[] ys = {50, 64, 72.5, 90};
        double[] zs = {0, -8.5, 33.75, -180};
        for (int rot = 0; rot < 4; rot++) {
            iso.setRotation(rot);
            for (int zoom = MapCamera.MIN_ZOOM; zoom <= MapCamera.MAX_ZOOM; zoom += 3) {
                base.setZoom(zoom);
                base.setCenter(18.5, -12.25);
                for (int i = 0; i < xs.length; i++) {
                    double[] sc = iso.worldToScreen(xs[i], ys[i], zs[i]);
                    double[] back = iso.screenToWorldAtY(sc[0], sc[1], ys[i]);
                    assertEquals("rot=" + rot + " zoom=" + zoom + " x", xs[i], back[0], 1e-6);
                    assertEquals("rot=" + rot + " zoom=" + zoom + " z", zs[i], back[1], 1e-6);
                }
            }
        }
        // Integer rotated grid mapping must be an exact bijection in every rotation.
        for (int rot = 0; rot < 4; rot++) {
            iso.setRotation(rot);
            for (int wx = -5; wx <= 5; wx++) {
                for (int wz = -5; wz <= 5; wz++) {
                    int gx = iso.rotGridX(wx, wz);
                    int gz = iso.rotGridZ(wx, wz);
                    assertEquals(wx, iso.worldXFromRot(gx, gz));
                    assertEquals(wz, iso.worldZFromRot(gx, gz));
                }
            }
        }
    }

    @Test public void panAndZoomKeepCursorWorldPointInEveryRotation() {
        MapCamera base = new MapCamera();
        base.size(800, 600);
        IsoMapCamera iso = new IsoMapCamera(base);
        iso.setRefY(64);
        for (int rot = 0; rot < 4; rot++) {
            iso.setRotation(rot);
            base.setZoom(4);
            base.setCenter(10, -20);
            double[] before = iso.screenToWorld(250, 180);
            iso.zoomAt(250, 180, 4);
            double[] after = iso.screenToWorld(250, 180);
            assertEquals("rot=" + rot + " zoom x", before[0], after[0], 1e-5);
            assertEquals("rot=" + rot + " zoom z", before[1], after[1], 1e-5);
            assertEquals(8, base.zoom());

            // Panning by (+40, -20) should move the world point previously at (300, 300) to (340, 280).
            double[] target = iso.screenToWorld(300, 300);
            iso.panPixels(40, -20);
            double[] moved = iso.worldToScreen(target[0], 64, target[1]);
            assertEquals("rot=" + rot + " pan sx", 340, moved[0], 1e-5);
            assertEquals("rot=" + rot + " pan sy", 280, moved[1], 1e-5);
        }
    }

    @Test public void surfaceRayPickHitsElevatedHilltopAndFallsBackOverVoid() {
        TerrainFeed hill = TerrainBuilder.around("Copper", 1000L, 1L, 'o', 0, 0, new TerrainBuilder.Columns() {
            @Override public String block(int x, int z) {
                return (x >= 4 && x <= 8 && z >= 4 && z <= 8) ? "stone" : "grass_block";
            }

            @Override public int y(int x, int z) {
                return (x >= 4 && x <= 8 && z >= 4 && z <= 8) ? 76 : 64;
            }
        });
        MapTerrain.Grid grid = MapTerrain.Grid.merge(List.of(hill), 'o');
        assertFalse(grid.isEmpty());
        assertEquals(64, grid.minY);
        assertEquals(76, grid.maxY);

        MapCamera base = new MapCamera();
        base.size(800, 600);
        base.setZoom(8);
        base.setCenter(6, 6);
        IsoMapCamera iso = new IsoMapCamera(base);
        iso.setRefY(64);

        for (int rot = 0; rot < 4; rot++) {
            iso.setRotation(rot);
            double[] peakScreen = iso.worldToScreen(6.5, 77.0, 6.5);
            int[] hit = iso.pickSurface(peakScreen[0], peakScreen[1], grid);
            assertEquals("rot=" + rot + " hit x", 6, hit[0]);
            assertEquals("rot=" + rot + " hit y", 76, hit[1]);
            assertEquals("rot=" + rot + " hit z", 6, hit[2]);
        }

        // Far outside terrain falls back cleanly to refY.
        int[] voidHit = iso.pickSurface(10, 10, MapTerrain.Grid.EMPTY);
        assertEquals(64, voidHit[1]);
    }

    @Test public void toggleRotationInteractionAndRtsOrdersWorkIn3dIso() throws Exception {
        Path dir = Files.createTempDirectory("iso-rts");
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Spelunker").role("miner", "miner work");
        ui.swarm.bot("Azalea").role("farmer", "farmer deposit");
        ui.shell.show(CenterLayout.MAP);
        ui.shell.setPrefsDir(dir);
        ui.refresh();
        MapView map = map(ui);

        assertFalse(map.isometric());
        assertFalse(ui.find("map:iso:rotL").isVisible());
        assertEquals("rts=true", CenterApp.mapAuto(ui.shell, "rts:on"));
        assertEquals("iso=true rot=0", CenterApp.mapAuto(ui.shell, "iso:on"));
        assertTrue(map.isometric());
        assertTrue(ui.find("map:iso:rotL").isVisible());
        assertTrue(ui.find("map:iso:rotR").isVisible());

        // Rotate via buttons and Q/E keys.
        ui.click("map:iso:rotR");
        assertEquals(1, map.isoRotation());
        ui.click("map:iso:rotL");
        assertEquals(0, map.isoRotation());
        JComponent canvas = (JComponent) ui.find("map:canvas");
        sendKey(canvas, KeyEvent.VK_E);
        assertEquals(1, map.isoRotation());
        sendKey(canvas, KeyEvent.VK_Q);
        assertEquals(0, map.isoRotation());

        // Selection clicks land accurately on isometric bot pawns.
        assertEquals("selected=[Azalea, Spelunker]", CenterApp.mapAuto(ui.shell, "select:Azalea+Spelunker"));
        assertEquals(List.of("Azalea", "Spelunker"), map.selected());

        // Right-click move order on open ground in 3D Iso sends goto.
        String ordered = CenterApp.mapAuto(ui.shell, "rts:order:120,-40");
        assertTrue(ordered, ordered.startsWith("order 120,-40"));
        assertEquals(List.of("swarm send Azalea goto 120 -40", "swarm send Spelunker goto 120 -40"), lines(ui));

        // Multi-layer blueprint paint and live status cubes render in 3D Iso.
        ui.type("rts:bp:y", "64").type("rts:bp:h", "3").type("rts:bp:block", "stone_bricks");
        String painted = CenterApp.mapAuto(ui.shell, "rts:paint:10,10,12,11");
        assertTrue(painted, painted.startsWith("blueprint base 18 blocks"));
        assertEquals(18, map.blueprintCells());
        Blueprint bp = map.rts().blueprint();
        assertNotNull(bp.at(10, 66, 10));

        // Pan and wheel zoom in 3D Iso move the shared camera.
        double cxBefore = map.camera().centerX();
        int zBefore = map.camera().zoom();
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                40, 40, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK, 100, 70, 0, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                100, 70, 1, false, MouseEvent.BUTTON1));
        // Paint was armed during paint, turn it off to verify pan works cleanly
        ui.click("rts:bp:paint");
        cxBefore = map.camera().centerX();
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                40, 40, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK, 100, 70, 0, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                100, 70, 1, false, MouseEvent.BUTTON1));
        assertNotEquals(cxBefore, map.camera().centerX(), 0.01);
        canvas.dispatchEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                200, 200, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1));
        assertEquals(Math.min(MapCamera.MAX_ZOOM, zBefore + 1), map.camera().zoom());
    }

    @Test public void isometricStateAndRotationSurviveThemeChange() throws Exception {
        Path dir = Files.createTempDirectory("iso-theme");
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.shell.setPrefsDir(dir);
        ui.click("header.layout.map");
        assertEquals("rts=true", CenterApp.mapAuto(ui.shell, "rts:on"));
        assertEquals("iso=true rot=0", CenterApp.mapAuto(ui.shell, "iso:on"));
        assertEquals("iso=true rot=2", CenterApp.mapAuto(ui.shell, "iso:rot:2"));

        ThemeChoice.save(ThemeChoice.file(dir), "light");
        ui.refresh();
        assertEquals("light", ui.shell.themeName());
        MapView after = map(ui);
        assertTrue(after.isometric());
        assertEquals(2, after.isoRotation());
        assertTrue(after.rts().visible());
    }

    @Test public void elevenBotsWithRollingHillsRepaintFastIn3dIso() {
        UiHarness ui = UiHarness.demo(11, CenterTheme.DEFAULT_DARK, CenterLayout.MAP).refresh();
        MapView map = map(ui);
        map.setIsometric(true);
        map.camera().setZoom(6);
        ui.refresh();
        BufferedImage img = new BufferedImage(UiHarness.WIDTH, UiHarness.HEIGHT, BufferedImage.TYPE_INT_RGB);
        // Warm-up 1 frame (JIT + merged terrain grid cache)
        ui.shell.component().paint(img.getGraphics());
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            ui.shell.component().paint(img.getGraphics());
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("20 isometric repaints took " + ms + " ms", ms < 600);
    }

    @Test public void screenshotsInEveryCoreThemeAndRotatedWith3dBlueprint() throws Exception {
        Map<String, CenterTheme> themes = Map.of(
                "dark", CenterTheme.DEFAULT_DARK,
                "light", CenterTheme.LIGHT,
                "high-contrast", CenterTheme.HIGH_CONTRAST,
                "colorblind", CenterTheme.COLORBLIND_SAFE);
        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            UiHarness h = UiHarness.demo(8, e.getValue(), CenterLayout.MAP).refresh();
            MapView m = map(h);
            m.rts().setVisible(true);
            m.setIsometric(true);
            m.camera().setZoom(6);
            m.centerOn("Copper");
            // Paint a small 3D stacked fort wall near Copper so the screenshot exercises 3D blueprint cubes too.
            h.type("rts:bp:y", "65").type("rts:bp:h", "3").type("rts:bp:block", "stone_bricks");
            for (int x = -4; x <= 4; x++) {
                m.rts().paint(x, -4, false);
                m.rts().paint(x, 4, false);
            }
            for (int z = -3; z <= 3; z++) {
                m.rts().paint(-4, z, false);
                m.rts().paint(4, z, false);
            }
            m.replaceSelection(List.of("Copper", "Azalea"));
            h.refresh();
            BufferedImage img = h.screenshot("iso-rts-" + e.getKey());
            assertNotNull(img);
            assertTrue(h.has("map:toggle:iso"));
            if ("dark".equals(e.getKey())) {
                m.setIsoRotation(1);
                h.refresh();
                h.screenshot("iso-rts-dark-rot1");
            }
        }
    }

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

    private static void sendKey(JComponent canvas, int keyCode) {
        KeyEvent ev = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                keyCode, KeyEvent.CHAR_UNDEFINED);
        for (java.awt.event.KeyListener l : canvas.getKeyListeners()) {
            l.keyPressed(ev);
        }
    }
}
