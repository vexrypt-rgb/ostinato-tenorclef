package baritone.swarm.center.app;

import baritone.swarm.center.feed.TerrainFeed;

import java.util.Arrays;
import java.util.Collection;

/**
 * Ostinato: Phase 3 window, which terrain column the map draws. Slices in another dimension are ignored. Where two
 * slices in the same dimension overlap, the one with the later {@code writtenAtMs} wins. An uncached column stays
 * {@link TerrainFeed#UNCACHED}.
 */
public final class MapTerrain {

    /** Ostinato: one merged surface sample (block id and top block Y, or {@link TerrainFeed#UNKNOWN_Y}). */
    public record Column(String block, int y) {
        public static final Column EMPTY = new Column(TerrainFeed.UNCACHED, TerrainFeed.UNKNOWN_Y);
    }

    /**
     * Ostinato: flat merged terrain grid for O(1) lookup during isometric rendering and surface ray-picking.
     * Built once per terrain change from all slices in the active dimension, latest {@code writtenAtMs} winning.
     */
    public static final class Grid {
        public static final Grid EMPTY = new Grid(0, 0, 0, 0, new int[0], new short[0], new byte[0], 64, 64, 64);

        /** Surface material hint used by the isometric cliff skirt shader. */
        public static final byte KIND_NORMAL = 0;
        public static final byte KIND_GRASS = 1;
        public static final byte KIND_WATER = 2;

        public final int minX, minZ, width, depth;
        public final int[] rgb;
        public final short[] height;
        public final byte[] kind;
        public final int minY, maxY, medianY;

        private Grid(int minX, int minZ, int width, int depth, int[] rgb, short[] height, byte[] kind,
                     int minY, int maxY, int medianY) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.depth = depth;
            this.rgb = rgb;
            this.height = height;
            this.kind = kind;
            this.minY = minY;
            this.maxY = maxY;
            this.medianY = medianY;
        }

        public boolean isEmpty() {
            return width == 0 || depth == 0;
        }

        public boolean contains(int x, int z) {
            return x >= minX && z >= minZ && x < minX + width && z < minZ + depth;
        }

        public int rgbAt(int x, int z) {
            if (!contains(x, z)) {
                return 0;
            }
            return rgb[(z - minZ) * width + (x - minX)];
        }

        public int heightAt(int x, int z) {
            if (!contains(x, z)) {
                return TerrainFeed.UNKNOWN_Y;
            }
            return height[(z - minZ) * width + (x - minX)];
        }

        public byte kindAt(int x, int z) {
            if (!contains(x, z)) {
                return KIND_NORMAL;
            }
            return kind[(z - minZ) * width + (x - minX)];
        }

