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

package baritone.swarm.rts;

import baritone.swarm.rts.BlueprintRepairSession.CellObservation;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Ostinato: only what the repair itself could have placed, outside the saved region, is scaffolding. */
public class ScaffoldTrackerTest {

    private static final int[] REGION = {0, 64, 0, 3, 64, 0};

    private final Map<Long, CellObservation> world = new HashMap<>();

    private CellObservation read(int x, int y, int z) {
        return world.getOrDefault(Blueprint.cellKey(x, y, z), CellObservation.air());
    }

    private void set(int x, int y, int z, String block) {
        world.put(Blueprint.cellKey(x, y, z), CellObservation.of(block, Map.of()));
    }

    @Test
    public void findsOnlyNewThrowawayBlocksOutsideTheRegion() {
        set(0, 64, 0, "stone_bricks");          // the wall itself: inside the region, never scaffolding
        set(5, 63, 0, "dirt");                  // existing terrain: was not air
        ScaffoldTracker t = new ScaffoldTracker(REGION, this::read);

        set(1, 64, -1, "dirt");                 // pillar block in front of the wall
        set(1, 65, -1, "cobblestone");
        set(2, 64, 0, "dirt");                  // inside the region: a repair target, not scaffolding
        set(9, 64, 9, "oak_log");               // something the builder never places
        set(2, 70, 2, "water");                 // fluid arrived by itself

        List<int[]> found = t.placedSince(this::read, Set.of("dirt", "cobblestone", "stone_bricks")::contains);
        assertEquals(2, found.size());
        for (int[] c : found) {
            assertEquals(-1, c[2]);
            assertTrue(c[0] == 1 && (c[1] == 64 || c[1] == 65));
        }
    }

    @Test
    public void cellsRemovedAgainAreNotReported() {
        ScaffoldTracker t = new ScaffoldTracker(REGION, this::read);
        set(1, 64, -1, "dirt");
        assertEquals(1, t.placedSince(this::read, "dirt"::equals).size());
        world.remove(Blueprint.cellKey(1, 64, -1));
        assertTrue(t.placedSince(this::read, "dirt"::equals).isEmpty());
    }

    @Test
    public void unloadedCellsAreNeverTreatedAsAir() {
        BlueprintRepairSession.WorldReader unloadedAway = (x, y, z) ->
                x < 0 ? CellObservation.unloaded() : read(x, y, z);
        ScaffoldTracker t = new ScaffoldTracker(REGION, unloadedAway);
        set(-2, 64, 0, "dirt"); // appears where the tracker never saw air
        assertTrue(t.placedSince(this::read, "dirt"::equals).isEmpty());
    }
}
