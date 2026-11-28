package baritone.gui.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RadialMenuTest {

    @Test
    public void deadzoneReturnsMinusOne() {
        assertEquals(-1, RadialMenu.sliceAt(0, 0, RadialMenu.DEADZONE_PX));
        assertEquals(-1, RadialMenu.sliceAt(10, 10, RadialMenu.DEADZONE_PX));
    }

    @Test
    public void clockwiseSectorsMatchSixSlices() {
        assertEquals(6, RadialMenu.SLICES.size());
        // 12 o'clock (top) -> 0 (goto)
        assertEquals(0, RadialMenu.sliceAt(0, -60, 18));
        // 2 o'clock -> 1 (mine)
        assertEquals(1, RadialMenu.sliceAt(52, -30, 18));
        // 4 o'clock -> 2 (follow)
        assertEquals(2, RadialMenu.sliceAt(52, 30, 18));
        // 6 o'clock (bottom) -> 3 (build)
        assertEquals(3, RadialMenu.sliceAt(0, 60, 18));
        // 8 o'clock -> 4 (stop)
        assertEquals(4, RadialMenu.sliceAt(-52, 30, 18));
        // 10 o'clock -> 5 (pov)
        assertEquals(5, RadialMenu.sliceAt(-52, -30, 18));
    }

    @Test
    public void commandResolutionHandlesBlockEntityAndSwarmTarget() {
        RadialMenu.Crosshair ore = new RadialMenu.Crosshair(true, 12, -54, 88, "minecraft:deepslate_diamond_ore", "");
        assertEquals("goto 12 -54 88", RadialMenu.commandFor(0, "self", ore));
        assertEquals("mine deepslate_diamond_ore", RadialMenu.commandFor(1, "self", ore));
        assertEquals("swarm send Azalea goto 12 -54 88", RadialMenu.commandFor(0, "Azalea", ore));
        assertEquals("swarm send Azalea mine deepslate_diamond_ore", RadialMenu.commandFor(1, "Azalea", ore));

        RadialMenu.Crosshair player = new RadialMenu.Crosshair(true, 0, 64, 0, "", "Spelunker");
        assertEquals("follow player Spelunker", RadialMenu.commandFor(2, "self", player));
        assertEquals("follow players", RadialMenu.commandFor(2, "self", RadialMenu.Crosshair.NONE));

        assertEquals("blueprint live base", RadialMenu.commandFor(3, "self", RadialMenu.Crosshair.NONE));
        assertEquals("stop", RadialMenu.commandFor(4, "self", RadialMenu.Crosshair.NONE));
        assertEquals("swarm send Miner stop", RadialMenu.commandFor(4, "Miner", RadialMenu.Crosshair.NONE));
        assertEquals("swarm send Miner pov show", RadialMenu.commandFor(5, "Miner", RadialMenu.Crosshair.NONE));
        assertEquals("", RadialMenu.commandFor(-1, "self", ore));
    }
}
