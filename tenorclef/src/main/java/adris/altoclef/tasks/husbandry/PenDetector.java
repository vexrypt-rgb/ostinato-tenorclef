package adris.altoclef.tasks.husbandry;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.WorldHelper;
//#if MC < 260000
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.WallBlock;
//#else
//$$ import net.minecraft.world.level.block.Block;
//$$ import net.minecraft.world.level.block.state.BlockState;
//$$ import net.minecraft.world.level.block.FenceBlock;
//$$ import net.minecraft.world.level.block.FenceGateBlock;
//$$ import net.minecraft.world.level.block.WallBlock;
//#endif
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Client-side fenced-pen detector using ordinary visible block state.
 * <p>
 * Performs a bounded horizontal flood-fill from a seed interior position to discover an enclosed
 * fenced/walled pen, recording its interior columns, perimeter fences/walls, and fence gates.
 */
public final class PenDetector {

    private static final int MAX_RADIUS = 16;
    private static final int MAX_INTERIOR_COLUMNS = 256;
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private PenDetector() {
    }

    public static boolean isFenceOrWall(BlockState state) {
        if (state == null) return false;
        Block b = state.getBlock();
        return b instanceof FenceBlock || b instanceof FenceGateBlock || b instanceof WallBlock;
    }

    public static boolean isFenceGate(BlockState state) {
        return state != null && state.getBlock() instanceof FenceGateBlock;
    }

