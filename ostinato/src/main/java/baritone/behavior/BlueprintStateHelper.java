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

package baritone.behavior;

import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.MovementHelper;
import baritone.swarm.rts.BlockStateSpec;
import baritone.swarm.rts.Blueprint;
import baritone.swarm.rts.BlueprintRepairSession;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ostinato: Bridges pure {@link Blueprint} / {@link BlockStateSpec} definitions with Minecraft 26.3
 * {@link BlockState}s, ordinary placement face prediction, and feasible stand-position selection.
 */
public final class BlueprintStateHelper {

    private BlueprintStateHelper() {}

    public static Block lookupBlock(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Optional<? extends Holder.Reference<Block>> h = BuiltInRegistries.BLOCK.get(Identifier.withDefaultNamespace(id));
        return h.map(Holder.Reference::value).orElse(null);
    }

    /** Client-visible world observation at {@code pos}; returns unloaded when chunk is not present. */
    public static BlueprintRepairSession.CellObservation observe(Level world, BlockPos pos) {
        // 26.3: Level.hasChunk is true for every chunk on a client, so ask the chunk source (a missing chunk reads as air)
        if (world == null || pos == null || !world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return BlueprintRepairSession.CellObservation.unloaded();
        }
        return observeState(world.getBlockState(pos));
    }

    public static BlueprintRepairSession.CellObservation observeState(BlockState state) {
        if (state == null || state.isAir()) {
            return BlueprintRepairSession.CellObservation.air();
        }
        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String id = "minecraft".equals(key.getNamespace()) ? key.getPath() : key.getPath();
        Map<String, String> props = new LinkedHashMap<>();
        for (Property<?> p : state.getProperties()) {
            props.put(p.getName(), valueName(state, p));
        }
        return BlueprintRepairSession.CellObservation.of(id, props);
    }

    /**
     * Validates a blueprint cell against Minecraft's live block-state registry.
     * Returns null if supported, or a human-readable unsupported reason if not.
     */
    public static String validateAgainstRegistry(Blueprint.Cell cell) {
        if (cell == null) {
            return "null cell";
        }
        Block block = lookupBlock(cell.block());
        if (block == null) {
            return "unknown block " + cell.block();
        }
        if (!cell.hasExplicitProperties()) {
            return null; // legacy name-only always supported
        }
        List<String> pureReasons = BlockStateSpec.unsupportedReasons(cell.block(), cell.properties());
        if (!pureReasons.isEmpty()) {
            return String.join("; ", pureReasons);
        }
        BlockState def = block.defaultBlockState();
        boolean isPillar = block instanceof RotatedPillarBlock || def.hasProperty(RotatedPillarBlock.AXIS);
        boolean isSlab = block instanceof SlabBlock;
        boolean isStair = block instanceof StairBlock;
        if (!isPillar && !isSlab && !isStair) {
            return "unsupported explicit state block " + cell.block();
        }
        for (Map.Entry<String, String> e : cell.properties().entrySet()) {
            Property<?> prop = block.getStateDefinition().getProperty(e.getKey());
            if (prop == null) {
                return "block " + cell.block() + " has no property " + e.getKey();
            }
            if (prop.getValue(e.getValue()).isEmpty()) {
                return "invalid value " + e.getKey() + "=" + e.getValue() + " for " + cell.block();
            }
        }
        return null;
    }

