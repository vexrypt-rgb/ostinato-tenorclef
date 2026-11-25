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

import baritone.swarm.world.ObservationMessage;
import baritone.swarm.world.WorldKnowledge;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Ostinato: Phase 2c part 2. Offering a chest lease copies a stored contents claim onto the offer. A scan's block id
 * does not, and a vein or region key is not this check.
 */
public class ChestLeaseExpectTest {

    private static final String CHEST = "c:o:302,150,300";

    private final AtomicLong mono = new AtomicLong(1_000_000L);

    private WorldKnowledge store() {
        WorldKnowledge k = new WorldKnowledge(mono::get, s -> { }, WorldKnowledge.TERRAIN_CAP, WorldKnowledge.ENTITY_CAP);
        k.setWorld("w1");
        k.startRebuild(Collections.emptyList());
        return k;
    }

    private static void report(WorldKnowledge k, String key, String detail) {
        k.apply("Copper", new ObservationMessage(ObservationMessage.Op.R, "w1",
                List.of(new ObservationMessage.Entry(key, detail, 0, 90))));
    }

    @Test
    public void aStoredContentsClaimIsTheLeaseExpect() {
        WorldKnowledge k = store();
        report(k, CHEST, "diamond.4");
        assertEquals("diamond.4", ChestLeaseExpect.of(k, CHEST));
    }

    @Test
    public void aBlockIdClaimIsNotAContentsExpect() {
        WorldKnowledge k = store();
        report(k, CHEST, "chest");
        assertNull(ChestLeaseExpect.of(k, CHEST));
    }

    @Test
    public void aGoneRecordAndOtherKindsCarryNoContentsExpect() {
        WorldKnowledge k = store();
        report(k, CHEST, "diamond.4");
        k.apply("Copper", new ObservationMessage(ObservationMessage.Op.G, "w1",
                List.of(new ObservationMessage.Entry(CHEST, "-", 0, 100))));
        assertNull(ChestLeaseExpect.of(k, CHEST));
        report(k, "v:o:1,2,3:1,2,3", "diamond.4");
        assertNull(ChestLeaseExpect.of(k, "v:o:1,2,3:1,2,3"));
        assertNull(ChestLeaseExpect.of(k, "r:o:1,2,3:4,5,6"));
        assertNull(ChestLeaseExpect.of(null, CHEST));
    }
}