        public static Grid merge(Collection<TerrainFeed> slices, char dim) {
            if (slices == null || slices.isEmpty()) {
                return EMPTY;
            }
            // Guard against absurdly distant bots causing a huge allocation: cap merged extent to 1024x1024.
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int count = 0;
            for (TerrainFeed t : slices) {
                if (t == null || t.dim() != dim) {
                    continue;
                }
                minX = Math.min(minX, t.originX());
                minZ = Math.min(minZ, t.originZ());
                maxX = Math.max(maxX, t.originX() + t.width());
                maxZ = Math.max(maxZ, t.originZ() + t.depth());
                count++;
            }
            if (count == 0) {
                return EMPTY;
            }
            long wLong = (long) maxX - minX;
            long dLong = (long) maxZ - minZ;
            if (wLong <= 0 || dLong <= 0 || wLong * dLong > 1_048_576L) {
                // Fall back to keeping the newest slice only when slices are thousands of blocks apart.
                TerrainFeed newest = null;
                for (TerrainFeed t : slices) {
                    if (t != null && t.dim() == dim && (newest == null || t.writtenAtMs() >= newest.writtenAtMs())) {
                        newest = t;
                    }
                }
                if (newest == null) {
                    return EMPTY;
                }
                minX = newest.originX();
                minZ = newest.originZ();
                maxX = minX + newest.width();
                maxZ = minZ + newest.depth();
            }
            int w = maxX - minX;
            int d = maxZ - minZ;
            int[] rgb = new int[w * d];
            short[] height = new short[w * d];
            byte[] kind = new byte[w * d];
            long[] stamp = new long[w * d];
            Arrays.fill(height, TerrainFeed.UNKNOWN_Y);
            Arrays.fill(stamp, Long.MIN_VALUE);

            for (TerrainFeed t : slices) {
                if (t == null || t.dim() != dim) {
                    continue;
                }
                int tx0 = Math.max(minX, t.originX());
                int tz0 = Math.max(minZ, t.originZ());
                int tx1 = Math.min(maxX, t.originX() + t.width());
                int tz1 = Math.min(maxZ, t.originZ() + t.depth());
                if (tx0 >= tx1 || tz0 >= tz1) {
                    continue;
                }
                int palSize = t.palette().size();
                int[] palRgb = new int[palSize];
                byte[] palKind = new byte[palSize];
                for (int i = 0; i < palSize; i++) {
                    String id = t.palette().get(i);
                    palRgb[i] = BlockColors.color(id);
                    palKind[i] = classify(id);
                }
                byte[] srcBlocks = t.blocks();
                short[] srcHeights = t.heights();
                int tw = t.width();
                int ox = t.originX();
                int oz = t.originZ();
                long ts = t.writtenAtMs();
                for (int z = tz0; z < tz1; z++) {
                    int dstRow = (z - minZ) * w - minX;
                    int srcRow = (z - oz) * tw - ox;
                    for (int x = tx0; x < tx1; x++) {
                        int pi = srcBlocks[srcRow + x] & 0xFF;
                        if (pi == 0 || pi >= palSize) {
                            continue;
                        }
                        int color = palRgb[pi];
                        if (color == 0) {
                            continue;
                        }
                        int di = dstRow + x;
                        if (ts >= stamp[di]) {
                            stamp[di] = ts;
                            rgb[di] = color;
                            height[di] = srcHeights[srcRow + x];
                            kind[di] = palKind[pi];
                        }
                    }
                }
            }
            int loY = Integer.MAX_VALUE;
            int hiY = Integer.MIN_VALUE;
            long sumY = 0;
            int valid = 0;
            for (int i = 0; i < rgb.length; i++) {
                if (rgb[i] != 0 && height[i] != TerrainFeed.UNKNOWN_Y) {
                    int y = height[i];
                    if (y < loY) loY = y;
                    if (y > hiY) hiY = y;
                    sumY += y;
                    valid++;
                }
            }
            if (valid == 0) {
                return EMPTY;
            }
            int meanY = (int) Math.round(sumY / (double) valid);
            return new Grid(minX, minZ, w, d, rgb, height, kind, loY, hiY, meanY);
        }

        private static byte classify(String id) {
            if (id == null) {
                return KIND_NORMAL;
            }
            String k = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
            if ("grass_block".equalsIgnoreCase(k) || "grass".equalsIgnoreCase(k) || "podzol".equalsIgnoreCase(k)) {
                return KIND_GRASS;
            }
            if ("water".equalsIgnoreCase(k)) {
                return KIND_WATER;
            }
            return KIND_NORMAL;
        }
    }

    private MapTerrain() {}

    public static String block(Collection<TerrainFeed> slices, char dim, int x, int z) {
        return column(slices, dim, x, z).block();
    }

    public static int height(Collection<TerrainFeed> slices, char dim, int x, int z) {
        return column(slices, dim, x, z).y();
    }

    public static Column column(Collection<TerrainFeed> slices, char dim, int x, int z) {
        if (slices == null || slices.isEmpty()) {
            return Column.EMPTY;
        }
        TerrainFeed best = null;
        String id = TerrainFeed.UNCACHED;
        int y = TerrainFeed.UNKNOWN_Y;
        for (TerrainFeed t : slices) {
            if (t == null || t.dim() != dim || !t.contains(x, z)) {
                continue;
            }
            String at = t.blockAt(x, z);
            if (TerrainFeed.UNCACHED.equals(at)) {
                continue;
            }
            if (best == null || t.writtenAtMs() >= best.writtenAtMs()) {
                best = t;
                id = at;
                y = t.heightAt(x, z);
            }
        }
        return best == null ? Column.EMPTY : new Column(id, y);
    }
}