    /**
     * Builds a {@link PenBounds} from an explicit box while scanning its 1-block shell for fences/gates
     * so perimeter protection still applies.
     */
    public static PenBounds fromExplicitBox(AltoClef mod,
                                            int minX, int minY, int minZ,
                                            int maxX, int maxY, int maxZ) {
        int x0 = Math.min(minX, maxX);
        int y0 = Math.min(minY, maxY);
        int z0 = Math.min(minZ, maxZ);
        int x1 = Math.max(minX, maxX);
        int y1 = Math.max(minY, maxY);
        int z1 = Math.max(minZ, maxZ);

        Set<Long> interior = new LinkedHashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                interior.add(PenBounds.packXZ(x, z));
            }
        }
        Set<PenBounds.BlockKey> perimeter = new LinkedHashSet<>();
        Set<PenBounds.BlockKey> gates = new LinkedHashSet<>();
        if (mod != null && mod.getWorld() != null) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int z = z0 - 1; z <= z1 + 1; z++) {
                    if (x >= x0 && x <= x1 && z >= z0 && z <= z1) continue;
                    for (int y = y0; y <= y1 + 1; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
                        BlockState st = mod.getWorld().getBlockState(pos);
                        if (isFenceGate(st)) {
                            PenBounds.BlockKey k = new PenBounds.BlockKey(x, y, z);
                            perimeter.add(k);
                            gates.add(k);
                        } else if (isFenceOrWall(st)) {
                            perimeter.add(new PenBounds.BlockKey(x, y, z));
                        }
                    }
                }
            }
        }
        return new PenBounds(x0, y0, z0, x1, y1, z1, interior, perimeter, gates);
    }

    /**
     * Attempts to detect an enclosed fenced pen starting from {@code seed}.
     * If {@code seed} is itself a fence/gate block, tries each cardinal neighbor as the interior seed.
     */
    public static Optional<PenBounds> detectAround(AltoClef mod, BlockPos seed) {
        if (mod == null || mod.getWorld() == null || seed == null) {
            return Optional.empty();
        }
        if (!mod.getChunkTracker().isChunkLoaded(seed)) {
            return Optional.empty();
        }
        BlockState seedState = mod.getWorld().getBlockState(seed);
        if (isFenceOrWall(seedState)) {
            for (int[] d : CARDINALS) {
                Optional<PenBounds> found = floodFill(mod, seed.add(d[0], 0, d[1]));
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        return floodFill(mod, seed);
    }

    private static Optional<PenBounds> floodFill(AltoClef mod, BlockPos start) {
        BlockPos walkableStart = findWalkableFloorY(mod, start.getX(), start.getY(), start.getZ());
        if (walkableStart == null) {
            return Optional.empty();
        }

        Map<Long, Integer> interiorY = new HashMap<>();
        Set<PenBounds.BlockKey> perimeter = new LinkedHashSet<>();
        Set<PenBounds.BlockKey> gates = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();

        long startKey = PenBounds.packXZ(walkableStart.getX(), walkableStart.getZ());
        interiorY.put(startKey, walkableStart.getY());
        queue.add(walkableStart);

        int minX = walkableStart.getX(), maxX = walkableStart.getX();
        int minY = walkableStart.getY(), maxY = walkableStart.getY();
        int minZ = walkableStart.getZ(), maxZ = walkableStart.getZ();

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (Math.abs(cur.getX() - walkableStart.getX()) > MAX_RADIUS
                    || Math.abs(cur.getZ() - walkableStart.getZ()) > MAX_RADIUS
                    || interiorY.size() > MAX_INTERIOR_COLUMNS) {
                // Not enclosed within pen radius.
                return Optional.empty();
            }

            for (int[] d : CARDINALS) {
                int nx = cur.getX() + d[0];
                int nz = cur.getZ() + d[1];
                long nKey = PenBounds.packXZ(nx, nz);
                if (interiorY.containsKey(nKey)) {
                    continue;
                }

                // Check for a fence/gate/wall boundary at cur.y, cur.y+1, or cur.y-1.
                BlockPos barrier = findBoundaryAt(mod, nx, cur.getY(), nz);
                if (barrier != null) {
                    PenBounds.BlockKey bk = new PenBounds.BlockKey(barrier.getX(), barrier.getY(), barrier.getZ());
                    perimeter.add(bk);
                    if (isFenceGate(mod.getWorld().getBlockState(barrier))) {
                        gates.add(bk);
                    }
                    continue;
                }

                // Otherwise try stepping into a walkable interior column (within +/-1 Y).
                BlockPos next = findWalkableFloorY(mod, nx, cur.getY(), nz);
                if (next == null) {
                    // Solid wall or cliff acts as a boundary.
                    perimeter.add(new PenBounds.BlockKey(nx, cur.getY(), nz));
                    continue;
                }

                interiorY.put(nKey, next.getY());
                queue.add(next);
                if (next.getX() < minX) minX = next.getX();
                if (next.getX() > maxX) maxX = next.getX();
                if (next.getY() < minY) minY = next.getY();
                if (next.getY() > maxY) maxY = next.getY();
                if (next.getZ() < minZ) minZ = next.getZ();
                if (next.getZ() > maxZ) maxZ = next.getZ();
            }
        }

        // Require at least 4 perimeter fence/wall blocks so we don't mistake a random 1x1 hole for a pen.
        int fenceCount = 0;
        for (PenBounds.BlockKey bk : perimeter) {
            BlockPos bp = new BlockPos(bk.x(), bk.y(), bk.z());
            if (mod.getChunkTracker().isChunkLoaded(bp) && isFenceOrWall(mod.getWorld().getBlockState(bp))) {
                fenceCount++;
            }
        }
        if (fenceCount < 4 || interiorY.isEmpty()) {
            return Optional.empty();
        }

        // Also capture diagonal corner fence posts around the interior bounding box so corners are protected too.
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                if (interiorY.containsKey(PenBounds.packXZ(x, z))) continue;
                for (int y = minY; y <= maxY + 1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!mod.getChunkTracker().isChunkLoaded(p)) continue;
                    BlockState st = mod.getWorld().getBlockState(p);
                    if (isFenceGate(st)) {
                        PenBounds.BlockKey k = new PenBounds.BlockKey(x, y, z);
                        perimeter.add(k);
                        gates.add(k);
                    } else if (isFenceOrWall(st)) {
                        perimeter.add(new PenBounds.BlockKey(x, y, z));
                    }
                }
            }
        }

        return Optional.of(new PenBounds(minX, minY, minZ, maxX, maxY, maxZ,
                interiorY.keySet(), perimeter, gates));
    }

    private static BlockPos findBoundaryAt(AltoClef mod, int x, int baseY, int z) {
        int[] offsets = {0, 1, -1};
        for (int dy : offsets) {
            BlockPos p = new BlockPos(x, baseY + dy, z);
            if (!mod.getChunkTracker().isChunkLoaded(p)) return null;
            BlockState st = mod.getWorld().getBlockState(p);
            if (isFenceOrWall(st)) {
                return p;
            }
        }
        return null;
    }

    private static BlockPos findWalkableFloorY(AltoClef mod, int x, int baseY, int z) {
        int[] offsets = {0, 1, -1};
        for (int dy : offsets) {
            int y = baseY + dy;
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos head = feet.up();
            BlockPos below = feet.down();
            if (!mod.getChunkTracker().isChunkLoaded(feet)) continue;
            BlockState feetState = mod.getWorld().getBlockState(feet);
            BlockState headState = mod.getWorld().getBlockState(head);
            BlockState belowState = mod.getWorld().getBlockState(below);
            if (isFenceOrWall(feetState) || isFenceOrWall(headState)) continue;
            if (!isPassable(feet, feetState) || !isPassable(head, headState)) continue;
            if (isSolidFloor(below, belowState)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean isPassable(BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) return true;
        if (isFenceOrWall(state)) return false;
        return !WorldHelper.isSolidBlock(pos);
    }

    private static boolean isSolidFloor(BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) return false;
        if (isFenceOrWall(state)) return false;
        return WorldHelper.isSolidBlock(pos);
    }
}
