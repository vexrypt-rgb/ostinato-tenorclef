package adris.altoclef.swarm.survey;

import adris.altoclef.swarm.Capability;
import adris.altoclef.swarm.SwarmCoordinator;
import adris.altoclef.swarm.SwarmMessage;
import adris.altoclef.swarm.SwarmWorker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.IntBinaryOperator;

/**
 * A deterministic chunk-grid world for exercising survey coordination without Minecraft. It is NOT a live fixture: movement
 * is a simple cost model (speed / terrain cost along 8-connected chunk paths) and a chunk is "observed" when it is within
 * the view radius of the bot's chunk. Both arms of an A/B trial use the same physics and the same oracle meter, so only the
 * controller differs.
 */
final class SurveySim {
    static final double SPEED = 5.6;          // blocks per second on cost-1 ground (sprinting)
    static final long DT_MS = 500;            // simulation step; the swarm clock advances in the same steps
    static final double INF = Double.POSITIVE_INFINITY;

    // ---- terrain ----

    static final class World {
        final int minCx, minCz, maxCx, maxCz;
        final double[][] cost;
        final int view;
        final String dimension;

        World(String dimension, int minCx, int minCz, int maxCx, int maxCz, int view, IntBinaryOperator costTimes10) {
            this.dimension = dimension;
            this.minCx = minCx; this.minCz = minCz; this.maxCx = maxCx; this.maxCz = maxCz; this.view = view;
            cost = new double[maxCx - minCx + 1][maxCz - minCz + 1];
            for (int x = minCx; x <= maxCx; x++) {
                for (int z = minCz; z <= maxCz; z++) {
                    int c = costTimes10.applyAsInt(x, z);
                    cost[x - minCx][z - minCz] = c < 0 ? INF : c / 10.0;
                }
            }
        }

        boolean exists(int cx, int cz) { return cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz; }
        double cost(int cx, int cz) { return exists(cx, cz) ? cost[cx - minCx][cz - minCz] : INF; }
        boolean passable(int cx, int cz) { return cost(cx, cz) < INF; }
    }

    // ---- bot ----

    static final class Bot implements SurveyScout.Port {
        final String id;
        final World world;
        final java.util.function.LongSupplier clock;
        double x, z;
        boolean online = true;
        final Deque<double[]> path = new ArrayDeque<>();
        final Set<Long> everLoaded = new HashSet<>();  // this client's own chunk cache (baseline explore reads it)
        double travelSinceEvent;
        double totalTravel;
        int cellX, cellZ;
        boolean[][] reach; // cells reachable from the last goTo's source

        Bot(String id, World world, double x, double z, java.util.function.LongSupplier clock) {
            this.id = id; this.world = world; this.x = x; this.z = z; this.clock = clock;
            cellX = (int) Math.floor(x / 16); cellZ = (int) Math.floor(z / 16);
        }

        // Port
        @Override public String dimension() { return world.dimension; }
        @Override public double[] position() { return new double[]{x, 64, z}; }
        @Override public boolean isLoaded(int cx, int cz) {
            return online && world.exists(cx, cz) && Math.max(Math.abs(cx - cellX), Math.abs(cz - cellZ)) <= world.view;
        }
        @Override public void goTo(double gx, double gz) { plan(gx, gz); }
        @Override public boolean moving() { return !path.isEmpty(); }
        @Override public void stopMoving() { path.clear(); }
        @Override public int viewRadius() { return world.view; }
        @Override public long nowMs() { return clock.getAsLong(); }

        /** Best-effort path (like a goal in water): to the goal cell if reachable, else to the reachable cell nearest it. */
        void plan(double gx, double gz) {
            path.clear();
            int gcx = Math.max(world.minCx, Math.min(world.maxCx, (int) Math.floor(gx / 16)));
            int gcz = Math.max(world.minCz, Math.min(world.maxCz, (int) Math.floor(gz / 16)));
            Dijkstra d = new Dijkstra(world, cellX, cellZ);
            int tx = gcx, tz = gcz;
            boolean exact = d.reachable(gcx, gcz);
            if (!exact) {
                double best = INF;
                for (int cx = world.minCx; cx <= world.maxCx; cx++) {
                    for (int cz = world.minCz; cz <= world.maxCz; cz++) {
                        if (!d.reachable(cx, cz)) continue;
                        double e = Math.hypot(cx - gcx, cz - gcz);
                        if (e < best || (e == best && d.dist(cx, cz) < d.dist(tx, tz))) { best = e; tx = cx; tz = cz; }
                    }
                }
            }
            for (int[] c : d.route(tx, tz)) path.add(new double[]{c[0] * 16 + 8, c[1] * 16 + 8});
            if (exact) { path.pollLast(); path.add(new double[]{gx, gz}); }
        }

