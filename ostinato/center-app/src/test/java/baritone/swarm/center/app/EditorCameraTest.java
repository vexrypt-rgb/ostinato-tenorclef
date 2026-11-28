package baritone.swarm.center.app;

import baritone.swarm.rts.Blueprint;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: orbit camera project/pick and ray hits against blueprint cells. */
public class EditorCameraTest {

    @Test public void projectThenRayHitsTheLookedAtPoint() {
        EditorCamera c = new EditorCamera();
        c.lookAt(10, -60, 20);
        c.distance = 24;
        c.yaw = 0.75;
        c.pitch = 0.55;
        double[] p = new double[3];
        c.project(10, -60, 20, 0, 0, 640, 480, p);
        assertEquals("target is near the screen centre", 320, p[0], 8);
        assertEquals(240, p[1], 8);
        assertTrue("target is in front of the eye", p[2] > 1);
        double[] o = new double[3];
        double[] d = new double[3];
        c.ray((float) p[0], (float) p[1], 0, 0, 640, 480, o, d);
        // walk along the ray: should pass close to the target
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double t = p[2];
        double hx = o[0] + d[0] / len * t;
        double hy = o[1] + d[1] / len * t;
        double hz = o[2] + d[2] / len * t;
        assertEquals(10, hx, 0.6);
        assertEquals(-60, hy, 0.6);
        assertEquals(20, hz, 0.6);
    }

    @Test public void orbitAndDollyStayBounded() {
        EditorCamera c = new EditorCamera();
        c.orbit(10, 40);
        assertTrue(c.pitch <= 1.25);
        c.dolly(100);
        assertTrue(c.distance >= 6);
        c.dolly(-100);
        assertTrue(c.distance <= 160);
    }

    @Test public void fitFramesABox() {
        EditorCamera c = new EditorCamera();
        c.fit(new int[]{0, -60, 0, 7, -59, 3});
        assertEquals(4.0, c.targetX, 0.01);
        assertEquals(-59.0, c.targetY, 0.01);
        assertEquals(2.0, c.targetZ, 0.01);
        assertTrue(c.distance > 8);
    }

    @Test public void aRayHitsACellAndNamesThePlaceFace() {
        Blueprint b = new Blueprint("hit");
        b.paint(0, -60, 0, "cobblestone");
        // eye on +Z looking at origin: start at (0.5, -59.5, 8) going toward -Z
        double[] o = {0.5, -59.5, 8};
        double[] d = {0, 0, -1};
        EditorHit hit = EditorHit.trace(o, d, b.cells(), -60);
        assertEquals(EditorHit.Kind.CELL, hit.kind);
        assertEquals(0, hit.x);
        assertEquals(-60, hit.y);
        assertEquals(0, hit.z);
        assertEquals(0, hit.placeX);
        assertEquals(-60, hit.placeY);
        assertEquals(1, hit.placeZ); // entered from +Z
    }

    @Test public void aRayThatMissesCellsLandsOnTheLayerPlane() {
        double[] o = {3.2, 10, 4.7};
        double[] d = {0, -1, 0};
        EditorHit hit = EditorHit.trace(o, d, List.of(), -60);
        assertEquals(EditorHit.Kind.LAYER, hit.kind);
        assertEquals(3, hit.placeX);
        assertEquals(-60, hit.placeY);
        assertEquals(4, hit.placeZ);
    }
}
