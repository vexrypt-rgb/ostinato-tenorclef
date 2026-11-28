package baritone.swarm.rts;

import baritone.swarm.center.app.AreaOrders;
import baritone.swarm.center.app.RtsOrders;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: RTS region and chest leases, and the split live blueprint. */
public class RtsLeaseTest {

    @Test public void regionRoundTripsAndGivesCanonicalKeys() {
        RoleOrder o = RoleOrder.parse("role miner ore=coal_ore chest=1,64,1 region=10,60,5:2,64,0");
        assertArrayEquals(new int[]{2, 60, 0, 10, 64, 5}, o.region);
        assertEquals("r:o:2,60,0:10,64,5", o.regionKey('o'));
        assertEquals("c:o:1,64,1", o.chestKey('o'));
        assertEquals(o.line(), RoleOrder.parse(o.line()).line());
        assertTrue(o.inRegion(2, 60, 0));
        assertFalse(o.inRegion(11, 60, 0));
    }

    @Test public void regionIsRefusedWhereItMeansNothing() {
        assertThrows(IllegalArgumentException.class, () -> RoleOrder.parse("role storage chests=1,2,3 region=0,0,0:1,1,1"));
        assertThrows(IllegalArgumentException.class, () -> RoleOrder.parse("role miner ore=coal_ore region=0,0,0:64,1,1"));
        assertThrows(IllegalArgumentException.class, () -> RoleOrder.parse("role miner ore=coal_ore region=0,0,0"));
    }

    @Test public void schedulerGivesEachChestToTheLongestWaiterOnce() {
        ChestScheduler s = new ChestScheduler();
        assertEquals("c:o:1,2,3", ChestScheduler.waitingFor("miner deposit (batch of 6) wait-chest=c:o:1,2,3 leased deposits=0"));
        assertNull(ChestScheduler.waitingFor("miner work deposits=0"));
        List<ChestScheduler.Want> one = List.of(new ChestScheduler.Want("Azalea", "c:o:1,2,3"));
        assertEquals(List.of(new ChestScheduler.Grant("Azalea", "c:o:1,2,3")), s.next(one, k -> false, 1_000));
        List<ChestScheduler.Want> both = List.of(new ChestScheduler.Want("Spelunker", "c:o:1,2,3"),
                new ChestScheduler.Want("Azalea", "c:o:1,2,3"));
        // the offer is in flight: nothing more for that chest, whoever else waits
        assertTrue(s.next(both, k -> false, 2_000).isEmpty());
        // the lease is live: still nothing
        assertTrue(s.next(both, k -> true, 5_000).isEmpty());
        // Azalea is done and gone; the chest is free again: Spelunker next
        List<ChestScheduler.Want> spel = List.of(new ChestScheduler.Want("Spelunker", "c:o:1,2,3"));
        assertEquals(List.of(new ChestScheduler.Grant("Spelunker", "c:o:1,2,3")), s.next(spel, k -> false, 9_000));
    }

    @Test public void schedulerKeepsChestsApart() {
        ChestScheduler s = new ChestScheduler();
        List<ChestScheduler.Grant> g = s.next(List.of(new ChestScheduler.Want("A", "c:o:0,0,0"),
                new ChestScheduler.Want("B", "c:o:5,0,0")), k -> false, 0);
        assertEquals(2, g.size());
    }

    @Test public void inAreaSplitsOneRegionLeasePerBot() {
        RoleOrder o = RoleOrder.parse("role miner ore=coal_ore chest=-33,-60,32 batch=6");
        RtsOrders.Plan p = RtsOrders.leasedRoles(List.of("Azalea", "Spelunker"), 'o',
                new AreaOrders.Box(-41, -61, 29, -34, -61, 31), o);
        assertNull(p.reason());
        assertEquals(2, p.orders().size());
        assertEquals("swarm lease Azalea r:o:-41,-61,29:-38,-61,31 role miner chest=-33,-60,32 ore=coal_ore batch=6"
                + " region=-41,-61,29:-38,-61,31", p.orders().get(0).line());
        assertTrue(p.orders().get(1).line().startsWith("swarm lease Spelunker r:o:-37,-61,29:-34,-61,31 "));
        assertNotNull(RtsOrders.leasedRoles(List.of("A"), 'o', new AreaOrders.Box(0, 0, 0, 1, 1, 1),
                RoleOrder.parse("role storage chests=1,2,3")).reason());
    }

    @Test public void splitBlueprintCoversEveryCellExactlyOnce() {
        Blueprint b = new Blueprint("base");
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 2; z++) {
                b.paint(x, 64, z, "cobblestone");
                b.paint(x, 65, z, "cobblestone");
            }
        }
        RtsOrders.Plan p = RtsOrders.splitBlueprint(List.of("Azalea", "Spelunker"), 'o', b);
        assertNull(p.reason());
        assertEquals("swarm lease Azalea r:o:0,64,0:2,65,1 blueprint live base part=0,64,0:2,65,1", p.orders().get(0).line());
        int total = 0;
        for (RtsOrders.Order o : p.orders()) {
            int[] part = RoleOrder.box(o.line().substring(o.line().indexOf("part=") + 5));
            total += b.part(part).size();
        }
        assertEquals(b.size(), total);
        assertNotNull(RtsOrders.splitBlueprint(List.of("A", "B"), 'o', new Blueprint("e")).reason());
    }

    @Test public void partStatusFilesBelongToTheirBlueprint() {
        assertEquals("base@Azalea.status", Blueprint.partStatusName("base", "Azalea"));
        assertTrue(Blueprint.isStatusOf("base", "base@Azalea.status"));
        assertTrue(Blueprint.isStatusOf("base", "base.status"));
        assertFalse(Blueprint.isStatusOf("base", "base2@Azalea.status"));
    }
}
