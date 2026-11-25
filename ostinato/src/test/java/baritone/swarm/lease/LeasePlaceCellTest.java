/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.lease;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c part 2. A place inside a REGION lease re-reads the landing cell right before the block goes down.
 * Air or replaceable: the place proceeds. Occupied: no place, one correction, the lease stays. Not loaded: no place,
 * no correction, the lease stays.
 */
public class LeasePlaceCellTest {

    private static final String REGION = "r:o:0,10,0:9,12,9";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LeaseSupport startedRegionLease() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        s.coord.lease("Bob", REGION, "tunnel 2 1 8");
        s.pump();
        assertEquals(1, s.bobExec.count("tunnel"));
        return s;
    }

    private static UUID lease(LeaseSupport s) {
        for (LeaseSupport.Msg m : s.sent) {
            LeaseMessage lm = m.lease();
            if (lm != null && lm.op == LeaseMessage.Op.OFFER && REGION.equals(lm.resource)) {
                return lm.leaseId;
            }
        }
        throw new AssertionError("no offer");
    }

    @Test
    public void anAirOrReplaceableCellMayBePlaced() throws Exception {
        LeaseSupport s = startedRegionLease();
        UUID id = lease(s);
        List<String> corrected = new ArrayList<>();
        s.guard.setPlaceCorrection((dim, x, y, z, block) -> corrected.add(block));
        assertNull(s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> ""));
        assertTrue(s.logged("place cell 5,11,5 clear"));
        assertNull("a second look is still allowed", s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> ""));
        assertEquals("logged once", 1, s.loggedCount("place cell 5,11,5 clear"));
        assertTrue(corrected.isEmpty());
        assertFalse(s.guard.placeSkipped('o', 5, 11, 5));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
    }

    @Test
    public void anOccupiedCellIsRefusedOnceAndTheLeaseStays() throws Exception {
        LeaseSupport s = startedRegionLease();
        UUID id = lease(s);
        List<String> corrected = new ArrayList<>();
        s.guard.setPlaceCorrection((dim, x, y, z, block) -> corrected.add(dim + ":" + x + "," + y + "," + z + "=" + block));
        String why = s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> "stone");
        assertEquals("place-cell: occupied by stone", why);
        assertEquals(List.of("o:5,11,5=stone"), corrected);
        assertTrue(s.guard.placeSkipped('o', 5, 11, 5));
        assertEquals("a repeat look does not correct again", "place-cell: occupied by stone",
                s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> "stone"));
        assertEquals(1, corrected.size());
        assertEquals("a different block corrects again", "place-cell: occupied by dirt",
                s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> "dirt"));
        assertEquals(List.of("o:5,11,5=stone", "o:5,11,5=dirt"), corrected);
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        assertFalse(s.logged("ending lease="));
    }

    @Test
    public void aCellThatBecomesClearMayBePlaced() throws Exception {
        LeaseSupport s = startedRegionLease();
        s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> "stone");
        assertTrue(s.guard.placeSkipped('o', 5, 11, 5));
        assertNull(s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> ""));
        assertFalse(s.guard.placeSkipped('o', 5, 11, 5));
    }

    @Test
    public void anUnloadedCellIsRefusedWithoutACorrectionOrEndingTheLease() throws Exception {
        LeaseSupport s = startedRegionLease();
        UUID id = lease(s);
        List<String> corrected = new ArrayList<>();
        s.guard.setPlaceCorrection((dim, x, y, z, block) -> corrected.add(block));
        String why = s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> null);
        assertEquals("place-cell: not loaded", why);
        assertTrue(corrected.isEmpty());
        assertFalse(s.guard.placeSkipped('o', 5, 11, 5));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        assertTrue(s.logged("place-cell: not loaded at 5,11,5"));
    }

    @Test
    public void aBrokenViewIsTreatedAsNotLoaded() throws Exception {
        LeaseSupport s = startedRegionLease();
        UUID id = lease(s);
        String why = s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> {
            throw new IllegalStateException("world");
        });
        assertEquals("place-cell: not loaded", why);
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
    }

    @Test
    public void outsideTheBoxAVeinAndAnEndedLeaseAreNotChecked() throws Exception {
        LeaseSupport s = startedRegionLease();
        UUID id = lease(s);
        List<String> corrected = new ArrayList<>();
        s.guard.setPlaceCorrection((dim, x, y, z, block) -> corrected.add(block));
        assertNull(s.guard.placeCell('o', 50, 11, 50, (x, y, z) -> "stone"));
        assertNull("other dimension", s.guard.placeCell('n', 5, 11, 5, (x, y, z) -> "stone"));
        assertTrue(corrected.isEmpty());
        assertFalse(s.logged("place-cell:"));

        LeaseSupport v = new LeaseSupport(tmp.newFolder().toPath());
        v.activate();
        v.coord.lease("Bob", "v:o:0,10,0:1,11,1", "mine iron_ore");
        v.pump();
        assertNull("a vein lease does not re-check a place", v.guard.placeCell('o', 0, 10, 0, (x, y, z) -> "stone"));

        s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> "stone");
        assertTrue(s.guard.placeSkipped('o', 5, 11, 5));
        s.advance(10 * 60_000L); // past the authorised time; the lease is no longer a cover
        assertNull(s.guard.placeCell('o', 5, 11, 5, (x, y, z) -> "stone"));
        assertFalse(s.guard.placeSkipped('o', 5, 11, 5));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
    }
}
