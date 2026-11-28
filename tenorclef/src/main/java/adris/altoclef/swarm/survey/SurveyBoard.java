package adris.altoclef.swarm.survey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The leader's picture of one survey. Chunks are tracked as four disjoint sets so a player can read exact progress:
 * <ul>
 *   <li><b>observed</b>: seen by a scout's client in the survey's dimension (never un-seen),</li>
 *   <li><b>claimed</b>: not yet observed, inside a region a scout currently holds,</li>
 *   <li><b>unknown</b>: not yet observed and nobody holds it,</li>
 *   <li><b>unreachable</b>: given up on, with a recorded reason, after a bounded number of tries.</li>
 * </ul>
 * Claimed is derived (unobserved chunks of a held region) so it can never disagree with the claim that produced it.
 * Not thread-safe on its own; {@code SwarmCoordinator} serialises every call.
 */
public final class SurveyBoard {
    public enum RegionState { UNKNOWN, CLAIMED, OBSERVED, UNREACHABLE }

    public record Snapshot(long total, long observed, long claimed, long unknown, long unreachable,
                           int regions, int regionsObserved, int regionsClaimed, int regionsUnknown, int regionsUnreachable) {}

    /** What one merge changed. {@code touchedHeld} lists regions held by someone other than the reporter that changed. */
    public record MergeResult(long fresh, long duplicate, boolean rejected, Set<Integer> touchedHeld) {}

    private static final class Region {
        final long full;
        long observed;
        long unreachable;
        String holder;
        long claimedAtMs;
        long lastProgressMs;
        boolean hasPos;
        double lx, lz;
        int attempts;
        String reason = "";
        final List<String> failures = new ArrayList<>();

        Region(long full) { this.full = full; }

        long unknown() { return full & ~observed & ~unreachable; }
    }

    private final String id;
    private final SurveyGrid grid;
    private final Region[] regions;
    private final SurveyMetrics metrics;
    private final long startedMs;
    private final Map<String, double[]> legPos = new LinkedHashMap<>();
    private long rejectedReports;
    private boolean closed;

    public SurveyBoard(String id, SurveyArea area, int preferredSide, long now) {
        this.id = id;
        this.grid = SurveyGrid.plan(area, preferredSide);
        this.regions = new Region[grid.regionCount()];
        for (int i = 0; i < regions.length; i++) regions[i] = new Region(grid.fullMask(i));
        this.metrics = new SurveyMetrics(area.chunkCount(), now);
        this.startedMs = now;
    }

    public String id() { return id; }
    public SurveyGrid grid() { return grid; }
    public SurveyArea area() { return grid.area(); }
    public SurveyMetrics metrics() { return metrics; }
    public long startedMs() { return startedMs; }
    public int regionCount() { return regions.length; }
    public long rejectedReports() { return rejectedReports; }
    public boolean closed() { return closed; }
    public void close() { closed = true; }

    // ---- per-region reads ----

    public long todo(int r) { return regions[r].unknown(); }
    public long observedMask(int r) { return regions[r].observed; }
    public String holder(int r) { return regions[r].holder; }
    public String reason(int r) { return regions[r].reason; }
    public int attempts(int r) { return regions[r].attempts; }
    public List<String> failures(int r) { return List.copyOf(regions[r].failures); }
    public boolean regionDone(int r) { return regions[r].unknown() == 0; }

    public RegionState state(int r) {
        Region g = regions[r];
        if (g.unknown() == 0) return g.unreachable == 0 ? RegionState.OBSERVED : RegionState.UNREACHABLE;
        return g.holder != null ? RegionState.CLAIMED : RegionState.UNKNOWN;
    }

    // ---- claims ----

    /** @return false when the region is already held or has nothing left to observe. */
    public boolean claim(int r, String agent, long now) {
        Region g = regions[r];
        if (g.holder != null || g.unknown() == 0) return false;
        g.holder = agent;
        g.claimedAtMs = now;
        g.lastProgressMs = now;
        g.hasPos = false;
        g.attempts++;
        return true;
    }

    public void release(int r) {
        regions[r].holder = null;
    }

