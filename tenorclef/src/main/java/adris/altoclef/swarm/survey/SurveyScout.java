package adris.altoclef.swarm.survey;

import adris.altoclef.swarm.AssignmentExecutor;
import adris.altoclef.swarm.Objective;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The scout's side of a survey, with no Minecraft types: it asks a {@link Port} where it is, which chunks its client has
 * loaded, and to walk somewhere. It never decides that the survey is done; it reports what its client saw and the
 * leader checks the merged result. Movement is always bounded: a target chunk it cannot reach or load is tried at most
 * {@link Config#maxTargetTries} times, then given up with a reason.
 */
public final class SurveyScout implements AssignmentExecutor {
    /** The live world as the scout needs it. The live adapter uses Baritone's goal process; tests use a simulated world. */
    public interface Port {
        String dimension();
        /** Block coordinates {x, y, z}, or null before the player exists. */
        double[] position();
        /** True when this client has the chunk loaded right now. */
        boolean isLoaded(int cx, int cz);
        /** Starts walking toward a block position; replaces any earlier goal. */
        void goTo(double x, double z);
        /** True while the last goTo is still being walked. */
        boolean moving();
        void stopMoving();
        /** How many chunks around the player this client keeps loaded. */
        int viewRadius();
        long nowMs();
    }

    public static final class Config {
        public long stallMs = 60_000;
        public int maxTargetTries = 2;
    }

    private final Port port;
    private final Config cfg;

    private SurveyTask task;
    private SurveyGrid grid;
    private final Set<Long> seen = new HashSet<>();            // every in-area chunk this client has had loaded
    private final Set<Long> fresh = new LinkedHashSet<>();     // seen but not yet handed to drainObserved
    private final Set<Long> todo = new LinkedHashSet<>();      // region chunks nobody has observed yet
    private final Set<Long> abandoned = new HashSet<>();
    private final Map<Long, Integer> tries = new HashMap<>();
    private final Map<Long, String> whyAbandoned = new HashMap<>();
    private int initialTodo;
    private Long target;
    private double goalX, goalZ;
    private long lastProgressMs;
    private double progX = Double.NaN, progZ = Double.NaN;
    private int lastScanCx = Integer.MIN_VALUE, lastScanCz = Integer.MIN_VALUE, scanAge;

    public SurveyScout(Port port) { this(port, new Config()); }

    public SurveyScout(Port port, Config cfg) {
        this.port = port;
        this.cfg = cfg;
    }

    @Override
    public void start(Objective objective) {
        task = objective.survey();
        grid = task.grid();
        seen.clear();
        fresh.clear();
        todo.clear();
        abandoned.clear();
        tries.clear();
        whyAbandoned.clear();
        target = null;
        long m = task.todo();
        while (m != 0) {
            int bit = Long.numberOfTrailingZeros(m);
            m &= m - 1;
            todo.add(SurveyGrid.pack(grid.chunkX(task.region(), bit), grid.chunkZ(task.region(), bit)));
        }
        initialTodo = Math.max(1, todo.size());
        lastProgressMs = port.nowMs();
        progX = progZ = Double.NaN;
        lastScanCx = Integer.MIN_VALUE;
    }

    @Override
    public Status poll() {
        if (task == null) return Status.idle();
        long now = port.nowMs();
        if (!task.area().dimension().equals(port.dimension())) {
            return finish(Status.failed("WRONG_DIMENSION: in " + port.dimension() + ", survey is " + task.area().dimension(), left()));
        }
        double[] p = port.position();
        if (p == null) return Status.running(0);
        int before = seen.size();
        scan(p);
        todo.removeAll(seen);
        abandoned.removeAll(seen); // seeing it settles it, whatever was concluded before
        whyAbandoned.keySet().retainAll(abandoned);
        if (seen.size() != before) lastProgressMs = now;
        if (todo.isEmpty()) return finish(abandoned.isEmpty() ? Status.succeeded(left()) : Status.failed(abandonReason(), left()));

        if (target != null && !todo.contains(target)) target = null; // seen on the way, or another scout saw it
        if (target != null && !port.moving()) {
            // The walk ended and the chunk is still not seen: unreachable, or it never loads.
            int n = tries.merge(target, 1, Integer::sum);
            if (n >= cfg.maxTargetTries) {
                boolean close = Math.hypot(goalX - p[0], goalZ - p[2]) < 6;
                abandoned.add(target);
                whyAbandoned.put(target, close ? "CHUNK_NOT_LOADED" : "NO_PATH");
                todo.remove(target);
                lastProgressMs = now; // working through a give-up is not a stall
            }
            target = null;
        }
        if (target == null) {
            if (todo.isEmpty()) return finish(Status.failed(abandonReason(), left())); // only abandoned chunks remain
            target = nearest(p);
            walkToward(p);
        }
        if (Double.isNaN(progX) || Math.hypot(p[0] - progX, p[2] - progZ) >= 2.0) {
            progX = p[0];
            progZ = p[2];
            lastProgressMs = now;
        }
        if (now - lastProgressMs > cfg.stallMs) {
            return finish(Status.failed("STALLED: no new chunk and no movement for " + (now - lastProgressMs) / 1000 + "s", left()));
        }
        return Status.running(Math.min(99, (initialTodo - todo.size() - abandoned.size()) * 100 / initialTodo));
    }

    private Status finish(Status s) {
        port.stopMoving();
        return s;
    }

    private String abandonReason() {
        Map<String, Integer> counts = new HashMap<>();
        for (String r : whyAbandoned.values()) counts.merge(r, 1, Integer::sum);
        StringBuilder b = new StringBuilder("UNREACHABLE:");
        boolean first = true;
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(counts).entrySet()) {
            if (!first) b.append('+');
            b.append(e.getKey()).append('x').append(e.getValue());
            first = false;
        }
        return b.toString();
    }

    private Map<String, String> left() {
        return Map.of("left", String.valueOf(todo.size()), "abandoned", String.valueOf(abandoned.size()));
    }

    /** Records every in-area chunk the client has loaded around the player. Only rescans when the player changes chunk. */
    private void scan(double[] p) {
        int pcx = (int) Math.floor(p[0] / 16.0), pcz = (int) Math.floor(p[2] / 16.0);
        if (pcx == lastScanCx && pcz == lastScanCz && ++scanAge < 20) return; // chunks also arrive late: rescan now and then
        scanAge = 0;
        lastScanCx = pcx;
        lastScanCz = pcz;
        int r = Math.max(1, port.viewRadius());
        SurveyArea a = task.area();
        for (int cx = Math.max(a.minCx(), pcx - r); cx <= Math.min(a.maxCx(), pcx + r); cx++) {
            for (int cz = Math.max(a.minCz(), pcz - r); cz <= Math.min(a.maxCz(), pcz + r); cz++) {
                long k = SurveyGrid.pack(cx, cz);
                if (seen.contains(k) || !port.isLoaded(cx, cz)) continue;
                seen.add(k);
                fresh.add(k);
            }
        }
    }

    private long nearest(double[] p) {
        long best = 0;
        double bd = Double.POSITIVE_INFINITY;
        for (long k : todo) {
            double d = Math.hypot(SurveyGrid.unpackX(k) * 16.0 + 8 - p[0], SurveyGrid.unpackZ(k) * 16.0 + 8 - p[2]);
            if (d < bd || (d == bd && k < best)) {
                bd = d;
                best = k;
            }
        }
        return best;
    }

    /** Walks to just inside viewing range of the target rather than onto it, so one walk can reveal several chunks. */
    private void walkToward(double[] p) {
        double tx = SurveyGrid.unpackX(target) * 16.0 + 8, tz = SurveyGrid.unpackZ(target) * 16.0 + 8;
        double dist = Math.hypot(tx - p[0], tz - p[2]);
        double standoff = Math.max(0, port.viewRadius() - 1) * 16.0;
        if (dist > standoff + 8) {
            goalX = tx - (tx - p[0]) / dist * standoff;
            goalZ = tz - (tz - p[2]) / dist * standoff;
        } else {
            goalX = tx; // already in range yet not loaded: go to it
            goalZ = tz;
        }
        port.goTo(goalX, goalZ);
    }

    @Override
    public void update(Map<String, String> fields) {
        String hex = fields.get("todo");
        if (hex == null || task == null) return;
        try {
            long keep = Long.parseUnsignedLong(hex, 16);
            todo.removeIf(k -> (keep & (1L << grid.bitOf(task.region(), SurveyGrid.unpackX(k), SurveyGrid.unpackZ(k)))) == 0);
        } catch (NumberFormatException ignored) {
            // a garbled update changes nothing
        }
    }

    @Override
    public Set<Long> drainObserved() {
        if (fresh.isEmpty()) return Set.of();
        Set<Long> out = new LinkedHashSet<>(fresh);
        fresh.clear();
        return out;
    }

    @Override
    public String dimension() { return port.dimension(); }

    @Override
    public double[] position() { return port.position(); }

    @Override
    public void cancel() {
        port.stopMoving();
        task = null;
        target = null;
    }
}
