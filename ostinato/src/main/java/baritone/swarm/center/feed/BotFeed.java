package baritone.swarm.center.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1, {@code bots/<name>.json}. What one bot says about itself, written by that bot. Unknown
 * numbers are -1, unknown strings are empty, and a bot that is not in a world has a null {@link #pos}. Lists are
 * bounded by the writer ({@link #MAX_NODES}, {@link #MAX_EXECUTIONS}, {@link #MAX_ITEMS}).
 *
 * @param name         roster member name (also the file name)
 * @param server       server address as the client knows it, or empty
 * @param runMode      visible, hidden or headless
 * @param commandHost  this bot serves the command channel ({@code host.json})
 * @param coordinator  this bot is the lease coordinator ({@code coordinator.json})
 * @param process      the Baritone process in control (its display name), or {@code idle}
 * @param gatherUs     what building this feed cost on the client thread, in microseconds (self-measured)
 */
public record BotFeed(String name, long writtenAtMs, long seq, String server, String runMode, boolean commandHost,
                      boolean coordinator, Pos pos, int health, int maxHealth, int hunger, boolean dead, String process,
                      Route path, Inventory inventory, List<Execution> executions, List<HeldLease> leases, Perf perf,
                      Link link, long gatherUs, String role, String roleState,
                      WhyStuck whyStuck, List<Danger> dangers, List<TaskItem> tasks, Speedrun speedrun,
                      LinkBudget linkBudget, ViewCap viewCap) {

    public static final int MAX_NODES = 32;
    public static final int MAX_EXECUTIONS = 8;
    public static final int MAX_ITEMS = 4;
    public static final int MAX_DANGERS = 16;
    public static final int MAX_TASKS = 16;

    public BotFeed {
        name = name == null ? "" : name;
        server = server == null ? "" : server;
        runMode = runMode == null ? "" : runMode;
        process = process == null || process.isBlank() ? "idle" : process;
        path = path == null ? Route.IDLE : path;
        inventory = inventory == null ? Inventory.UNKNOWN : inventory;
        executions = executions == null ? List.of() : List.copyOf(executions);
        leases = leases == null ? List.of() : List.copyOf(leases);
        perf = perf == null ? Perf.UNKNOWN : perf;
        link = link == null ? Link.DOWN : link;
        role = role == null || role.isBlank() ? "idle" : role;
        roleState = roleState == null ? "" : roleState;
        whyStuck = whyStuck == null ? WhyStuck.NONE : whyStuck;
        dangers = dangers == null ? List.of() : List.copyOf(dangers);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        speedrun = speedrun == null ? Speedrun.NONE : speedrun;
        linkBudget = linkBudget == null ? LinkBudget.NONE : linkBudget;
        viewCap = viewCap == null ? ViewCap.NONE : viewCap;
    }

    /** Ostinato: the pre-RTS shape; a feed without a role is idle. */
    public BotFeed(String name, long writtenAtMs, long seq, String server, String runMode, boolean commandHost,
                   boolean coordinator, Pos pos, int health, int maxHealth, int hunger, boolean dead, String process,
                   Route path, Inventory inventory, List<Execution> executions, List<HeldLease> leases, Perf perf,
                   Link link, long gatherUs) {
        this(name, writtenAtMs, seq, server, runMode, commandHost, coordinator, pos, health, maxHealth, hunger, dead,
                process, path, inventory, executions, leases, perf, link, gatherUs, "idle", "",
                WhyStuck.NONE, List.of(), List.of(), Speedrun.NONE, LinkBudget.NONE, ViewCap.NONE);
    }

    /** Ostinato: RTS shape without optional Phase 3/4 diagnostic extensions. */
    public BotFeed(String name, long writtenAtMs, long seq, String server, String runMode, boolean commandHost,
                   boolean coordinator, Pos pos, int health, int maxHealth, int hunger, boolean dead, String process,
                   Route path, Inventory inventory, List<Execution> executions, List<HeldLease> leases, Perf perf,
                   Link link, long gatherUs, String role, String roleState) {
        this(name, writtenAtMs, seq, server, runMode, commandHost, coordinator, pos, health, maxHealth, hunger, dead,
                process, path, inventory, executions, leases, perf, link, gatherUs, role, roleState,
                WhyStuck.NONE, List.of(), List.of(), Speedrun.NONE, LinkBudget.NONE, ViewCap.NONE);
    }

    /** Where the bot stands. {@code dim} is o, n or e (the reservation-key letters), or ? for another dimension. */
    public record Pos(char dim, double x, double y, double z, float yaw) {
        public int blockX() {
            return (int) Math.floor(x);
        }

        public int blockY() {
            return (int) Math.floor(y);
        }

        public int blockZ() {
            return (int) Math.floor(z);
        }

        Map<String, Object> toJson() {
            return Json.map("dim", String.valueOf(dim), "x", round(x), "y", round(y), "z", round(z), "yaw", round(yaw));
        }

        static Pos fromJson(Map<String, Object> m) {
            if (m == null) {
                return null;
            }
            String d = Json.str(m, "dim", "?");
            return new Pos(d.isEmpty() ? '?' : d.charAt(0), Json.dbl(m, "x", 0), Json.dbl(m, "y", 0),
                    Json.dbl(m, "z", 0), (float) Json.dbl(m, "yaw", 0));
        }
    }

    /**
     * The path the bot is following. {@code state} is idle, calculating, pathing or waiting (PathStatus). {@code goal}
     * is the goal's own text, {@code goalPos} its block when it has one. {@code nodes} are the next path positions
     * from where the bot stands, at most {@link #MAX_NODES}.
     */
    public record Route(String state, String goal, int[] goalPos, double distance, int position, int length,
                       List<int[]> nodes) {
        public static final Route IDLE = new Route("idle", "", null, -1, 0, 0, List.of());

        public Route {
            state = state == null || state.isBlank() ? "idle" : state;
            goal = goal == null ? "" : goal;
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }

        Map<String, Object> toJson() {
            return Json.map("state", state, "goal", goal, "goalPos", goalPos, "distance", round(distance),
                    "position", position, "length", length, "nodes", nodes);
        }

        static Route fromJson(Map<String, Object> m) {
            if (m == null) {
                return IDLE;
            }
            List<int[]> nodes = new ArrayList<>();
            Object raw = m.get("nodes");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof List<?> xyz && xyz.size() == 3 && xyz.stream().allMatch(Number.class::isInstance)) {
                        nodes.add(new int[]{((Number) xyz.get(0)).intValue(), ((Number) xyz.get(1)).intValue(),
                                ((Number) xyz.get(2)).intValue()});
                    }
                }
            }
            int[] goalPos = Json.ints(m, "goalPos");
            return new Route(Json.str(m, "state", "idle"), Json.str(m, "goal", ""),
                    goalPos != null && goalPos.length == 3 ? goalPos : null, Json.dbl(m, "distance", -1),
                    Json.i32(m, "position", 0), Json.i32(m, "length", 0), nodes);
        }
    }

    /**
     * Inventory summary (the owner's choice for a tile): free main slots out of the total, food items carried, the
     * {@link #MAX_ITEMS} largest stacks by item, every distinct item in {@code counts} (capped at 64), and the lowest
     * durability of any tool or weapon carried, as a percent (-1 when none). Feeds written before {@code counts}
     * are read as if the top stacks were the whole bill.
     */
    public record Inventory(int freeSlots, int totalSlots, int food, List<Item> top, int lowestToolPct,
                            String lowestTool, List<Item> counts) {
        public static final Inventory UNKNOWN = new Inventory(-1, -1, -1, List.of(), -1, "", List.of());

        /** Older callers, and feeds written before {@code counts}: the reported top stacks stand in for the bill. */
        public Inventory(int freeSlots, int totalSlots, int food, List<Item> top, int lowestToolPct, String lowestTool) {
            this(freeSlots, totalSlots, food, top, lowestToolPct, lowestTool, top);
        }

        public Inventory {
            top = top == null ? List.of() : List.copyOf(top);
            lowestTool = lowestTool == null ? "" : lowestTool;
            counts = counts == null ? List.copyOf(top) : List.copyOf(counts);
        }

        Map<String, Object> toJson() {
            List<Object> items = new ArrayList<>();
            for (Item i : top) {
                items.add(Json.map("id", i.id(), "count", i.count()));
            }
            List<Object> all = new ArrayList<>();
            for (Item i : counts) {
                all.add(Json.map("id", i.id(), "count", i.count()));
            }
            return Json.map("freeSlots", freeSlots, "totalSlots", totalSlots, "food", food, "top", items,
                    "lowestToolPct", lowestToolPct, "lowestTool", lowestTool, "counts", all);
        }

        static Inventory fromJson(Map<String, Object> m) {
            if (m == null) {
                return UNKNOWN;
            }
            List<Item> items = new ArrayList<>();
            for (Map<String, Object> i : Json.objs(m, "top")) {
                items.add(new Item(Json.str(i, "id", "?"), Json.i32(i, "count", 0)));
            }
            List<Item> counts = new ArrayList<>();
            if (m.containsKey("counts")) {
                for (Map<String, Object> i : Json.objs(m, "counts")) {
                    counts.add(new Item(Json.str(i, "id", "?"), Json.i32(i, "count", 0)));
                }
            } else {
                counts.addAll(items);
            }
            return new Inventory(Json.i32(m, "freeSlots", -1), Json.i32(m, "totalSlots", -1), Json.i32(m, "food", -1),
                    items, Json.i32(m, "lowestToolPct", -1), Json.str(m, "lowestTool", ""), counts);
        }
    }

    /** One item id (namespace dropped when it is minecraft) and how many the bot carries in total. */
    public record Item(String id, int count) {}

    /**
     * One swarm command this bot knows about (its command-protocol ledger). {@code execution} is accepted, running,
     * succeeded, failed or canceled; {@code observation} is confirmed, stale or unknown (lower-case enum names, not the
     * display labels).
     */
    public record Execution(String id, String command, String issuer, String execution, String observation, long ageMs,
                            String endReason) {
        public Execution {
            id = id == null ? "" : id;
            command = command == null ? "" : command;
            issuer = issuer == null ? "" : issuer;
            execution = execution == null ? "" : execution;
            observation = observation == null ? "unknown" : observation;
            endReason = endReason == null ? "" : endReason;
        }

        Map<String, Object> toJson() {
            return Json.map("id", id, "command", command, "issuer", issuer, "execution", execution,
                    "observation", observation, "ageMs", ageMs, "endReason", endReason);
        }

        static Execution fromJson(Map<String, Object> m) {
            return new Execution(Json.str(m, "id", ""), Json.str(m, "command", ""), Json.str(m, "issuer", ""),
                    Json.str(m, "execution", ""), Json.str(m, "observation", "unknown"), Json.num(m, "ageMs", -1),
                    Json.str(m, "endReason", ""));
        }
    }

    /** A lease this bot holds or is being offered (its lease guard). {@code phase} is the guard's lower-case phase. */
    public record HeldLease(String id, long gen, String resource, String phase, long leftMs, boolean active) {
        Map<String, Object> toJson() {
            return Json.map("id", id, "gen", gen, "resource", resource, "phase", phase, "leftMs", leftMs,
                    "active", active);
        }

        static HeldLease fromJson(Map<String, Object> m) {
            return new HeldLease(Json.str(m, "id", ""), Json.num(m, "gen", 0), Json.str(m, "resource", ""),
                    Json.str(m, "phase", ""), Json.num(m, "leftMs", -1), Json.bool(m, "active", false));
        }
    }

    /** The performance strip figures (SwarmSample), with the same -1 for unknown. */
    public record Perf(long pathMs, int tpsX10, int pingMs, int ramUsedMb, int ramMaxMb, int chatUsed, int chatCap,
                       long dropped, long retried) {
        public static final Perf UNKNOWN = new Perf(-1, -1, -1, -1, -1, -1, -1, -1, -1);

        Map<String, Object> toJson() {
            return Json.map("pathMs", pathMs, "tpsX10", tpsX10, "pingMs", pingMs, "ramUsedMb", ramUsedMb,
                    "ramMaxMb", ramMaxMb, "chatUsed", chatUsed, "chatCap", chatCap, "dropped", dropped,
                    "retried", retried);
        }

        static Perf fromJson(Map<String, Object> m) {
            if (m == null) {
                return UNKNOWN;
            }
            return new Perf(Json.num(m, "pathMs", -1), Json.i32(m, "tpsX10", -1), Json.i32(m, "pingMs", -1),
                    Json.i32(m, "ramUsedMb", -1), Json.i32(m, "ramMaxMb", -1), Json.i32(m, "chatUsed", -1),
                    Json.i32(m, "chatCap", -1), Json.num(m, "dropped", -1), Json.num(m, "retried", -1));
        }
    }

    /** The swarm link: whether it is up, peers heard from in the last 30 s, and the start failure if it is down. */
    public record Link(boolean up, int peersSeen, String failure) {
        public static final Link DOWN = new Link(false, 0, "");

        public Link {
            failure = failure == null ? "" : failure;
        }

        Map<String, Object> toJson() {
            return Json.map("up", up, "peersSeen", peersSeen, "failure", failure);
        }

        static Link fromJson(Map<String, Object> m) {
            return m == null ? DOWN : new Link(Json.bool(m, "up", false), Json.i32(m, "peersSeen", 0),
                    Json.str(m, "failure", ""));
        }
    }

    /** Additive v1: Why-stuck diagnostics from PathFailureLog and BuilderProcess stallDebug. */
    public record WhyStuck(String lastGoal, int failCount, String reason, int triedNodes, long lastFailAgeMs,
                           String stallDetail) {
        public static final WhyStuck NONE = new WhyStuck("", 0, "", 0, -1, "");

        public WhyStuck {
            lastGoal = lastGoal == null ? "" : lastGoal;
            reason = reason == null ? "" : reason;
            stallDetail = stallDetail == null ? "" : stallDetail;
        }

        public boolean isEmpty() {
            return failCount <= 0 && reason.isEmpty() && stallDetail.isEmpty() && lastGoal.isEmpty();
        }

        Map<String, Object> toJson() {
            return Json.map("lastGoal", lastGoal, "failCount", failCount, "reason", reason, "triedNodes", triedNodes,
                    "lastFailAgeMs", lastFailAgeMs, "stallDetail", stallDetail);
        }

        static WhyStuck fromJson(Map<String, Object> m) {
            if (m == null) {
                return NONE;
            }
            return new WhyStuck(Json.str(m, "lastGoal", ""), Json.i32(m, "failCount", 0), Json.str(m, "reason", ""),
                    Json.i32(m, "triedNodes", 0), Json.num(m, "lastFailAgeMs", -1), Json.str(m, "stallDetail", ""));
        }
    }

    /** Additive v1: Nearby hostile mob, non-swarm player, lava, or fall hazard for the Danger overlay. */
    public record Danger(String kind, String name, double x, double y, double z, double distance, String severity) {
        public Danger {
            kind = kind == null ? "" : kind;
            name = name == null ? "" : name;
            severity = severity == null || severity.isBlank() ? "warn" : severity;
        }

        Map<String, Object> toJson() {
            return Json.map("kind", kind, "name", name, "x", round(x), "y", round(y), "z", round(z),
                    "distance", round(distance), "severity", severity);
        }

        static Danger fromJson(Map<String, Object> m) {
            return new Danger(Json.str(m, "kind", ""), Json.str(m, "name", ""), Json.dbl(m, "x", 0),
                    Json.dbl(m, "y", 0), Json.dbl(m, "z", 0), Json.dbl(m, "distance", -1),
                    Json.str(m, "severity", "warn"));
        }
    }

    /** Additive v1: One step in this bot's active task queue. */
    public record TaskItem(int index, String type, String label, String state) {
        public TaskItem {
            type = type == null ? "" : type;
            label = label == null ? "" : label;
            state = state == null ? "queued" : state;
        }

        Map<String, Object> toJson() {
            return Json.map("index", index, "type", type, "label", label, "state", state);
        }

        static TaskItem fromJson(Map<String, Object> m) {
            return new TaskItem(Json.i32(m, "index", 0), Json.str(m, "type", ""), Json.str(m, "label", ""),
                    Json.str(m, "state", "queued"));
        }
    }

    /** Additive v1: Live TenorClef speedrun splits vs personal bests with ticks per phase. */
    public record Speedrun(String phase, long elapsedMs, long elapsedTicks, long totalPbMs, List<Split> splits) {
        public static final Speedrun NONE = new Speedrun("", 0, 0, -1, List.of());

        public record Split(String name, long ms, long ticks, long pbMs) {
            public Split {
                name = name == null ? "" : name;
            }

            Map<String, Object> toJson() {
                return Json.map("name", name, "ms", ms, "ticks", ticks, "pbMs", pbMs);
            }

            static Split fromJson(Map<String, Object> m) {
                return new Split(Json.str(m, "name", ""), Json.num(m, "ms", 0), Json.num(m, "ticks", 0),
                        Json.num(m, "pbMs", -1));
            }
        }

        public Speedrun {
            phase = phase == null ? "" : phase;
            splits = splits == null ? List.of() : List.copyOf(splits);
        }

        public boolean isEmpty() {
            return phase.isEmpty() && elapsedMs <= 0 && splits.isEmpty();
        }

        /** Parses the compact encoded string published via {@code ISwarm.publishSpeedrunSplits}. */
        public static Speedrun parseEncoded(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return NONE;
            }
            String[] parts = encoded.split("\\|", -1);
            if (parts.length < 4) {
                return NONE;
            }
            String phase = parts[0];
            long elapsedMs = parseLongSafe(parts[1], 0);
            long elapsedTicks = parseLongSafe(parts[2], 0);
            long totalPbMs = parseLongSafe(parts[3], -1);
            List<Split> list = new ArrayList<>();
            if (parts.length >= 5 && !parts[4].isBlank()) {
                for (String token : parts[4].split(",")) {
                    int eq = token.indexOf('=');
                    if (eq <= 0) continue;
                    String sName = token.substring(0, eq);
                    String[] nums = token.substring(eq + 1).split(":", -1);
                    if (nums.length >= 3) {
                        list.add(new Split(sName, parseLongSafe(nums[0], 0), parseLongSafe(nums[1], 0),
                                parseLongSafe(nums[2], -1)));
                    }
                }
            }
            return new Speedrun(phase, elapsedMs, elapsedTicks, totalPbMs, list);
        }

        private static long parseLongSafe(String s, long fallback) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        Map<String, Object> toJson() {
            List<Object> sp = new ArrayList<>();
            for (Split s : splits) {
                sp.add(s.toJson());
            }
            return Json.map("phase", phase, "elapsedMs", elapsedMs, "elapsedTicks", elapsedTicks,
                    "totalPbMs", totalPbMs, "splits", sp);
        }

        static Speedrun fromJson(Map<String, Object> m) {
            if (m == null) {
                return NONE;
            }
            List<Split> sp = new ArrayList<>();
            for (Map<String, Object> o : Json.objs(m, "splits")) {
                sp.add(Split.fromJson(o));
            }
            return new Speedrun(Json.str(m, "phase", ""), Json.num(m, "elapsedMs", 0),
                    Json.num(m, "elapsedTicks", 0), Json.num(m, "totalPbMs", -1), sp);
        }
    }

    /** Additive v1: Learned per-server chat budget & detected whisper prefix for the Swarm Link Monitor. */
    public record LinkBudget(String whisperCommand, int maxLineChars, int rateMilliPerSec, long backoffEvents) {
        public static final LinkBudget NONE = new LinkBudget("", -1, -1, 0);

        public LinkBudget {
            whisperCommand = whisperCommand == null ? "" : whisperCommand;
        }

        public boolean isEmpty() {
            return whisperCommand.isEmpty() && maxLineChars < 0 && rateMilliPerSec < 0 && backoffEvents <= 0;
        }

        Map<String, Object> toJson() {
            return Json.map("whisperCommand", whisperCommand, "maxLineChars", maxLineChars,
                    "rateMilliPerSec", rateMilliPerSec, "backoffEvents", backoffEvents);
        }

        static LinkBudget fromJson(Map<String, Object> m) {
            if (m == null) {
                return NONE;
            }
            return new LinkBudget(Json.str(m, "whisperCommand", ""), Json.i32(m, "maxLineChars", -1),
                    Json.i32(m, "rateMilliPerSec", -1), Json.num(m, "backoffEvents", 0));
        }
    }

    /** Additive v1: Bot POV switching view capability (Phase 4). */
    public record ViewCap(boolean canRenderSwap, boolean canStreamView) {
        public static final ViewCap NONE = new ViewCap(false, false);

        public boolean isEmpty() {
            return !canRenderSwap && !canStreamView;
        }

        Map<String, Object> toJson() {
            return Json.map("canRenderSwap", canRenderSwap, "canStreamView", canStreamView);
        }

        static ViewCap fromJson(Map<String, Object> m) {
            if (m == null) {
                return NONE;
            }
            return new ViewCap(Json.bool(m, "canRenderSwap", false), Json.bool(m, "canStreamView", false));
        }
    }

    // ---- codec ----------------------------------------------------------------------------------------------------

    public String toJson() {
        Map<String, Object> m = CenterFeed.envelope(CenterFeed.KIND_BOT, writtenAtMs, seq);
        m.put("name", name);
        m.put("server", server);
        m.put("runMode", runMode);
        m.put("commandHost", commandHost);
        m.put("coordinator", coordinator);
        m.put("pos", pos == null ? null : pos.toJson());
        m.put("health", health);
        m.put("maxHealth", maxHealth);
        m.put("hunger", hunger);
        m.put("dead", dead);
        m.put("process", process);
        m.put("path", path.toJson());
        m.put("inventory", inventory.toJson());
        List<Object> ex = new ArrayList<>();
        for (Execution e : executions) {
            ex.add(e.toJson());
        }
        m.put("executions", ex);
        List<Object> ls = new ArrayList<>();
        for (HeldLease l : leases) {
            ls.add(l.toJson());
        }
        m.put("leases", ls);
        m.put("perf", perf.toJson());
        m.put("link", link.toJson());
        m.put("gatherUs", gatherUs);
        m.put("role", role);
        m.put("roleState", roleState);
        if (!whyStuck.isEmpty()) {
            m.put("whyStuck", whyStuck.toJson());
        }
        if (!dangers.isEmpty()) {
            List<Object> ds = new ArrayList<>();
            for (Danger d : dangers) {
                ds.add(d.toJson());
            }
            m.put("dangers", ds);
        }
        if (!tasks.isEmpty()) {
            List<Object> ts = new ArrayList<>();
            for (TaskItem t : tasks) {
                ts.add(t.toJson());
            }
            m.put("tasks", ts);
        }
        if (!speedrun.isEmpty()) {
            m.put("speedrun", speedrun.toJson());
        }
        if (!linkBudget.isEmpty()) {
            m.put("linkBudget", linkBudget.toJson());
        }
        if (!viewCap.isEmpty()) {
            m.put("viewCap", viewCap.toJson());
        }
        return Json.write(m);
    }

    /** Throws {@link IllegalArgumentException} with {@link CenterFeed#check}'s reason for a file it must not read. */
    public static BotFeed fromJson(String text) {
        Map<String, Object> m = Json.object(text);
        String bad = CenterFeed.check(m, CenterFeed.KIND_BOT);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        List<Execution> ex = new ArrayList<>();
        for (Map<String, Object> e : Json.objs(m, "executions")) {
            ex.add(Execution.fromJson(e));
        }
        List<HeldLease> ls = new ArrayList<>();
        for (Map<String, Object> l : Json.objs(m, "leases")) {
            ls.add(HeldLease.fromJson(l));
        }
        List<Danger> ds = new ArrayList<>();
        for (Map<String, Object> d : Json.objs(m, "dangers")) {
            ds.add(Danger.fromJson(d));
        }
        List<TaskItem> ts = new ArrayList<>();
        for (Map<String, Object> t : Json.objs(m, "tasks")) {
            ts.add(TaskItem.fromJson(t));
        }
        return new BotFeed(Json.str(m, "name", ""), Json.num(m, "writtenAtMs", 0), Json.num(m, "seq", 0),
                Json.str(m, "server", ""), Json.str(m, "runMode", ""), Json.bool(m, "commandHost", false),
                Json.bool(m, "coordinator", false), Pos.fromJson(Json.obj(m, "pos")), Json.i32(m, "health", -1),
                Json.i32(m, "maxHealth", -1), Json.i32(m, "hunger", -1), Json.bool(m, "dead", false),
                Json.str(m, "process", "idle"), Route.fromJson(Json.obj(m, "path")),
                Inventory.fromJson(Json.obj(m, "inventory")), ex, ls, Perf.fromJson(Json.obj(m, "perf")),
                Link.fromJson(Json.obj(m, "link")), Json.num(m, "gatherUs", -1), Json.str(m, "role", "idle"),
                Json.str(m, "roleState", ""), WhyStuck.fromJson(Json.obj(m, "whyStuck")), ds, ts,
                Speedrun.fromJson(Json.obj(m, "speedrun")), LinkBudget.fromJson(Json.obj(m, "linkBudget")),
                ViewCap.fromJson(Json.obj(m, "viewCap")));
    }

    static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 100.0) / 100.0 : -1;
    }
}
