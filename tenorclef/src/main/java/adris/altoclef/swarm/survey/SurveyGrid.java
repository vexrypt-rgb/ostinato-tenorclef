package adris.altoclef.swarm.survey;

/**
 * How an area is cut into bounded regions. Every region is at most 8x8 chunks so its coverage fits one {@code long}
 * mask (bit = row * width + column inside the region). The grid is derived from (area, side) alone, so a scout can
 * turn any chunk it sees into (region, bit) without asking the leader.
 */
public final class SurveyGrid {
    public static final int MAX_SIDE = 8;
    public static final int MAX_REGIONS = 256;
    public static final int DEFAULT_SIDE = 4;

    private final SurveyArea area;
    private final int side;
    private final int cols;
    private final int rows;

    public SurveyGrid(SurveyArea area, int side) {
        if (side < 1 || side > MAX_SIDE) throw new IllegalArgumentException("region side must be 1.." + MAX_SIDE);
        this.area = area;
        this.side = side;
        this.cols = (area.width() + side - 1) / side;
        this.rows = (area.height() + side - 1) / side;
        if ((long) cols * rows > MAX_REGIONS) throw new IllegalArgumentException("too many regions: " + (long) cols * rows);
    }

    /** Grows the region side from the preferred one until the area fits in {@link #MAX_REGIONS} regions. */
    public static SurveyGrid plan(SurveyArea area, int preferredSide) {
        int s = Math.max(1, Math.min(MAX_SIDE, preferredSide));
        while (true) {
            long cols = (area.width() + s - 1) / s;
            long rows = (area.height() + s - 1) / s;
            if (cols * rows <= MAX_REGIONS) return new SurveyGrid(area, s);
            if (s == MAX_SIDE) {
                throw new IllegalArgumentException("area too large: " + area.chunkCount() + " chunks, at most "
                        + (long) MAX_REGIONS * MAX_SIDE * MAX_SIDE + " can be surveyed at once");
            }
            s++;
        }
    }

    public SurveyArea area() { return area; }
    public int side() { return side; }
    public int regionCount() { return cols * rows; }

    public int minCx(int region) { return area.minCx() + (region % cols) * side; }
    public int minCz(int region) { return area.minCz() + (region / cols) * side; }
    public int width(int region) { return Math.min(side, area.maxCx() - minCx(region) + 1); }
    public int height(int region) { return Math.min(side, area.maxCz() - minCz(region) + 1); }

    public long fullMask(int region) {
        int n = width(region) * height(region);
        return n == 64 ? -1L : (1L << n) - 1;
    }

    /** @return the region holding the chunk, or -1 when it is outside the area. */
    public int regionOf(int cx, int cz) {
        if (!area.contains(cx, cz)) return -1;
        return ((cz - area.minCz()) / side) * cols + (cx - area.minCx()) / side;
    }

    public int bitOf(int region, int cx, int cz) {
        return (cz - minCz(region)) * width(region) + (cx - minCx(region));
    }

    public int chunkX(int region, int bit) { return minCx(region) + bit % width(region); }
    public int chunkZ(int region, int bit) { return minCz(region) + bit / width(region); }

    /** Block coordinates of a region's middle. */
    public double centerX(int region) { return (minCx(region) + width(region) / 2.0) * 16.0; }
    public double centerZ(int region) { return (minCz(region) + height(region) / 2.0) * 16.0; }

    public static long pack(int cx, int cz) { return (cx & 0xFFFFFFFFL) | ((long) cz << 32); }
    public static int unpackX(long p) { return (int) p; }
    public static int unpackZ(long p) { return (int) (p >> 32); }
}