    /** Applies explicit properties from {@code properties} onto {@code base}. */
    public static BlockState applyProperties(BlockState base, Map<String, String> properties) {
        if (base == null || properties == null || properties.isEmpty()) {
            return base;
        }
        BlockState state = base;
        Block block = base.getBlock();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            Property<?> prop = block.getStateDefinition().getProperty(e.getKey());
            if (prop != null) {
                state = setPropertyValue(state, prop, e.getValue());
            }
        }
        return state;
    }

    /**
     * Builds a state-aware {@link ISchematic} for {@code bp}, optionally scoped to {@code scopeKeys}.
     * Unsupported cells are excluded so the builder never places or falsely completes them.
     * Legacy name-only cells preserve any existing orientation of the same block type.
     */
    public static ISchematic toSchematic(Blueprint bp, Set<Long> scopeKeys) {
        int[] o = bp.origin();
        if (o == null || bp.size() == 0) {
            return new BlueprintSchematic(0, 0, 0, new Blueprint.Cell[0][0][0], new Block[0][0][0]);
        }
        int sx = 1, sy = 1, sz = 1;
        for (Blueprint.Cell c : bp.cells()) {
            sx = Math.max(sx, c.x() - o[0] + 1);
            sy = Math.max(sy, c.y() - o[1] + 1);
            sz = Math.max(sz, c.z() - o[2] + 1);
        }
        Blueprint.Cell[][][] cells = new Blueprint.Cell[sx][sz][sy];
        Block[][][] blocks = new Block[sx][sz][sy];
        for (Blueprint.Cell c : bp.cells()) {
            long k = Blueprint.cellKey(c.x(), c.y(), c.z());
            if (scopeKeys != null && !scopeKeys.contains(k)) {
                continue;
            }
            if (validateAgainstRegistry(c) != null) {
                continue; // never build unsupported explicit state cells
            }
            Block block = lookupBlock(c.block());
            if (block == null) {
                continue;
            }
            int lx = c.x() - o[0];
            int ly = c.y() - o[1];
            int lz = c.z() - o[2];
            cells[lx][lz][ly] = c;
            blocks[lx][lz][ly] = block;
        }
        return new BlueprintSchematic(sx, sy, sz, cells, blocks);
    }

    /**
     * True when holding {@code itemBlock} in inventory can be placed via ordinary clicks as {@code desired}.
     * Fixes inventory availability checks for logs, slabs, and stairs whose default placement state differs
     * from the desired axis/type/facing/half.
     */
    public static boolean canPlaceBlockItemAs(Block itemBlock, BlockState desired) {
        if (itemBlock == null || desired == null || itemBlock != desired.getBlock()) {
            return false;
        }
        if (itemBlock instanceof RotatedPillarBlock || desired.hasProperty(RotatedPillarBlock.AXIS)) {
            return true;
        }
        if (itemBlock instanceof SlabBlock) {
            return desired.hasProperty(SlabBlock.TYPE) && desired.getValue(SlabBlock.TYPE) != SlabType.DOUBLE
                    && (!desired.hasProperty(SlabBlock.WATERLOGGED) || !desired.getValue(SlabBlock.WATERLOGGED));
        }
        if (itemBlock instanceof StairBlock) {
            return !desired.hasProperty(StairBlock.WATERLOGGED) || !desired.getValue(StairBlock.WATERLOGGED);
        }
        return false;
    }

    /**
     * Predicts the exact vanilla {@link BlockState} produced by ordinary placement of {@code block} when right-clicking
     * {@code clickedFace} at vertical hit fraction {@code hitFracY} while looking at {@code playerYaw}, without
     * mutating the player entity. Returns null for blocks outside this supported orientation set.
     */
    public static BlockState predictPlacementState(Block block, Direction clickedFace, double hitFracY, float playerYaw) {
        if (block == null || clickedFace == null) {
            return null;
        }
        BlockState def = block.defaultBlockState();
        if (block instanceof RotatedPillarBlock || def.hasProperty(RotatedPillarBlock.AXIS)) {
            return def.setValue(RotatedPillarBlock.AXIS, clickedFace.getAxis());
        }
        if (block instanceof SlabBlock) {
            SlabType type = (clickedFace == Direction.DOWN || (clickedFace != Direction.UP && hitFracY > 0.5))
                    ? SlabType.TOP
                    : SlabType.BOTTOM;
            BlockState s = def.setValue(SlabBlock.TYPE, type);
            if (s.hasProperty(SlabBlock.WATERLOGGED)) {
                s = s.setValue(SlabBlock.WATERLOGGED, false);
            }
            return s;
        }
        if (block instanceof StairBlock) {
            Direction facing = horizontalFacingFromYaw(playerYaw);
            Half half = (clickedFace == Direction.DOWN || (clickedFace != Direction.UP && hitFracY > 0.5))
                    ? Half.TOP
                    : Half.BOTTOM;
            BlockState s = def.setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
            if (s.hasProperty(StairBlock.WATERLOGGED)) {
                s = s.setValue(StairBlock.WATERLOGGED, false);
            }
            return s;
        }
        return null;
    }

    /** Exact vanilla horizontal facing derived from a player yaw (in degrees). */
    public static Direction horizontalFacingFromYaw(float yaw) {
        return Direction.fromYRot(yaw);
    }

    /**
     * Pure yaw-margin check (degrees away from the 45-degree diagonal boundary) so stair placements pick stands
     * that will not flip facing under tiny aim jitter.
     */
    public static double facingYawMarginDegrees(float yaw, Direction targetFacing) {
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) {
            return 0.0;
        }
        float centerYaw = targetFacing.toYRot();
        double diff = Math.abs(Mth.wrapDegrees(yaw - centerYaw));
        return 45.0 - diff;
    }

    /** True when {@code desired} needs orientation-aware face/stand selection beyond generic adjacent placement. */
    public static boolean needsOrientedStand(BlockState desired) {
        if (desired == null) {
            return false;
        }
        Block b = desired.getBlock();
        if (b instanceof StairBlock) {
            return true;
        }
        if (b instanceof SlabBlock && desired.hasProperty(SlabBlock.TYPE)
                && desired.getValue(SlabBlock.TYPE) == SlabType.TOP) {
            return true;
        }
        if ((b instanceof RotatedPillarBlock || desired.hasProperty(RotatedPillarBlock.AXIS))
                && desired.hasProperty(RotatedPillarBlock.AXIS)
                && desired.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) {
            return true;
        }
        return false;
    }

    /**
     * Computes feasible walkable stand positions around {@code targetPos} from which an ordinary click on an existing
     * support face will produce {@code desired}'s required orientation (log axis, slab top/bottom, stair facing+half).
     */
    public static List<BlockPos> feasibleOrientationStands(IPlayerContext ctx, BlockStateInterface bsi,
                                                           BlockPos targetPos, BlockState desired) {
        if (ctx == null || bsi == null || targetPos == null || !needsOrientedStand(desired)) {
            return List.of();
        }
        Block block = desired.getBlock();
        double reach = ctx.playerController().getBlockReachDistance();
        double maxDistSqr = (reach - 0.15) * (reach - 0.15);
        double eyeH = ctx.entity().getEyeHeight(Pose.CROUCHING);

        // Collect all currently solid support faces and candidate click points that can produce `desired`.
        record ClickCandidate(BlockPos support, Direction clickedFace, Vec3 point, double hitFracY) {}
        List<ClickCandidate> candidates = new ArrayList<>();
        for (Direction against : Direction.values()) {
            BlockPos supportPos = targetPos.relative(against);
            BlockState supportState = bsi.get0(supportPos);
            if (MovementHelper.isReplaceable(supportPos.getX(), supportPos.getY(), supportPos.getZ(), supportState, bsi)) {
                continue;
            }
            VoxelShape shape = supportState.getShape(ctx.world(), supportPos);
            if (shape.isEmpty()) {
                continue;
            }
            Direction clickedFace = against.getOpposite();
            if (!faceCanProduce(block, desired, clickedFace)) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (double[] mult : clickMultipliersFor(against)) {
                double px = supportPos.getX() + aabb.minX * mult[0] + aabb.maxX * (1.0 - mult[0]);
                double py = supportPos.getY() + aabb.minY * mult[1] + aabb.maxY * (1.0 - mult[1]);
                double pz = supportPos.getZ() + aabb.minZ * mult[2] + aabb.maxZ * (1.0 - mult[2]);
                double hitFracY = py - targetPos.getY();
                if (!faceAndHitCanProduce(block, desired, clickedFace, hitFracY)) {
                    continue;
                }
                candidates.add(new ClickCandidate(supportPos, clickedFace, new Vec3(px, py, pz), hitFracY));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<BlockPos> clearStands = new ArrayList<>();
        List<BlockPos> adjacentStands = new ArrayList<>();
        Direction wantFacing = (block instanceof StairBlock && desired.hasProperty(StairBlock.FACING))
                ? desired.getValue(StairBlock.FACING) : null;
        BlockPos currentFeet = ctx.playerFeet();
        boolean bodyIntersectsTarget = !desired.getCollisionShape(ctx.world(), targetPos).isEmpty()
                && !ctx.world().isUnobstructed(null, desired.getCollisionShape(ctx.world(), targetPos)
                .move(targetPos.getX(), targetPos.getY(), targetPos.getZ()));
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue; // never stand inside the target cell
                    }
                    int sx = targetPos.getX() + dx;
                    int sy = targetPos.getY() + dy;
                    int sz = targetPos.getZ() + dz;
                    if (sx == targetPos.getX() && sz == targetPos.getZ() && sy == targetPos.getY() - 1) {
                        continue; // head would intersect target
                    }
                    if (bodyIntersectsTarget && currentFeet != null
                            && sx == currentFeet.getX() && sy == currentFeet.getY() && sz == currentFeet.getZ()) {
                        continue; // current stand overlaps target hitbox: force stepping out
                    }
                    if (!MovementHelper.canWalkOn(bsi, sx, sy - 1, sz)) {
                        continue;
                    }
                    if (!MovementHelper.canWalkThrough(bsi, sx, sy, sz)
                            || !MovementHelper.canWalkThrough(bsi, sx, sy + 1, sz)) {
                        continue;
                    }
                    BlockPos floorPos = new BlockPos(sx, sy - 1, sz);
                    VoxelShape floorShape = bsi.get0(floorPos).getCollisionShape(ctx.world(), floorPos);
                    double floorTop = floorShape.isEmpty() ? sy : (sy - 1) + floorShape.max(Direction.Axis.Y);
                    Vec3 eye = new Vec3(sx + 0.5, floorTop + eyeH, sz + 0.5);
                    boolean ok = false;
                    for (ClickCandidate cand : candidates) {
                        if (eye.distanceToSqr(cand.point) > maxDistSqr) {
                            continue;
                        }
                        // Face must point toward the player's eye (front-facing).
                        double dot = (eye.x - cand.point.x) * cand.clickedFace.getStepX()
                                + (eye.y - cand.point.y) * cand.clickedFace.getStepY()
                                + (eye.z - cand.point.z) * cand.clickedFace.getStepZ();
                        if (dot <= 0.05) {
                            continue;
                        }
                        if (wantFacing != null) {
                            Rotation rot = RotationUtils.calcRotationFromVec3d(eye, cand.point, new Rotation(0, 0));
                            if (horizontalFacingFromYaw(rot.getYaw()) != wantFacing) {
                                continue;
                            }
                            if (facingYawMarginDegrees(rot.getYaw(), wantFacing) < 4.0) {
                                continue;
                            }
                        }
                        // Verify unobstructed vanilla raytrace from this stand's eye to the exact support face!
                        Vec3 rayDir = cand.point.subtract(eye);
                        double len = rayDir.length();
                        if (len < 1e-4) {
                            continue;
                        }
                        Vec3 rayEnd = eye.add(rayDir.scale(Math.min(reach, len + 0.25) / len));
                        BlockHitResult hit = ctx.world().clip(new ClipContext(
                                eye, rayEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.entity()));
                        if (hit.getType() != HitResult.Type.BLOCK
                                || !hit.getBlockPos().equals(cand.support)
                                || hit.getDirection() != cand.clickedFace) {
                            continue;
                        }
                        // For slabs/stairs, confirm the actual clipped hit Y stays on the required half.
                        double clippedFracY = hit.getLocation().y - targetPos.getY();
                        if (!faceAndHitCanProduce(block, desired, cand.clickedFace, clippedFracY)) {
                            continue;
                        }
                        ok = true;
                        break;
                    }
                    if (ok) {
                        BlockPos pos = new BlockPos(sx, sy, sz);
                        // Prefer stands >=2 horizontal blocks away (or different Y) so the player hitbox
                        // never clips into the target cell edge upon arrival.
                        if (Math.abs(dx) >= 2 || Math.abs(dz) >= 2 || dy != 0) {
                            clearStands.add(pos);
                        } else {
                            adjacentStands.add(pos);
                        }
                    }
                }
            }
        }
        return clearStands.isEmpty() ? adjacentStands : clearStands;
    }

    private static boolean faceCanProduce(Block block, BlockState desired, Direction clickedFace) {
        if (block instanceof RotatedPillarBlock || desired.hasProperty(RotatedPillarBlock.AXIS)) {
            return clickedFace.getAxis() == desired.getValue(RotatedPillarBlock.AXIS);
        }
        if (block instanceof SlabBlock && desired.hasProperty(SlabBlock.TYPE)) {
            SlabType want = desired.getValue(SlabBlock.TYPE);
            if (want == SlabType.TOP) {
                return clickedFace != Direction.UP;
            }
            if (want == SlabType.BOTTOM) {
                return clickedFace != Direction.DOWN;
            }
            return false;
        }
        if (block instanceof StairBlock && desired.hasProperty(StairBlock.HALF)) {
            Half want = desired.getValue(StairBlock.HALF);
            if (want == Half.TOP) {
                return clickedFace != Direction.UP;
            }
            return clickedFace != Direction.DOWN;
        }
        return true;
    }

    private static boolean faceAndHitCanProduce(Block block, BlockState desired, Direction clickedFace, double hitFracY) {
        if (block instanceof SlabBlock && desired.hasProperty(SlabBlock.TYPE)) {
            SlabType want = desired.getValue(SlabBlock.TYPE);
            SlabType got = (clickedFace == Direction.DOWN || (clickedFace != Direction.UP && hitFracY > 0.5))
                    ? SlabType.TOP : SlabType.BOTTOM;
            return got == want;
        }
        if (block instanceof StairBlock && desired.hasProperty(StairBlock.HALF)) {
            Half want = desired.getValue(StairBlock.HALF);
            Half got = (clickedFace == Direction.DOWN || (clickedFace != Direction.UP && hitFracY > 0.5))
                    ? Half.TOP : Half.BOTTOM;
            return got == want;
        }
        return faceCanProduce(block, desired, clickedFace);
    }

    private static double[][] clickMultipliersFor(Direction against) {
        return switch (against) {
            case UP -> new double[][]{{0.5, 1, 0.5}, {0.1, 1, 0.5}, {0.9, 1, 0.5}, {0.5, 1, 0.1}, {0.5, 1, 0.9}};
            case DOWN -> new double[][]{{0.5, 0, 0.5}, {0.1, 0, 0.5}, {0.9, 0, 0.5}, {0.5, 0, 0.1}, {0.5, 0, 0.9}};
            case NORTH, SOUTH, EAST, WEST -> {
                double x = against.getStepX() == 0 ? 0.5 : (1 + against.getStepX()) / 2.0;
                double z = against.getStepZ() == 0 ? 0.5 : (1 + against.getStepZ()) / 2.0;
                yield new double[][]{{x, 0.25, z}, {x, 0.75, z}};
            }
        };
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> prop, String val) {
        Optional<T> parsed = prop.getValue(val);
        return parsed.map(t -> state.setValue(prop, t)).orElse(state);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }

    /** State-aware schematic backed by {@link Blueprint.Cell} entries (air omitted, legacy name-only preserved). */
    /**
     * Ostinato: a schematic that wants exactly {@code cells} to be air and has no opinion about anything else, so
     * the builder takes down scaffolding without touching its neighbours. Lowest corner in {@code originOut}.
     */
    public static ISchematic clearSchematic(List<int[]> cells, int[] originOut) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int[] c : cells) {
            x0 = Math.min(x0, c[0]); y0 = Math.min(y0, c[1]); z0 = Math.min(z0, c[2]);
            x1 = Math.max(x1, c[0]); y1 = Math.max(y1, c[1]); z1 = Math.max(z1, c[2]);
        }
        originOut[0] = x0; originOut[1] = y0; originOut[2] = z0;
        boolean[][][] mark = new boolean[x1 - x0 + 1][z1 - z0 + 1][y1 - y0 + 1];
        for (int[] c : cells) {
            mark[c[0] - x0][c[2] - z0][c[1] - y0] = true;
        }
        return new ClearSchematic(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1, mark);
    }

    static final class ClearSchematic extends AbstractSchematic {
        private final boolean[][][] mark;

        ClearSchematic(int sx, int sy, int sz, boolean[][][] mark) {
            super(sx, sy, sz);
            this.mark = mark;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
            return super.inSchematic(x, y, z, currentState) && mark[x][z][y];
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
    }

    static final class BlueprintSchematic extends AbstractSchematic {
        private final Blueprint.Cell[][][] cells;
        private final Block[][][] blocks;

        BlueprintSchematic(int sx, int sy, int sz, Blueprint.Cell[][][] cells, Block[][][] blocks) {
            super(sx, sy, sz);
            this.cells = cells;
            this.blocks = blocks;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
            if (!super.inSchematic(x, y, z, currentState)) {
                return false;
            }
            return cells[x][z][y] != null && blocks[x][z][y] != null;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            Blueprint.Cell cell = cells[x][z][y];
            Block block = blocks[x][z][y];
            if (cell == null || block == null) {
                return current;
            }
            if (!cell.hasExplicitProperties()) {
                // Legacy name-only cell: any state of the right block type matches!
                if (current != null && current.getBlock() == block) {
                    return current;
                }
                return block.defaultBlockState();
            }
            BlockState base = (current != null && current.getBlock() == block) ? current : block.defaultBlockState();
            return applyProperties(base, cell.properties());
        }
    }
}
