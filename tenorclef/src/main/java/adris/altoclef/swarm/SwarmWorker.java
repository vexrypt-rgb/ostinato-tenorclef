package adris.altoclef.swarm;

import adris.altoclef.swarm.survey.SurveyGrid;
import adris.altoclef.swarm.survey.SurveyTask;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The agent's side of the swarm protocol. It accepts offers it can honour, hands them to the
 * {@link AssignmentExecutor}, and reports what the executor says actually happened.
 * It owns no game logic and never claims success on its own.
 */
public final class SwarmWorker {
    public interface Uplink {
        void send(SwarmMessage message);
    }

    private final String id;
    private final Set<Capability> caps;
    private final AssignmentExecutor executor;
    private final Uplink uplink;
    private final long heartbeatMs;

    private String current;
    private Objective currentObjective;
    private int lastProgress = -1;
    private long lastHeartbeat = Long.MIN_VALUE / 2;
    private boolean registered;

    // Survey work: chunks this client saw, by region, waiting to be told to the leader. Bounded by the region count.
    private static final int COV_ENTRIES_PER_MESSAGE = 16;
    private SurveyTask currentSurvey;
    private final TreeMap<Integer, Long> pendingMasks = new TreeMap<>();
    private long lastCov = Long.MIN_VALUE / 2;

    public SwarmWorker(String id, Set<Capability> caps, AssignmentExecutor executor, Uplink uplink, long heartbeatMs) {
        this.id = id;
        this.caps = caps;
        this.executor = executor;
        this.uplink = uplink;
        this.heartbeatMs = heartbeatMs;
    }

    public String id() { return id; }
    public String currentAssignment() { return current; }

    public synchronized void register(long now) {
        SwarmMessage reg = SwarmMessage.of("reg", "caps", Capability.encode(caps));
        double[] p = executor.position(); // lets the leader place the first offer by distance, before any heartbeat
        if (p != null) reg.put("x", String.valueOf(p[0])).put("y", String.valueOf(p[1])).put("z", String.valueOf(p[2]));
        uplink.send(reg);
        registered = true;
        lastHeartbeat = Long.MIN_VALUE / 2;
    }

    public synchronized void leave() {
        if (current != null) executor.cancel();
        current = null;
        clearSurvey();
        uplink.send(SwarmMessage.of("bye"));
    }

    public synchronized void onMessage(SwarmMessage m, long now) {
        switch (m.op) {
            case "offer" -> offered(m);
            case "who" -> register(now); // the leader does not know us (it restarted)
            case "cancel" -> {
                if (current != null && current.equals(m.get("a"))) {
                    executor.cancel();
                    current = null;
                    currentObjective = null;
                    clearSurvey();
                }
            }
            case "upd" -> {
                if (current != null && current.equals(m.get("a"))) executor.update(m.fields());
            }
            default -> { }
        }
    }

    private void offered(SwarmMessage m) {
        String a = m.get("a");
        if (a == null) return;
        if (current != null) {
            // A repeated offer for the work already held means the leader lost track of it (missed accept, or it
            // wrote this agent off and came back): say so again instead of letting the offer time out.
            uplink.send(current.equals(a) ? SwarmMessage.of("accept", "a", a) : SwarmMessage.of("reject", "a", a, "why", "busy"));
            return;
        }
        if ("survey".equals(m.get("kind"))) {
            SurveyTask task = SurveyTask.from(m);
            if (task == null) {
                uplink.send(SwarmMessage.of("reject", "a", a, "why", "bad survey offer"));
                return;
            }
            if (!executor.alive()) {
                uplink.send(SwarmMessage.of("reject", "a", a, "why", "dead"));
                return;
            }
            current = a;
            currentSurvey = task;
            pendingMasks.clear();
            currentObjective = Objective.survey(task);
            lastProgress = -1;
            uplink.send(SwarmMessage.of("accept", "a", a));
            executor.start(currentObjective);
            return;
        }
        String item = m.get("item");
        if (item == null) {
            uplink.send(SwarmMessage.of("reject", "a", a, "why", "bad offer"));
            return;
        }
        if (!executor.alive()) {
            uplink.send(SwarmMessage.of("reject", "a", a, "why", "dead"));
            return;
        }
        current = a;
        currentObjective = Objective.acquire(item, m.getInt("n", 1));
        lastProgress = -1;
        uplink.send(SwarmMessage.of("accept", "a", a));
        executor.start(currentObjective);
    }

