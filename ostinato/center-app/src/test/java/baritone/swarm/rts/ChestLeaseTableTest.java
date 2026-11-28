package baritone.swarm.rts;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChestLeaseTableTest {

    @Test
    public void canonicalKeyMatchesRoleOrder() {
        assertEquals("c:o:10,64,-5", ChestLeaseTable.chestKey('o', 10, 64, -5));
        assertEquals("c:n:0,70,0", ChestLeaseTable.chestKey('n', 0, 70, 0));
    }

    @Test
    public void mutualExclusionRenewalExpiryAndRelease() {
        ChestLeaseTable table = new ChestLeaseTable();
        String key = ChestLeaseTable.chestKey('o', 1, 64, 2);

        assertTrue(table.tryAcquire(key, "courier-1", 1_000L, 10_000L));
        assertTrue(table.holds(key, "courier-1", 1_000L));
        assertEquals("courier-1", table.holder(key, 5_000L));

        // Another owner is refused while lease is live
        assertFalse(table.tryAcquire(key, "courier-2", 5_000L, 10_000L));

        // Same owner can renew
        assertTrue(table.tryAcquire(key, "courier-1", 8_000L, 10_000L));
        assertFalse(table.tryAcquire(key, "courier-2", 12_000L, 10_000L));

        // Wrong owner cannot release
        assertFalse(table.release(key, "courier-2"));
        assertTrue(table.holds(key, "courier-1", 12_000L));

        // After expiry (18_000L), another owner can acquire
        assertNull(table.holder(key, 18_000L));
        assertTrue(table.tryAcquire(key, "courier-2", 18_000L, 5_000L));
        assertTrue(table.holds(key, "courier-2", 19_000L));

        // Explicit release frees immediately
        assertTrue(table.release(key, "courier-2"));
        assertNull(table.holder(key, 19_000L));
    }

    @Test
    public void releaseAllClearsOnlyMatchingOwner() {
        ChestLeaseTable table = new ChestLeaseTable();
        String k1 = ChestLeaseTable.chestKey('o', 1, 64, 1);
        String k2 = ChestLeaseTable.chestKey('o', 2, 64, 2);
        String k3 = ChestLeaseTable.chestKey('o', 3, 64, 3);

        assertTrue(table.tryAcquire(k1, "job-a", 1_000L, 20_000L));
        assertTrue(table.tryAcquire(k2, "job-a", 1_000L, 20_000L));
        assertTrue(table.tryAcquire(k3, "job-b", 1_000L, 20_000L));

        assertEquals(2, table.releaseAll("job-a"));
        assertEquals(1, table.activeCount(2_000L));
        assertNull(table.holder(k1, 2_000L));
        assertNull(table.holder(k2, 2_000L));
        assertEquals("job-b", table.holder(k3, 2_000L));
    }
}
