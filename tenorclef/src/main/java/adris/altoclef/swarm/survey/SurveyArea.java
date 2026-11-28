package adris.altoclef.swarm.survey;

/**
 * The marked area, as an inclusive rectangle of chunk coordinates in ONE dimension. Coverage is defined over
 * exactly these chunks: a chunk counts only when a scout's own client actually had it loaded in this dimension.
 * No Minecraft types, so it is shared by the leader, the scout logic and the tests.
 */
public record SurveyArea(String dimension, int minCx, int minCz, int maxCx, int maxCz) {
    public SurveyArea {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension required");
        if (maxCx < minCx || maxCz < minCz) throw new IllegalArgumentException("empty area");
    }

    /** Builds the area from two block-coordinate corners in any order. */
    public static SurveyArea fromBlocks(String dimension, int x1, int z1, int x2, int z2) {
        return new SurveyArea(dimension, Math.min(x1, x2) >> 4, Math.min(z1, z2) >> 4, Math.max(x1, x2) >> 4, Math.max(z1, z2) >> 4);
    }

    public int width() { return maxCx - minCx + 1; }
    public int height() { return maxCz - minCz + 1; }
    public long chunkCount() { return (long) width() * height(); }
    public boolean contains(int cx, int cz) { return cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz; }

    public String encodeRect() { return minCx + "," + minCz + "," + maxCx + "," + maxCz; }

    /** @return null when the text is not a rectangle. */
    public static SurveyArea decode(String dimension, String rect) {
        if (dimension == null || rect == null) return null;
        String[] p = rect.split(",");
        if (p.length != 4) return null;
        try {
            return new SurveyArea(dimension, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return dimension + " chunks " + minCx + "," + minCz + ".." + maxCx + "," + maxCz + " (" + chunkCount() + ")";
    }
}
