package adris.altoclef.tasks.husbandry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java representation of a designated fenced pen.
 * <p>
 * Tracks interior columns, vertical bounds, fence/wall perimeter blocks, fence gate blocks,
 * and the exact set of chunk keys covering the pen and its perimeter so runtime code can
 * verify chunk residency, contain herd counts accurately, and forbid breaking/placing/gate-opening.
 */
public final class PenBounds {

    public record BlockKey(int x, int y, int z) {
    }

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final Set<Long> interiorColumns;
    private final Set<BlockKey> perimeterBlocks;
    private final Set<BlockKey> gateBlocks;
    private final Set<Long> requiredChunkKeys;

    public PenBounds(int minX, int minY, int minZ,
                     int maxX, int maxY, int maxZ,
                     Set<Long> interiorColumns,
                     Set<BlockKey> perimeterBlocks,
                     Set<BlockKey> gateBlocks) {
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
        this.interiorColumns = interiorColumns == null || interiorColumns.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(interiorColumns));
        this.perimeterBlocks = perimeterBlocks == null || perimeterBlocks.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(perimeterBlocks));
        this.gateBlocks = gateBlocks == null || gateBlocks.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(gateBlocks));

        Set<Long> chunks = new LinkedHashSet<>();
        int cMinX = Math.floorDiv(this.minX - 1, 16);
        int cMaxX = Math.floorDiv(this.maxX + 1, 16);
        int cMinZ = Math.floorDiv(this.minZ - 1, 16);
        int cMaxZ = Math.floorDiv(this.maxZ + 1, 16);
        for (int cx = cMinX; cx <= cMaxX; cx++) {
            for (int cz = cMinZ; cz <= cMaxZ; cz++) {
                chunks.add(packXZ(cx, cz));
            }
        }
        this.requiredChunkKeys = Collections.unmodifiableSet(chunks);
    }

    public static PenBounds box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new PenBounds(minX, minY, minZ, maxX, maxY, maxZ,
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    }

    public static long packXZ(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    public Set<Long> interiorColumns() {
        return interiorColumns;
    }

    public Set<BlockKey> perimeterBlocks() {
        return perimeterBlocks;
    }

    public Set<BlockKey> gateBlocks() {
        return gateBlocks;
    }

    public Set<Long> requiredChunkKeys() {
        return requiredChunkKeys;
    }

    public double centerX() {
        return (minX + maxX + 1.0) * 0.5;
    }

    public double centerY() {
        return (minY + maxY) * 0.5;
    }

    public double centerZ() {
        return (minZ + maxZ + 1.0) * 0.5;
    }

    /**
     * Returns true if the world point lies inside the designated pen's interior.
     */
    public boolean containsPoint(double x, double y, double z) {
        if (y < minY - 0.5 || y > maxY + 2.5) {
            return false;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!interiorColumns.isEmpty()) {
            return interiorColumns.contains(packXZ(bx, bz));
        }
        return x >= minX && x < maxX + 1.0 && z >= minZ && z < maxZ + 1.0;
    }

    public boolean containsBlockColumn(int x, int z) {
        if (!interiorColumns.isEmpty()) {
            return interiorColumns.contains(packXZ(x, z));
        }
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * Blocks that must NEVER be broken: all perimeter fences/gates/walls and the floor directly beneath them.
     */
    public boolean isProtectedPerimeter(int x, int y, int z) {
        if (perimeterBlocks.contains(new BlockKey(x, y, z))
                || perimeterBlocks.contains(new BlockKey(x, y + 1, z))
                || gateBlocks.contains(new BlockKey(x, y, z))
                || gateBlocks.contains(new BlockKey(x, y + 1, z))) {
            return true;
        }
        // Even for explicit-box pens without scanned perimeter blocks, protect the 1-block shell around the box.
        boolean onShellXZ = (x >= minX - 1 && x <= maxX + 1 && z >= minZ - 1 && z <= maxZ + 1)
                && !containsBlockColumn(x, z);
        return onShellXZ && y >= minY - 1 && y <= maxY + 2;
    }

    /**
     * Positions where placing blocks is forbidden to avoid creating jump-out ramps over the fence.
     */
    public boolean isProtectedPlacement(int x, int y, int z) {
        if (isProtectedPerimeter(x, y, z)) {
            return true;
        }
        // Protect interior ring directly adjacent to the fence so the bot never places a step block inside the pen.
        if (containsBlockColumn(x, z) && y >= minY && y <= maxY + 2) {
            return true;
        }
        return false;
    }

    public boolean isGate(int x, int y, int z) {
        return gateBlocks.contains(new BlockKey(x, y, z));
    }

    public String describe() {
        return "[" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ + "]"
                + (interiorColumns.isEmpty() ? "" : " (" + interiorColumns.size() + " floor cols)");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PenBounds that)) return false;
        return minX == that.minX && minY == that.minY && minZ == that.minZ
                && maxX == that.maxX && maxY == that.maxY && maxZ == that.maxZ
                && Objects.equals(interiorColumns, that.interiorColumns)
                && Objects.equals(perimeterBlocks, that.perimeterBlocks)
                && Objects.equals(gateBlocks, that.gateBlocks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minX, minY, minZ, maxX, maxY, maxZ, interiorColumns, perimeterBlocks, gateBlocks);
    }

    @Override
    public String toString() {
        return "PenBounds" + describe();
    }
}