    /** A heartbeat from the holder. Moving counts as life; standing still does not. */
    public void touch(int r, String agent, double x, double z, long now) {
        Region g = regions[r];
        if (!agent.equals(g.holder)) return;
        if (g.hasPos && Math.hypot(x - g.lx, z - g.lz) >= 2.0) g.lastProgressMs = now;
        g.lx = x;
        g.lz = z;
        g.hasPos = true;
    }

    /** Held regions that have shown neither a new chunk nor movement for {@code stallMs}. */
    public List<Integer> stale(long now, long stallMs) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < regions.length; i++) {
            Region g = regions[i];
            if (g.holder != null && g.unknown() != 0 && now - g.lastProgressMs > stallMs) out.add(i);
        }
        return out;
    }

    public void recordFailure(int r, String agent, String reason) {
        Region g = regions[r];
        g.failures.add(agent + ": " + reason);
        g.reason = reason;
    }

    /** Gives up on what is still unknown in the region. Observed chunks stay observed. */
    public int markUnreachable(int r, String reason) {
        Region g = regions[r];
        long u = g.unknown();
        g.unreachable |= u;
        g.holder = null;
        g.reason = reason;
        return Long.bitCount(u);
    }

    // ---- observations ----

    /**
     * Merges what one scout saw. {@code masks} maps region index to the chunks of that region the scout's client
     * had loaded. A report for another dimension is refused whole: those chunks say nothing about this survey.
     */
    public MergeResult merge(String agent, String dimension, Map<Integer, Long> masks, long now) {
        if (!area().dimension().equals(dimension)) {
            rejectedReports++;
            return new MergeResult(0, 0, true, Set.of());
        }
        long fresh = 0, dup = 0;
        Set<Integer> touchedHeld = new LinkedHashSet<>();
        for (Map.Entry<Integer, Long> e : masks.entrySet()) {
            int r = e.getKey();
            if (r < 0 || r >= regions.length) continue;
            Region g = regions[r];
            long m = e.getValue() & g.full;
            long add = m & ~g.observed;
            dup += Long.bitCount(m & g.observed);
            if (add == 0) continue;
            fresh += Long.bitCount(add);
            g.observed |= add;
            g.unreachable &= ~add; // seeing it settles it, whatever was concluded before
            if (agent.equals(g.holder)) g.lastProgressMs = now;
            else if (g.holder != null) touchedHeld.add(r);
        }
        metrics.observed(now, fresh, dup);
        return new MergeResult(fresh, dup, false, touchedHeld);
    }

    /** Distance travelled by an agent since its previous report, for leg attribution. Returns 0 on the first sample. */
    public double legSince(String agent, double x, double z) {
        double[] p = legPos.put(agent, new double[]{x, z});
        return p == null ? 0 : Math.hypot(x - p[0], z - p[1]);
    }

    // ---- planning aids ----

    /** Block distance from a point to the nearest still-unobserved chunk centre of the region. */
    public double distanceToUnknown(int r, double x, double z) {
        long m = regions[r].unknown();
        double best = Double.POSITIVE_INFINITY;
        while (m != 0) {
            int bit = Long.numberOfTrailingZeros(m);
            m &= m - 1;
            double cx = grid.chunkX(r, bit) * 16.0 + 8, cz = grid.chunkZ(r, bit) * 16.0 + 8;
            best = Math.min(best, Math.hypot(cx - x, cz - z));
        }
        return best;
    }

    /** Blocks of penalty for starting this region right next to work another scout already holds. */
    public double crowding(int r, String forAgent, double weight) {
        if (weight <= 0) return 0;
        double sep = 1.5 * grid.side();
        double pen = 0;
        for (int i = 0; i < regions.length; i++) {
            Region g = regions[i];
            if (i == r || g.holder == null || g.holder.equals(forAgent) || g.unknown() == 0) continue;
            double d = Math.hypot((grid.centerX(i) - grid.centerX(r)) / 16.0, (grid.centerZ(i) - grid.centerZ(r)) / 16.0);
            if (d < sep) pen += (sep - d) * 16.0 * weight;
        }
        return pen;
    }

    // ---- reading it back ----

    public boolean done() {
        for (Region g : regions) if (g.unknown() != 0) return false;
        return true;
    }

    public Snapshot snapshot() {
        long obs = 0, claimed = 0, unknown = 0, unreach = 0;
        int ro = 0, rc = 0, ru = 0, rx = 0;
        for (int i = 0; i < regions.length; i++) {
            Region g = regions[i];
            obs += Long.bitCount(g.observed);
            unreach += Long.bitCount(g.unreachable);
            long u = Long.bitCount(g.unknown());
            if (g.holder != null) claimed += u; else unknown += u;
            switch (state(i)) {
                case OBSERVED -> ro++;
                case CLAIMED -> rc++;
                case UNKNOWN -> ru++;
                case UNREACHABLE -> rx++;
            }
        }
        return new Snapshot(area().chunkCount(), obs, claimed, unknown, unreach, regions.length, ro, rc, ru, rx);
    }

    public enum ChunkState { OBSERVED, CLAIMED, UNKNOWN, UNREACHABLE }

    public ChunkState chunkState(int cx, int cz) {
        int r = grid.regionOf(cx, cz);
        if (r < 0) throw new IllegalArgumentException("chunk outside the survey area");
        Region g = regions[r];
        long bit = 1L << grid.bitOf(r, cx, cz);
        if ((g.observed & bit) != 0) return ChunkState.OBSERVED;
        if ((g.unreachable & bit) != 0) return ChunkState.UNREACHABLE;
        return g.holder != null ? ChunkState.CLAIMED : ChunkState.UNKNOWN;
    }

    /** Packed chunks ({@link SurveyGrid#pack}) in the given state, in region then bit order. */
    public List<Long> chunks(ChunkState want) {
        List<Long> out = new ArrayList<>();
        for (int r = 0; r < regions.length; r++) {
            Region g = regions[r];
            long m = switch (want) {
                case OBSERVED -> g.observed;
                case UNREACHABLE -> g.unreachable;
                case CLAIMED -> g.holder != null ? g.unknown() : 0;
                case UNKNOWN -> g.holder == null ? g.unknown() : 0;
            };
            while (m != 0) {
                int bit = Long.numberOfTrailingZeros(m);
                m &= m - 1;
                out.add(SurveyGrid.pack(grid.chunkX(r, bit), grid.chunkZ(r, bit)));
            }
        }
        return out;
    }

    /** Player-readable progress: counts, who holds what, what was given up on and why, and a map when it fits. */
    public List<String> describe() {
        Snapshot s = snapshot();
        List<String> out = new ArrayList<>();
        out.add(String.format(java.util.Locale.ROOT, "survey %s %s: observed %d/%d (%.1f%%) claimed %d unknown %d unreachable %d; regions observed %d claimed %d unknown %d unreachable %d of %d%s",
                id, area(), s.observed(), s.total(), 100.0 * s.observed() / s.total(), s.claimed(), s.unknown(), s.unreachable(),
                s.regionsObserved(), s.regionsClaimed(), s.regionsUnknown(), s.regionsUnreachable(), s.regions(),
                closed ? " [stopped]" : done() ? " [complete]" : ""));
        for (int i = 0; i < regions.length; i++) {
            Region g = regions[i];
            RegionState st = state(i);
            if (st == RegionState.OBSERVED) continue;
            out.add("  region " + i + " chunks " + grid.minCx(i) + "," + grid.minCz(i) + ".." + (grid.minCx(i) + grid.width(i) - 1)
                    + "," + (grid.minCz(i) + grid.height(i) - 1) + " " + st
                    + (g.holder != null ? " by " + g.holder : "")
                    + " left " + Long.bitCount(g.unknown() | g.unreachable)
                    + (g.unreachable != 0 ? " unreachable " + Long.bitCount(g.unreachable) + " (" + g.reason + "; tried " + g.attempts + "x)" : "")
                    + (!g.failures.isEmpty() && g.unreachable == 0 ? " last failure " + g.failures.get(g.failures.size() - 1) : ""));
        }
        out.addAll(SurveyRender.map(this, 64, 32));
        return out;
    }
}
