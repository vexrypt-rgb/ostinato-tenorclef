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

package baritone.road;

import baritone.api.schematic.AbstractSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact-cell schematic for a road construction stage ({@link BuilderAccessPlan.BuildOp}).
 * Refuses any cell inside a {@link ProtectedZone} as a defense-in-depth safety guard.
 */
public final class RoadSchematic extends AbstractSchematic {

    public final BlockPos origin;
    private final Map<Long, BlockState> desiredByRel;

    private RoadSchematic(BlockPos origin, int sx, int sy, int sz, Map<Long, BlockState> desiredByRel) {
        super(sx, sy, sz);
        this.origin = origin;
        this.desiredByRel = desiredByRel;
    }

    public static RoadSchematic ofStage(RoadSpec spec, List<BuilderAccessPlan.BuildOp> ops, BlockState deckBlock) {
        if (ops == null || ops.isEmpty()) {
            throw new IllegalArgumentException("Road stage ops must not be empty");
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BuilderAccessPlan.BuildOp op : ops) {
            if (spec.isProtected(op.x(), op.y(), op.z())) {
                throw new IllegalStateException("Refusing to include protected-zone cell ("
                        + op.x() + "," + op.y() + "," + op.z() + ") in RoadSchematic");
            }
            if (op.x() < x0) x0 = op.x();
            if (op.y() < y0) y0 = op.y();
            if (op.z() < z0) z0 = op.z();
            if (op.x() > x1) x1 = op.x();
            if (op.y() > y1) y1 = op.y();
            if (op.z() > z1) z1 = op.z();
        }
        BlockPos origin = new BlockPos(x0, y0, z0);
        Map<Long, BlockState> desired = new HashMap<>(ops.size() * 2);
        for (BuilderAccessPlan.BuildOp op : ops) {
            BlockState want = op.kind() == BuilderAccessPlan.OpKind.CLEAR
                    ? Blocks.AIR.defaultBlockState()
                    : deckBlock;
            desired.put(ProtectedZone.key(op.x() - x0, op.y() - y0, op.z() - z0), want);
        }
        return new RoadSchematic(origin, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1, desired);
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        return desiredByRel.containsKey(ProtectedZone.key(x, y, z));
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        BlockState state = desiredByRel.get(ProtectedZone.key(x, y, z));
        return state != null ? state : current;
    }
}
