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

package baritone.swarm.world;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c part 2. The observation stores at their caps. A full dump is a few small SWO bodies and applies
 * in well under a quarter second, and the steady entity feed is 15 local messages a minute. Disk persistence is not
 * added: a restart already rebuilds the store from the bots, and these numbers do not call for a second copy.
 */
public class StoreMeasureTest {

    /** Coordinator chat budget from the part 2 gate: under 25 lines a minute with containers and entities on. */
    private static final int LINE_BUDGET = 25;

    @Test
    public void aFullStoreRebuildsInMemory() {
        AtomicLong mono = new AtomicLong(1_000_000L);
        List<String> log = new ArrayList<>();
        WorldKnowledge store = new WorldKnowledge(mono::get, log::add);
        assertTrue(store.setWorld("w1"));
        store.startRebuild(Collections.singletonList("Amy"));

        List<ObservationMessage.Entry> entries = new ArrayList<>();
        for (int i = 0; i < WorldKnowledge.TERRAIN_CAP; i++) {
            entries.add(new ObservationMessage.Entry("b:o:" + i + ",0,0", "stone", 0, 90));
        }
        for (int i = 0; i < WorldKnowledge.ENTITY_CAP; i++) {
            entries.add(new ObservationMessage.Entry("e:o:cow-" + i, "cow@0,64,0", 0, 90));
        }
        List<ObservationMessage> parts = ObservationMessage.pack(ObservationMessage.Op.R, "w1", entries,
                ObservationMessage.MAX_BODY);
        int chars = 0;
        for (ObservationMessage m : parts) {
            chars += m.encode().length();
        }
        long start = System.nanoTime();
        for (ObservationMessage m : parts) {
            store.apply("Amy", m);
        }
        store.apply("Amy", new ObservationMessage(ObservationMessage.Op.E, "w1", new ArrayList<>()));
        long ms = (System.nanoTime() - start) / 1_000_000L;

        assertFalse(store.rebuilding());
        assertEquals(WorldKnowledge.TERRAIN_CAP + WorldKnowledge.ENTITY_CAP, store.feedSize());
        assertEquals(0, store.evicted());
        assertTrue("full dump is " + chars + " chars", chars < 256 * 1024);
        assertTrue("rebuild applied in " + ms + " ms", ms < 250);
        assertTrue("dump is " + parts.size() + " messages", parts.size() < LINE_BUDGET);
    }

    @Test
    public void entityFeedStaysInsideTheLineBudgetAndTheTtl() {
        long perMinute = 60_000L / AutoReport.ENTITY_RESEND_MS;
        assertTrue(perMinute <= LINE_BUDGET);
        assertTrue(AutoReport.ENTITY_RESEND_MS < WorldKnowledge.ENTITY_TTL_MS);
        assertTrue(AutoReport.STRUCTURE_MAX < WorldKnowledge.TERRAIN_CAP);
        assertTrue(AutoReport.ENTITY_MAX < WorldKnowledge.ENTITY_CAP);
    }
}
