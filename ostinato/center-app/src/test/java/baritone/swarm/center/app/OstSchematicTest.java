package baritone.swarm.center.app;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Ostinato: ost1 text reads and writes without Minecraft, and the ghost is the placement in world XZ. */
public class OstSchematicTest {

    static final String WALL = "ost1\n3 1 1\n0 0 0 cobblestone\n1 0 0 minecraft:cobblestone\n2 0 0 cobblestone\n";

    @Test public void roundTripKeepsTheBlocksAndDropsAir() {
        OstSchematic s = OstSchematic.read(WALL);
        assertEquals(3, s.width());
        assertEquals(1, s.height());
        assertEquals(1, s.length());
        assertEquals(3, s.cells().size());
        assertEquals(3, (int) s.materials().get("cobblestone"));
        OstSchematic again = OstSchematic.read(s.write());
        assertEquals(s.cells(), again.cells());
    }

    @Test public void aLaterLineReplacesTheSameCell() {
        OstSchematic s = OstSchematic.read("ost1\n1 1 1\n0 0 0 dirt\n0 0 0 cobblestone\n");
        assertEquals(List.of(new OstSchematic.Cell(0, 0, 0, "cobblestone")), s.cells());
    }

    @Test public void ghostIsTheOriginPlusTheCellAndALayerHidesTheOthers() {
        OstSchematic s = OstSchematic.read("ost1\n2 2 1\n0 0 0 cobblestone\n1 1 0 cobblestone\n");
        List<int[]> all = s.ghost(360, 150, 300, null);
        assertEquals(2, all.size());
        assertEquals(360, all.get(0)[0]);
        assertEquals(300, all.get(0)[1]);
        assertEquals(361, all.get(1)[0]);
        assertEquals(1, s.ghost(360, 150, 300, 1).size());
        assertEquals(0, s.ghost(360, 150, 300, 4).size());
    }

    @Test public void aBadHeaderOrABlockOutsideIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> OstSchematic.read("nope\n"));
        assertThrows(IllegalArgumentException.class, () -> OstSchematic.read("ost1\n1 1 1\n2 0 0 cobblestone\n"));
        assertThrows(IllegalArgumentException.class, () -> OstSchematic.read("ost1\n1 1 1\n0 0 0 Not A Block\n"));
    }

    @Test public void explicitBlockStatePropertiesRoundTripAndMaterialsGroupByBaseBlock() {
        String text = "ost1\n3 1 1\n"
                + "0 0 0 oak_log[axis=x]\n"
                + "1 0 0 minecraft:oak_slab[type=top]\n"
                + "2 0 0 oak_stairs[half=top,facing=east]\n";
        OstSchematic s = OstSchematic.read(text);
        assertEquals(3, s.cells().size());
        assertEquals("oak_log", s.cells().get(0).block());
        assertEquals("x", s.cells().get(0).properties().get("axis"));
        assertEquals("oak_slab", s.cells().get(1).block());
        assertEquals("top", s.cells().get(1).properties().get("type"));
        assertEquals("oak_stairs", s.cells().get(2).block());
        assertEquals("east", s.cells().get(2).properties().get("facing"));
        assertEquals("top", s.cells().get(2).properties().get("half"));
        assertEquals("oak_stairs[facing=east,half=top]", s.cells().get(2).spec());
        // Materials tally by base block id, not per-orientation
        assertEquals(1, (int) s.materials().get("oak_stairs"));
        // Canonical write -> read round-trip preserves every property
        OstSchematic again = OstSchematic.read(s.write());
        assertEquals(s.cells(), again.cells());
    }
}