        /** Advances dt seconds. @return true if the bot entered a new chunk. */
        boolean step(double dt) {
            if (!online) return false;
            double budget = dt;
            boolean entered = false;
            while (budget > 1e-9 && !path.isEmpty()) {
                double[] w = path.peekFirst();
                double speed = SPEED / Math.max(1.0, world.cost(cellX, cellZ));
                double dist = Math.hypot(w[0] - x, w[1] - z);
                double reach = speed * budget;
                double move = Math.min(dist, reach);
                if (dist > 1e-9) { x += (w[0] - x) / dist * move; z += (w[1] - z) / dist * move; }
                travelSinceEvent += move;
                totalTravel += move;
                budget -= move / speed;
                if (move >= dist - 1e-9) path.pollFirst();
                int ncx = (int) Math.floor(x / 16), ncz = (int) Math.floor(z / 16);
                if (ncx != cellX || ncz != cellZ) { cellX = ncx; cellZ = ncz; entered = true; }
                if (move < dist - 1e-9) break;
            }
            return entered;
        }
    }

    static final class Dijkstra {
        final World w;
        final double[][] dist;
        final int[][] prev;
        final int sx, sz;

        Dijkstra(World w, int sx, int sz) {
            this.w = w; this.sx = sx; this.sz = sz;
            int W = w.maxCx - w.minCx + 1, H = w.maxCz - w.minCz + 1;
            dist = new double[W][H];
            prev = new int[W][H];
            for (double[] r : dist) java.util.Arrays.fill(r, INF);
            for (int[] r : prev) java.util.Arrays.fill(r, -1);
            PriorityQueue<double[]> q = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
            if (w.exists(sx, sz)) { dist[sx - w.minCx][sz - w.minCz] = 0; q.add(new double[]{0, sx, sz}); }
            while (!q.isEmpty()) {
                double[] t = q.poll();
                int cx = (int) t[1], cz = (int) t[2];
                if (t[0] > dist[cx - w.minCx][cz - w.minCz]) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = cx + dx, nz = cz + dz;
                        if (!w.passable(nx, nz)) continue;
                        if (dx != 0 && dz != 0 && (!w.passable(cx + dx, cz) || !w.passable(cx, cz + dz))) continue; // no corner cutting
                        double step = 16 * Math.hypot(dx, dz) * (w.cost(cx, cz) + w.cost(nx, nz)) / 2;
                        double nd = t[0] + step;
                        if (nd < dist[nx - w.minCx][nz - w.minCz]) {
                            dist[nx - w.minCx][nz - w.minCz] = nd;
                            prev[nx - w.minCx][nz - w.minCz] = (cx - w.minCx) * 10_000 + (cz - w.minCz);
                            q.add(new double[]{nd, nx, nz});
                        }
                    }
                }
            }
        }

        boolean reachable(int cx, int cz) { return w.exists(cx, cz) && dist[cx - w.minCx][cz - w.minCz] < INF; }
        double dist(int cx, int cz) { return w.exists(cx, cz) ? dist[cx - w.minCx][cz - w.minCz] : INF; }

        /** Cells from the one after the source up to the target. */
        List<int[]> route(int tx, int tz) {
            List<int[]> out = new ArrayList<>();
            int cx = tx, cz = tz;
            while (!(cx == sx && cz == sz)) {
                out.add(0, new int[]{cx, cz});
                int p = prev[cx - w.minCx][cz - w.minCz];
                if (p < 0) return new ArrayList<>();
                cx = p / 10_000 + w.minCx;
                cz = p % 10_000 + w.minCz;
            }
            return out;
        }
    }

    // ---- oracle: ground truth about what was observed, independent of the system under test ----

    static final class Oracle {
        final World world;
        final SurveyArea area;
        final SurveyMetrics meter;
        final Set<Long> observed = new HashSet<>();           // in-area chunks seen by anyone
        final Map<String, Set<Long>> byBot = new LinkedHashMap<>();
        final long t0;

        Oracle(World world, SurveyArea area, long t0) {
            this.world = world; this.area = area; this.t0 = t0;
            this.meter = new SurveyMetrics(area.chunkCount(), t0);
        }

        void enter(Bot b, int fromCx, int fromCz, boolean first, long now) {
            long fresh = 0, dup = 0;
            Set<Long> mine = byBot.computeIfAbsent(b.id, k -> new HashSet<>());
            for (int cx = b.cellX - world.view; cx <= b.cellX + world.view; cx++) {
                for (int cz = b.cellZ - world.view; cz <= b.cellZ + world.view; cz++) {
                    if (!world.exists(cx, cz)) continue;
                    b.everLoaded.add(SurveyGrid.pack(cx, cz));
                    boolean wasInOld = !first && Math.max(Math.abs(cx - fromCx), Math.abs(cz - fromCz)) <= world.view;
                    if (wasInOld || !area.contains(cx, cz)) continue;     // only the newly exposed ring counts for this event
                    long k = SurveyGrid.pack(cx, cz);
                    mine.add(k);
                    if (observed.add(k)) fresh++; else dup++;
                }
            }
            meter.leg(b.travelSinceEvent, fresh, dup);
            b.travelSinceEvent = 0;
            meter.observed(now, fresh, dup);
        }

        double coverage() { return meter.coverage(); }
    }

    /** Chunks of the area that some passable cell can see: the feasible set. */
    static Set<Long> feasible(World w, SurveyArea area, int startCx, int startCz) {
        Dijkstra d = new Dijkstra(w, startCx, startCz);
        Set<Long> out = new HashSet<>();
        for (int cx = w.minCx; cx <= w.maxCx; cx++) {
            for (int cz = w.minCz; cz <= w.maxCz; cz++) {
                if (!d.reachable(cx, cz)) continue;
                for (int ax = cx - w.view; ax <= cx + w.view; ax++) {
                    for (int az = cz - w.view; az <= cz + w.view; az++) if (area.contains(ax, az)) out.add(SurveyGrid.pack(ax, az));
                }
            }
        }
        return out;
    }

    // ---- results ----

    record Run(String arm, double t95Sec, double dupTravelAt95, double offAreaAt95, double totalTravelAt95,
               double tFullSec, double dupTravelFull, double offAreaFull, double totalTravelFull, double finalCoverage) {}

    /** Snapshot of the oracle meter when 95% is first reached. */
    static final class Cutoff {
        double t95 = -1, dup95, off95, tot95;
        void check(Oracle o, long now) {
            if (t95 < 0 && o.meter.coverage() >= 0.95 - 1e-9) {
                t95 = (now - o.t0) / 1000.0;
                dup95 = o.meter.duplicateTravel(); off95 = o.meter.offAreaTravel(); tot95 = o.meter.totalTravel();
            }
        }
    }

    // ---- arm B: the existing ExploreProcess, one per bot, each from its own origin ----

    /**
     * Port of ExploreProcess.closestUncachedChunks / onTick for the A/B baseline: every bot spirals outward (Manhattan
     * distance) from its own start chunk over the chunks ITS OWN cache has not seen, and walks to the nearest of the ten
     * closest. It knows nothing about the marked area or the other bot. exploreChunkSetMinimumSize=10 and
     * worldExploringChunkOffset=0 are the defaults of this tree.
     */
    static final class BaselineExplore {
        final Bot bot;
        final int ox, oz;
        int distanceCompleted;
        long[] currentGoal;
        boolean finished;

        BaselineExplore(Bot bot) { this.bot = bot; ox = bot.cellX; oz = bot.cellZ; }

        void tick() {
            if (finished) return;
            World w = bot.world;
            int count = 10;
            List<long[]> goals = new ArrayList<>();
            int maxDist = (w.maxCx - w.minCx) + (w.maxCz - w.minCz) + 2;
            for (int dist = distanceCompleted; dist <= maxDist; dist++) {
                for (int dx = -dist; dx <= dist; dx++) {
                    int zval = dist - Math.abs(dx);
                    for (int mult = 0; mult < 2; mult++) {
                        int dz = (mult * 2 - 1) * zval;
                        int cx = ox + dx, cz = oz + dz;
                        if (!w.exists(cx, cz)) continue;                          // nothing to load there
                        if (bot.everLoaded.contains(SurveyGrid.pack(cx, cz))) continue; // its own cache already has it
                        goals.add(new long[]{cx, cz});
                        if (zval == 0) break;                                      // dz = -0 and +0 are the same chunk
                    }
                }
                if (goals.size() >= count) break;
                if (goals.isEmpty()) distanceCompleted = dist + 1;
            }
            if (goals.isEmpty()) { finished = true; bot.stopMoving(); return; }
            // GoalComposite: head for whichever goal is cheapest to path to right now (best effort when unreachable)
            Dijkstra d = new Dijkstra(w, bot.cellX, bot.cellZ);
            long[] best = null;
            double bd = INF;
            for (long[] g : goals) {
                double dd = d.reachable((int) g[0], (int) g[1]) ? d.dist((int) g[0], (int) g[1]) : INF;
                if (dd < bd) { bd = dd; best = g; }
            }
            if (best == null) { // none reachable: closest by straight line, as a partial path would
                double e = INF;
                for (long[] g : goals) { double ee = Math.hypot(g[0] - bot.cellX, g[1] - bot.cellZ); if (ee < e) { e = ee; best = g; } }
            }
            if (currentGoal == null || currentGoal[0] != best[0] || currentGoal[1] != best[1] || !bot.moving()) {
                currentGoal = best;
                bot.plan(best[0] * 16 + 8, best[1] * 16 + 8);
            }
        }
    }

    /**
     * Ablation, not the requested comparison: bounded to the marked area and nearest-first like the survey scout, but each
     * bot works alone from its own cache with no leader. Separates "knows the area" from "coordinates with the other bot".
     */
    static final class IndependentBounded {
        final Bot bot;
        long[] currentGoal;

        IndependentBounded(Bot bot) { this.bot = bot; }

        void tick(SurveyArea area) {
            long[] best = null;
            double bd = INF;
            for (int cx = area.minCx(); cx <= area.maxCx(); cx++) {
                for (int cz = area.minCz(); cz <= area.maxCz(); cz++) {
                    if (bot.everLoaded.contains(SurveyGrid.pack(cx, cz))) continue;
                    double d = Math.hypot(cx * 16 + 8 - bot.x, cz * 16 + 8 - bot.z);
                    if (d < bd) { bd = d; best = new long[]{cx, cz}; }
                }
            }
            if (best == null) { bot.stopMoving(); return; }
            if (currentGoal == null || currentGoal[0] != best[0] || currentGoal[1] != best[1] || !bot.moving()) {
                currentGoal = best;
                double tx = best[0] * 16 + 8, tz = best[1] * 16 + 8;
                double dist = Math.hypot(tx - bot.x, tz - bot.z), stand = (bot.world.view - 1) * 16.0;
                if (dist > stand + 8) bot.plan(tx - (tx - bot.x) / dist * stand, tz - (tz - bot.z) / dist * stand);
                else bot.plan(tx, tz);
            }
        }
    }

    static Run runIndependentBounded(Scenario sc, long maxMs) {
        long[] clock = {1_000};
        Oracle o = new Oracle(sc.world, sc.area, clock[0]);
        List<Bot> bots = new ArrayList<>();
        List<IndependentBounded> ex = new ArrayList<>();
        for (double[] s : sc.starts) {
            Bot b = new Bot("bot" + bots.size(), sc.world, s[0], s[1], () -> clock[0]);
            bots.add(b);
            o.enter(b, 0, 0, true, clock[0]);
            ex.add(new IndependentBounded(b));
        }
        Cutoff cut = new Cutoff();
        double tFull = -1;
        while (clock[0] - 1_000 < maxMs) {
            for (IndependentBounded e : ex) e.tick(sc.area);
            clock[0] += DT_MS;
            for (Bot b : bots) {
                int fx = b.cellX, fz = b.cellZ;
                if (b.step(DT_MS / 1000.0)) o.enter(b, fx, fz, false, clock[0]);
            }
            cut.check(o, clock[0]);
            if (o.coverage() >= 1.0 - 1e-9) { tFull = (clock[0] - o.t0) / 1000.0; break; }
        }
        return new Run("independent-bounded", cut.t95, cut.dup95, cut.off95, cut.tot95, tFull,
                o.meter.duplicateTravel(), o.meter.offAreaTravel(), o.meter.totalTravel(), o.coverage());
    }

    static Run runBaseline(Scenario sc, long maxMs) {
        long[] clock = {1_000};
        Oracle o = new Oracle(sc.world, sc.area, clock[0]);
        List<Bot> bots = new ArrayList<>();
        List<BaselineExplore> ex = new ArrayList<>();
        for (double[] s : sc.starts) {
            Bot b = new Bot("bot" + bots.size(), sc.world, s[0], s[1], () -> clock[0]);
            bots.add(b);
            o.enter(b, 0, 0, true, clock[0]);
            ex.add(new BaselineExplore(b));
        }
        Cutoff cut = new Cutoff();
        double tFull = -1, dupFull = 0, offFull = 0, totFull = 0;
        while (clock[0] - 1_000 < maxMs) {
            for (BaselineExplore e : ex) e.tick();
            clock[0] += DT_MS;
            for (Bot b : bots) {
                int fx = b.cellX, fz = b.cellZ;
                if (b.step(DT_MS / 1000.0)) o.enter(b, fx, fz, false, clock[0]);
            }
            cut.check(o, clock[0]);
            if (o.coverage() >= 1.0 - 1e-9) { tFull = (clock[0] - o.t0) / 1000.0; break; }
        }
        dupFull = o.meter.duplicateTravel(); offFull = o.meter.offAreaTravel(); totFull = o.meter.totalTravel();
        return new Run("baseline-explore", cut.t95, cut.dup95, cut.off95, cut.tot95, tFull, dupFull, offFull, totFull, o.coverage());
    }

    // ---- arm A: coordinated survey through the real SwarmCoordinator / SwarmWorker / SurveyScout ----

    static final class Coordinated {
        final Scenario sc;
        final long[] clock = {1_000};
        final Oracle oracle;
        final List<Bot> bots = new ArrayList<>();
        final Map<String, SwarmWorker> workers = new LinkedHashMap<>();
        final Set<String> offline = new HashSet<>();
        final SwarmCoordinator leader;
        final SurveyBoard board;
        final Cutoff cut = new Cutoff();

        final long heartbeatMs;
        int messagesToLeader, messagesToAgents;

        Coordinated(Scenario sc, SwarmCoordinator.Config cfg, int bots) { this(sc, cfg, bots, 2_000); }

        Coordinated(Scenario sc, SwarmCoordinator.Config cfg, int bots, long heartbeatMs) {
            this.sc = sc;
            this.heartbeatMs = heartbeatMs;
            oracle = new Oracle(sc.world, sc.area, clock[0]);
            leader = new SwarmCoordinator((agent, m) -> {
                messagesToAgents++;
                SwarmWorker w = workers.get(agent);
                if (w != null && !offline.contains(agent)) w.onMessage(SwarmMessage.decode(m.encode()), clock[0]);
            }, cfg);
            for (int i = 0; i < bots; i++) {
                double[] s = sc.starts.get(i);
                Bot b = new Bot("scout" + (char) ('A' + i), sc.world, s[0], s[1], () -> clock[0]);
                this.bots.add(b);
                oracle.enter(b, 0, 0, true, clock[0]);
                SurveyScout scout = new SurveyScout(b);
                String id = b.id;
                SwarmWorker w = new SwarmWorker(id, EnumSet.of(Capability.CAN_SCOUT), scout,
                        m -> { if (!offline.contains(id)) { messagesToLeader++; leader.onMessage(id, SwarmMessage.decode(m.encode()), clock[0]); } }, heartbeatMs);
                workers.put(id, w);
                w.register(clock[0]);
            }
            board = leader.startSurvey(sc.area, clock[0]);
        }

        void step() {
            clock[0] += DT_MS;
            for (Bot b : bots) {
                int fx = b.cellX, fz = b.cellZ;
                if (b.step(DT_MS / 1000.0)) oracle.enter(b, fx, fz, false, clock[0]);
            }
            for (Map.Entry<String, SwarmWorker> e : workers.entrySet()) if (!offline.contains(e.getKey())) e.getValue().tick(clock[0]);
            leader.tick(clock[0]);
            cut.check(oracle, clock[0]);
        }

        /** Drops a scout with no goodbye: it stops moving, sending and hearing. */
        void disconnect(String id) {
            offline.add(id);
            for (Bot b : bots) if (b.id.equals(id)) { b.online = false; b.path.clear(); }
        }

        Run run(long maxMs, boolean stopAtBoardDone) {
            double tFull = -1;
            while (clock[0] - oracle.t0 < maxMs) {
                step();
                if (oracle.coverage() >= 1.0 - 1e-9 && tFull < 0) tFull = (clock[0] - oracle.t0) / 1000.0;
                if (stopAtBoardDone ? board.done() : tFull >= 0) break;
            }
            SurveyMetrics m = oracle.meter;
            return new Run("coordinated-survey", cut.t95, cut.dup95, cut.off95, cut.tot95, tFull,
                    m.duplicateTravel(), m.offAreaTravel(), m.totalTravel(), oracle.coverage());
        }
    }

    // ---- scenarios ----

    record Scenario(String name, World world, SurveyArea area, List<double[]> starts) {}

    /** Start positions are in block coordinates, one per bot, at opposite ends of the marked area. */
    static Scenario scenario(String name, int areaMinCx, int areaMinCz, int w, int h, int view, int margin,
                             IntBinaryOperator cost, double[]... starts) {
        SurveyArea area = new SurveyArea("overworld", areaMinCx, areaMinCz, areaMinCx + w - 1, areaMinCz + h - 1);
        World world = new World("overworld", areaMinCx - margin, areaMinCz - margin, areaMinCx + w - 1 + margin, areaMinCz + h - 1 + margin, view, cost);
        return new Scenario(name, world, area, List.of(starts));
    }

    static double[] at(int cx, int cz) { return new double[]{cx * 16 + 8, cz * 16 + 8}; }

    static List<Scenario> fiveScenarios(int view) {
        List<Scenario> out = new ArrayList<>();
        // 1. open flat ground, bots start at opposite corners of the marked area
        out.add(scenario("open-flat 24x24", 0, 0, 24, 24, view, 10, (x, z) -> 10, at(0, 0), at(23, 23)));
        // 2. a long thin strip, bots start at the two ends
        out.add(scenario("strip 40x10", 0, 0, 40, 10, view, 10, (x, z) -> 10, at(0, 5), at(39, 5)));
        // 3. a two-chunk-wide river across the middle with one ford; bots start on opposite banks
        out.add(scenario("river-with-ford 32x24", 0, 0, 32, 24, view, 10,
                (x, z) -> (x == 15 || x == 16) ? ((z >= 11 && z <= 12) ? 15 : -1) : 10, at(2, 12), at(29, 12)));
        // 4. a lake in the middle and a band of slow hills; bots start at opposite corners
        out.add(scenario("lake-and-hills 28x28", 0, 0, 28, 28, view, 10,
                (x, z) -> Math.hypot(x - 13.5, z - 13.5) <= 4.2 ? -1 : (z >= 18 && z <= 22 ? 25 : 10), at(0, 27), at(27, 0)));
        // 5. dense forest on one half (slow), open on the other; bots start at opposite ends
        out.add(scenario("forest-half 20x32", 0, 0, 20, 32, view, 10, (x, z) -> z < 16 ? 18 : 10, at(10, 0), at(10, 31)));
        return out;
    }
}
