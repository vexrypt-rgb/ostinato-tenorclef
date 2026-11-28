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
import java.util.Map;

import static org.junit.Assert.*;

/** Ostinato: a builder that never finishes must not keep a repair open forever. */
public class BlueprintRepairStallTest {

    private static Blueprint two() {
        Blueprint bp = new Blueprint("stall");
        bp.paint(0, 64, 0, "stone_bricks");
        bp.paint(1, 64, 0, "stone_bricks");
        return bp;
    }

    @Test
    public void stalledRepairFailsWhatIsLeftAndStopsTheBuilder() {
        Map<Long, CellObservation> world = new HashMap<>();
        BlueprintRepairSession.WorldReader reader = (x, y, z) ->
                world.getOrDefault(Blueprint.cellKey(x, y, z), CellObservation.air());
        BlueprintRepairSession s = new BlueprintRepairSession(two(), null, false, reader);
        assertTrue(s.isActive());
        assertEquals(0, s.progress());

        BlueprintRepairSession.Action a = s.failStalled("no route", true);
        assertEquals(BlueprintRepairSession.ActionKind.STOP_BUILDER, a.kind());
        assertFalse(s.isActive());
        assertEquals(BlueprintRepairSession.SessionState.FAILED, s.state());
        assertEquals(2, s.failedKeys().size());
        assertTrue(s.failedDiscrepancies().get(0).reason().contains("no route"));
        assertEquals(Blueprint.DiscrepancyKind.REPAIR_FAILED, s.failedDiscrepancies().get(0).kind());
    }

    @Test
    public void stalledAfterPartialProgressIsPartialNotFailed() {
        Map<Long, CellObservation> world = new HashMap<>();
        BlueprintRepairSession.WorldReader reader = (x, y, z) ->
                world.getOrDefault(Blueprint.cellKey(x, y, z), CellObservation.air());
        BlueprintRepairSession s = new BlueprintRepairSession(two(), null, false, reader, null, 1, 3);
        world.put(Blueprint.cellKey(0, 64, 0), CellObservation.of("stone_bricks", Map.of()));
        for (int i = 0; i < 4; i++) {
            s.tick(reader, true, false);
        }
        assertEquals(1, s.progress());
        s.failStalled("no route", false);
        assertEquals(BlueprintRepairSession.SessionState.PARTIAL, s.state());
        assertEquals(1, s.confirmedKeys().size());
        assertEquals(1, s.failedKeys().size());
    }

    @Test
    public void stallOnAFinishedSessionChangesNothing() {
        BlueprintRepairSession s = new BlueprintRepairSession(two(), null, false, (x, y, z) -> CellObservation.air());
        s.cancel();
        assertEquals(BlueprintRepairSession.ActionKind.NONE, s.failStalled("late", true).kind());
        assertEquals(BlueprintRepairSession.SessionState.CANCELLED, s.state());
    }
}