    public synchronized void tick(long now) {
        if (!registered) return;
        if (now - lastHeartbeat >= heartbeatMs) {
            lastHeartbeat = now;
            SwarmMessage hb = SwarmMessage.of("hb", "hp", String.valueOf(executor.health()));
            double[] p = executor.position();
            if (p != null) hb.put("x", String.valueOf(p[0])).put("y", String.valueOf(p[1])).put("z", String.valueOf(p[2]));
            if (current != null) hb.put("a", current);
            if (!executor.alive()) hb.put("alive", "0");
            String risk = executor.risk();
            if (risk != null && !risk.isEmpty()) hb.put("risk", risk);
            uplink.send(hb);
        }
        if (current == null) return;

        if (!executor.alive()) {
            // Death ends the work here: the leader hands it on, and a goal picked up again after the respawn
            // would compete with whoever got it.
            executor.cancel();
            finish("FAILED", Map.of(), "DIED");
            return;
        }
        AssignmentExecutor.Status s = executor.poll();
        if (currentSurvey != null) collectObserved();
        switch (s.phase()) {
            case RUNNING -> {
                if (s.progress() != lastProgress) {
                    lastProgress = s.progress();
                    uplink.send(SwarmMessage.of("prog", "a", current, "pct", String.valueOf(s.progress())));
                }
                if (currentSurvey != null) flushCoverage(now, false);
            }
            case SUCCEEDED -> { flushCoverage(now, true); finish("SUCCEEDED", s.evidence(), ""); }
            case FAILED -> { flushCoverage(now, true); finish("FAILED", s.evidence(), s.reason()); }
            case IDLE -> finish("FAILED", Map.of(), "EXECUTOR_IDLE"); // told to start, but nothing is running
        }
    }

    private void collectObserved() {
        SurveyGrid g = currentSurvey.grid();
        for (long packed : executor.drainObserved()) {
            int cx = SurveyGrid.unpackX(packed), cz = SurveyGrid.unpackZ(packed);
            int r = g.regionOf(cx, cz);
            if (r >= 0) pendingMasks.merge(r, 1L << g.bitOf(r, cx, cz), (x, y) -> x | y);
        }
    }

    /** Tells the leader which chunks this client has seen. Throttled to the heartbeat rate; forced before a result. */
    private void flushCoverage(long now, boolean force) {
        if (pendingMasks.isEmpty() || (!force && now - lastCov < heartbeatMs)) return;
        lastCov = now;
        do {
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (var it = pendingMasks.entrySet().iterator(); it.hasNext() && n < COV_ENTRIES_PER_MESSAGE; n++) {
                var e = it.next();
                if (b.length() > 0) b.append(',');
                b.append(e.getKey()).append(':').append(Long.toHexString(e.getValue()));
                it.remove();
            }
            SwarmMessage cov = SwarmMessage.of("cov", "a", current, "sv", currentSurvey.surveyId(), "dim", executor.dimension(), "m", b.toString());
            double[] pos = executor.position(); // where the scout is as it reports, so travel can be attributed to this report
            if (pos != null) cov.put("x", String.valueOf(pos[0])).put("z", String.valueOf(pos[2]));
            uplink.send(cov);
        } while (force && !pendingMasks.isEmpty());
    }

    private void clearSurvey() {
        currentSurvey = null;
        pendingMasks.clear();
    }

    private void finish(String status, Map<String, String> evidence, String reason) {
        SwarmMessage r = SwarmMessage.of("result", "a", current, "status", status);
        if (!reason.isEmpty()) r.put("reason", reason);
        evidence.forEach(r::put);
        // Clear first: a synchronous transport can deliver the next offer from inside send().
        current = null;
        currentObjective = null;
        clearSurvey();
        uplink.send(r);
    }

    /** Share something this agent saw. The swarm treats it as a belief, not a fact. */
    public synchronized void observe(String key, String value, double confidence) {
        uplink.send(SwarmMessage.of("obs", "key", key, "value", value, "conf", String.valueOf(confidence)));
    }
}
