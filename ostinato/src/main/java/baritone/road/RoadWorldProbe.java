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

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Client-visible terrain probe for {@link RoadPlanner} and {@link RoadVerifier}.
 * Strictly reads ONLY from the client-visible {@link Level} (never peeks at an integrated server world).
 */
public final class RoadWorldProbe implements RoadProbe {

    private final Level world;

    public RoadWorldProbe(Level world) {
        this.world = world;
    }

    @Override
    public CellKind get(int x, int y, int z) {
        if (world == null || y < world.getMinY() || y > world.getMaxY()) {
            return CellKind.UNKNOWN;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.isLoaded(pos)) {
            return CellKind.UNKNOWN;
        }
        return classify(world, pos, world.getBlockState(pos));
    }

    public static CellKind classify(Level world, BlockPos pos, BlockState state) {
        if (state == null) {
            return CellKind.UNKNOWN;
        }
        Block block = state.getBlock();
        if (block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POWDER_SNOW
                || block == Blocks.WITHER_ROSE || block instanceof FireBlock || block instanceof CampfireBlock) {
            return CellKind.HAZARD;
        }
        if (!state.getFluidState().isEmpty()) {
            return CellKind.FLUID;
        }
        if (state.isAir()) {
            return CellKind.AIR;
        }
        if (state.getDestroySpeed(world, pos) < 0.0f) {
            return CellKind.UNBREAKABLE;
        }
        VoxelShape collision = state.getCollisionShape(world, pos);
        if (collision.isEmpty()) {
            return state.canBeReplaced() ? CellKind.PASSABLE_PLANT : CellKind.SOLID_NON_FULL_CLEARABLE;
        }
        if (Block.isShapeFullBlock(collision)) {
            return CellKind.SOLID_FULL;
        }
        return CellKind.SOLID_NON_FULL_CLEARABLE;
    }
}
