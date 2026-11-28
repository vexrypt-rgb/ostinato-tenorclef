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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Ostinato: finds the blocks a repair left behind. The builder pillars and bridges with throwaway blocks to reach a
 * gap, and nothing else removes them. A tracker remembers which cells around the saved region were air when the repair
 * began (a bounded volume: the region plus {@link #MARGIN}, one bit a cell); afterwards every such cell that holds a
 * block outside the region, and that the repair could have placed, is scaffolding to take down again.
 * Pure Java: the world is read through {@link BlueprintRepairSession.WorldReader}.
 */
public final class ScaffoldTracker {

    /** How far around the saved region the repair's own placements are looked for. */
    public static final int MARGIN = 6;

    private final int[] region;
    private final int x0, y0, z0, sx, sy, sz;
    private final BitSet wasAir;

    public ScaffoldTracker(int[] region, BlueprintRepairSession.WorldReader world) {
        this.region = region.clone();
        this.x0 = region[0] - MARGIN;
        this.y0 = region[1] - MARGIN;
        this.z0 = region[2] - MARGIN;
        this.sx = region[3] - region[0] + 1 + 2 * MARGIN;
        this.sy = region[4] - region[1] + 1 + 2 * MARGIN;
        this.sz = region[5] - region[2] + 1 + 2 * MARGIN;
        this.wasAir = new BitSet(sx * sy * sz);
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    int wx = x0 + x, wy = y0 + y, wz = z0 + z;
                    if (inRegion(wx, wy, wz)) {
                        continue;
                    }
                    BlueprintRepairSession.CellObservation o = world.read(wx, wy, wz);
                    if (o != null && o.loaded() && o.isAir()) {
                        wasAir.set(index(x, y, z));
                    }
                }
            }
        }
    }

    private int index(int x, int y, int z) {
        return (x * sy + y) * sz + z;
    }

    private boolean inRegion(int x, int y, int z) {
        return x >= region[0] && x <= region[3] && y >= region[1] && y <= region[4] && z >= region[2] && z <= region[5];
    }

    /**
     * Cells outside the region that were air when the tracker started and now hold a block {@code placeable} accepts
     * (the throwaway blocks and the repair's own material). Water, lava and anything else that appeared are not
     * touched: only what the builder could have put there.
     */
    public List<int[]> placedSince(BlueprintRepairSession.WorldReader world, Predicate<String> placeable) {
        List<int[]> out = new ArrayList<>();
        for (int i = wasAir.nextSetBit(0); i >= 0; i = wasAir.nextSetBit(i + 1)) {
            int z = i % sz;
            int y = (i / sz) % sy;
            int x = i / (sz * sy);
            int wx = x0 + x, wy = y0 + y, wz = z0 + z;
            BlueprintRepairSession.CellObservation o = world.read(wx, wy, wz);
            if (o != null && o.loaded() && !o.isAir() && placeable.test(o.block())) {
                out.add(new int[]{wx, wy, wz});
            }
        }
        return out;
    }
}
