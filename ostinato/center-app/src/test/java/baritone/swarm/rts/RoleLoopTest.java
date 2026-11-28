package baritone.swarm.rts;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class RoleLoopTest {

    private static RoleLoop.Inputs in(long now, int free, int output, boolean active) {
        return new RoleLoop.Inputs(now, free, output, active, false, false, false, null);
    }

    @Test
    public void parsesAndRoundTrips() {
        RoleOrder f = RoleOrder.parse("role farmer at=1,64,2 r=8 chest=3,64,4 batch=8");
        assertEquals(Role.FARMER, f.role);
        assertEquals(8, f.radius);
        assertEquals("role farmer at=1,64,2 r=8 chest=3,64,4 batch=8", f.line());
        assertEquals(f.line(), RoleOrder.parse(f.line()).line());
        RoleOrder m = RoleOrder.parse(List.of("miner", "ore=minecraft:iron_ore,coal_ore", "chest=0,60,0"));
        assertEquals(List.of("iron_ore", "coal_ore"), m.ores);
        assertEquals("role miner chest=0,60,0 ore=iron_ore,coal_ore", m.line());
        RoleOrder s = RoleOrder.parse("storage chests=1,2,3;4,5,6");
        assertEquals(2, s.chests.size());
        assertEquals("role storage chests=1,2,3;4,5,6", s.line());
        assertEquals("role armorer set=copper", RoleOrder.parse("armorer").line());
        assertEquals(Role.IDLE, RoleOrder.parse("role idle").role);
    }

    @Test
    public void refusesBadOrders() {
        for (String bad : new String[]{"", "role wizard", "miner", "farmer chest=1,2", "farmer r=0", "farmer ore=x",
                "storage", "armorer set=gold", "farmer foo=1", "idle chest=1,2,3", "farmer at=1,2,3 at=1,2,3",
                "builder"}) {
            try {
                RoleOrder.parse(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void workDepositWorkCycle() {
        RoleLoop l = new RoleLoop(RoleOrder.parse("farmer chest=0,0,0 batch=8"));
        RoleLoop.Step s = l.step(in(0, 30, 0, false));
        assertEquals(RoleLoop.Phase.WORK, s.phase());
        assertTrue(s.start());
        assertFalse(l.step(in(50, 30, 3, true)).start());
        s = l.step(in(100, 30, 8, true));
        assertEquals(RoleLoop.Phase.DEPOSIT, s.phase());
        assertTrue(s.start());
        assertFalse(l.step(new RoleLoop.Inputs(150, 30, 8, false, false, true, false, null)).start());
        s = l.step(new RoleLoop.Inputs(200, 31, 0, false, false, false, true, null));
        assertEquals(RoleLoop.Phase.WORK, s.phase());
        assertTrue(s.start());
        assertEquals(1, l.deposits());
    }

    @Test
    public void fullInventoryDepositsEarly() {
        RoleLoop l = new RoleLoop(RoleOrder.parse("miner ore=coal_ore chest=0,0,0"));
        l.step(in(0, 30, 0, false));
        assertEquals(RoleLoop.Phase.DEPOSIT, l.step(in(10, 2, 5, true)).phase());
    }

    @Test
    public void depositFailureBacksOffThenRetries() {
        RoleLoop l = new RoleLoop(RoleOrder.parse("miner ore=coal_ore chest=0,0,0 batch=4"));
        l.step(in(0, 30, 0, false));
        l.step(in(10, 30, 4, true));
        RoleLoop.Step s = l.step(new RoleLoop.Inputs(20, 30, 4, false, false, false, false, "chest did not open"));
        assertEquals(RoleLoop.Phase.WAIT, s.phase());
        assertTrue(s.note().contains("chest did not open"));
        assertEquals(RoleLoop.Phase.WAIT, l.step(in(20 + RoleLoop.BACKOFF_MS[0] - 1, 30, 4, false)).phase());
        s = l.step(in(20 + RoleLoop.BACKOFF_MS[0], 30, 4, false));
        assertEquals(RoleLoop.Phase.DEPOSIT, s.phase());
        assertTrue(s.start());
    }

    @Test
    public void workEndingEmptyWaitsInsteadOfSpinning() {
        RoleLoop l = new RoleLoop(RoleOrder.parse("miner ore=diamond_ore chest=0,0,0"));
        l.step(in(0, 30, 0, false));
        l.step(in(10, 30, 0, true));
        RoleLoop.Step s = l.step(in(20, 30, 0, false));
        assertEquals(RoleLoop.Phase.WAIT, s.phase());
        assertEquals(RoleLoop.Phase.WORK, l.step(in(20 + RoleLoop.EMPTY_WAIT_MS, 30, 0, false)).phase());
    }

    @Test
    public void minedBatchEndsWorkAndDeposits() {
        RoleLoop l = new RoleLoop(RoleOrder.parse("miner ore=coal_ore chest=0,0,0 batch=64"));
        l.step(in(0, 30, 0, false));
        l.step(in(10, 30, 5, true));
        assertEquals(RoleLoop.Phase.DEPOSIT, l.step(in(20, 30, 5, false)).phase());
    }

    @Test
    public void itemsSplitIntoOutputAndKept() {
        RoleOrder f = RoleOrder.parse("farmer");
        assertTrue(RoleItems.isOutput(Role.FARMER, "wheat"));
        assertTrue(RoleItems.isOutput(Role.FARMER, "wheat_seeds"));
        assertEquals(RoleItems.SEED_KEEP, RoleItems.keep(Role.FARMER, "wheat_seeds", f));
        assertFalse(RoleItems.isOutput(Role.FARMER, "copper_hoe"));
        assertFalse(RoleItems.isOutput(Role.MINER, "copper_pickaxe"));
        assertTrue(RoleItems.isOutput(Role.MINER, "raw_iron"));
        assertTrue(RoleItems.isOutput(Role.MINER, "cobblestone"));
        assertEquals(32, RoleItems.keep(Role.MINER, "cobblestone", RoleOrder.parse("miner ore=stone keep=32")));
        assertFalse(RoleItems.isOutput(Role.MINER, "bread"));
    }
}
