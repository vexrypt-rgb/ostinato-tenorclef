package adris.altoclef.swarm.survey;

import java.util.ArrayList;
import java.util.List;

/**
 * Unique coverage, duplicate travel and time to a coverage fraction. Used with the same rules for the coordinated
 * survey and for the baseline it is compared against, so the numbers mean the same thing.
 *
 * <p>Travel is attributed leg by leg. A leg is the distance covered since the previous observation event. Of the
 * in-area chunks that leg exposed, {@code fresh} were seen for the first time by anyone and {@code dup} had already
 * been seen. The leg counts as {@code new} travel for the fresh share and {@code duplicate} travel for the dup share.
 * A leg that exposed no in-area chunk at all is {@code offArea} travel (transit, or wandering outside the marked area);
 * it is reported separately so it is never hidden inside, or mistaken for, re-covering.
 */
public final class SurveyMetrics {
    private final long total;
    private final long startMs;
    private long unique;
    private long duplicateObservations;
    private double newTravel;
    private double duplicateTravel;
    private double offAreaTravel;
    private final List<long[]> timeline = new ArrayList<>(); // {timeMs, cumulative unique}

    public SurveyMetrics(long totalChunks, long startMs) {
        this.total = Math.max(1, totalChunks);
        this.startMs = startMs;
    }

    public void observed(long nowMs, long fresh, long dup) {
        duplicateObservations += dup;
        if (fresh > 0) {
            unique += fresh;
            timeline.add(new long[]{nowMs, unique});
        }
    }

    public void leg(double blocks, long fresh, long dup) {
        if (blocks <= 0) return;
        if (fresh + dup == 0) {
            offAreaTravel += blocks;
        } else {
            double f = (double) fresh / (fresh + dup);
            newTravel += blocks * f;
            duplicateTravel += blocks * (1 - f);
        }
    }

    public long unique() { return unique; }
    public long duplicateObservations() { return duplicateObservations; }
    public double newTravel() { return newTravel; }
    public double duplicateTravel() { return duplicateTravel; }
    public double offAreaTravel() { return offAreaTravel; }
    public double totalTravel() { return newTravel + duplicateTravel + offAreaTravel; }
    public double coverage() { return (double) unique / total; }

    /** @return milliseconds from the start until the given share of the area was first observed, or -1 if never. */
    public long timeToCoverage(double fraction) {
        long need = (long) Math.ceil(fraction * total - 1e-9);
        for (long[] p : timeline) if (p[1] >= need) return p[0] - startMs;
        return -1;
    }

    public String summary() {
        return String.format(java.util.Locale.ROOT, "unique=%d/%d (%.1f%%) dupObs=%d travel new=%.0f dup=%.0f offArea=%.0f blocks t95=%s",
                unique, total, 100.0 * coverage(), duplicateObservations, newTravel, duplicateTravel, offAreaTravel,
                timeToCoverage(0.95) < 0 ? "n/a" : (timeToCoverage(0.95) / 1000) + "s");
    }
}
