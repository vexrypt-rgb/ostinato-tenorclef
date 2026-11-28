package adris.altoclef.swarm.survey;

import adris.altoclef.swarm.AssignmentExecutor;
import adris.altoclef.swarm.Capability;
import adris.altoclef.swarm.Objective;
import adris.altoclef.swarm.SwarmCoordinator;
import adris.altoclef.swarm.SwarmMessage;
import adris.altoclef.swarm.SwarmWorker;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** In-memory swarm with scripted scouts: no Minecraft, no world physics. Drives leader and workers in lock step. */
final class SurveyHarness {
    /** A scout whose "client" sees a scripted number of its todo chunks per poll. */
    static final class ScriptedScout implements AssignmentExecutor {
        enum Mode { OBSERVE, FREEZE, UNREACHABLE, CLAIM_WITHOUT_LOOKING }

        Mode mode = Mode.OBSERVE;
        int perPoll = 2;
        String dim = "OVERWORLD";
        String failReason = "UNREACHABLE:NO_PATH";
        double x, z;
        boolean moving;
        SurveyTask task;
        final Set<Long> todo = new LinkedHashSet<>();
        final Set<Long> fresh = new LinkedHashSet<>();
        final Set<Long> allSeen = new LinkedHashSet<>();
        int starts;
        /** Region numbers this scout can never get to (it reports UNREACHABLE for them, whatever its mode). */
        final Set<Integer> unreachableRegions = new HashSet<>();
        /** The todo set of every offer this scout was handed, in order. */
        final java.util.List<Set<Long>> offered = new java.util.ArrayList<>();
        boolean cancelled;
        Map<String, String> lastUpdate = Map.of();

        @Override public void start(Objective o) {
            task = o.survey();
            starts++;
            cancelled = false;
            todo.clear();
            long m = task.todo();
            SurveyGrid g = task.grid();
            while (m != 0) {
                int bit = Long.numberOfTrailingZeros(m);
                m &= m - 1;
                todo.add(SurveyGrid.pack(g.chunkX(task.region(), bit), g.chunkZ(task.region(), bit)));
            }
            offered.add(new LinkedHashSet<>(todo));
        }

        @Override public Status poll() {
            if (task == null || cancelled) return Status.idle();
            if (unreachableRegions.contains(task.region())) return Status.failed(failReason, Map.of("left", String.valueOf(todo.size())));
            switch (mode) {
                case FREEZE: return Status.running(10);
                case UNREACHABLE:
                    // sees nothing, reports it cannot get there
                    return Status.failed(failReason, Map.of("left", String.valueOf(todo.size())));
                case CLAIM_WITHOUT_LOOKING: return Status.succeeded(Map.of());
                default:
            }
            moving = true;
            x += 8;
            int n = 0;
            for (var it = todo.iterator(); it.hasNext() && n < perPoll; n++) {
                long k = it.next();
                it.remove();
                fresh.add(k);
                allSeen.add(k);
            }
            return todo.isEmpty() ? Status.succeeded(Map.of("left", "0")) : Status.running(50);
        }

        @Override public Set<Long> drainObserved() {
            Set<Long> out = new LinkedHashSet<>(fresh);
            fresh.clear();
            return out;
        }

        @Override public String dimension() { return dim; }
        @Override public double[] position() { return new double[]{x, 64, z}; }
        @Override public void cancel() { cancelled = true; }
        @Override public void update(Map<String, String> fields) {
            lastUpdate = fields;
            String hex = fields.get("todo");
            if (hex == null || task == null) return;
            long keep = Long.parseUnsignedLong(hex, 16);
            SurveyGrid g = task.grid();
            todo.removeIf(k -> (keep & (1L << g.bitOf(task.region(), SurveyGrid.unpackX(k), SurveyGrid.unpackZ(k)))) == 0);
        }
    }

    static final class Node {
        final SwarmWorker worker;
        final ScriptedScout scout = new ScriptedScout();
        Node(String id, SurveyHarness h, Set<Capability> caps) {
            worker = new SwarmWorker(id, caps, scout,
                    m -> { if (!h.offline.contains(id)) { h.toLeader++; h.leader.onMessage(id, SwarmMessage.decode(m.encode()), h.clock); } }, 1_000);
        }
    }

    long clock = 1_000;
    int toLeader, toAgents;
    final Set<String> offline = new HashSet<>();
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final SwarmCoordinator leader;

    SurveyHarness() { this(defaultConfig()); }

    SurveyHarness(SwarmCoordinator.Config cfg) {
        leader = new SwarmCoordinator((agent, m) -> {
            toAgents++;
            Node n = nodes.get(agent);
            if (n != null && !offline.contains(agent)) n.worker.onMessage(SwarmMessage.decode(m.encode()), clock);
        }, cfg);
    }

    static SwarmCoordinator.Config defaultConfig() {
        SwarmCoordinator.Config c = new SwarmCoordinator.Config();
        c.heartbeatTimeoutMs = 5_000;
        c.offerTimeoutMs = 3_000;
        c.surveyStallMs = 20_000;
        c.situationalRetryDelayMs = 1_000;
        return c;
    }

    Node join(String id, double x, double z) { return join(id, x, z, EnumSet.of(Capability.CAN_SCOUT)); }

    Node join(String id, double x, double z, Set<Capability> caps) {
        Node n = new Node(id, this, caps);
        n.scout.x = x;
        n.scout.z = z;
        nodes.put(id, n);
        n.worker.register(clock);
        return n;
    }

    void step(long ms) {
        clock += ms;
        for (Node n : nodes.values()) if (!offline.contains(n.worker.id())) n.worker.tick(clock);
        leader.tick(clock);
    }

    void run(int steps, long ms) { for (int i = 0; i < steps; i++) step(ms); }

    boolean runUntilDone(SurveyBoard b, int maxSteps, long ms) {
        for (int i = 0; i < maxSteps && !b.done(); i++) step(ms);
        return b.done();
    }
}
