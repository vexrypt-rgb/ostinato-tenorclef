/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.headless;

import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.command.ArgDecl;
import baritone.api.command.CommandPermission;
import baritone.api.command.FormDecl;
import baritone.api.command.FormPlacement;
import baritone.api.context.BotContext;
import baritone.api.context.BotContextScope;
import baritone.api.headless.IHeadlessAgent;
import baritone.api.headless.IHeadlessAgentFactory;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.channel.HostInfo;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CatalogFeed;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.InventorySummary;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe fleet manager for spawning, inspecting, commanding, and stopping
 * headless bot instances sharing one JVM and {@link HeadlessTickScheduler}.
 */
public final class HeadlessFleetController {

    /** Hook for constructing live or fake {@link HeadlessBot} instances. */
    @FunctionalInterface
    public interface BotSpawner {
        SpawnedBot create(HeadlessAccount account, BotContext context, String host, int port);
    }

    public record SpawnedBot(HeadlessBot bot, HeadlessTransport transport) {
    }

    public record ManagedBot(
            String id,
            HeadlessAccount account,
            BotContext context,
            HeadlessBot bot,
            HeadlessTransport transport,
            String host,
            int port,
            Path configDir,
            long spawnedAtMs) {
    }

    private final HeadlessTickScheduler scheduler;
    private final Path configRoot;
    private final String defaultHost;
    private final int defaultPort;
    private final IHeadlessAgentFactory agentFactory;
    private final BotSpawner spawner;
    private final Map<String, ManagedBot> bots = new ConcurrentHashMap<>();
    private final Map<String, Deque<ExecutionEntry>> executions = new ConcurrentHashMap<>();
    private final HeadlessSwarmBus swarmBus = new HeadlessSwarmBus();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicLong centerSeq = new AtomicLong();
    private final long startedAtMs = System.currentTimeMillis();

    private static final class ExecutionEntry {
        final String id;
        final String command;
        final String issuer;
        volatile String execution;
        volatile String observation;
        final long startedAtMs;
        volatile String endReason;

        ExecutionEntry(String id, String command, String issuer, String execution, String observation, long startedAtMs) {
            this.id = id;
            this.command = command;
            this.issuer = issuer;
            this.execution = execution;
            this.observation = observation;
            this.startedAtMs = startedAtMs;
            this.endReason = "";
        }
    }

    public HeadlessFleetController(
            HeadlessTickScheduler scheduler,
            RegistryAccess.Frozen registries,
            EventLoopGroupHolder eventLoop,
            IHeadlessAgentFactory agentFactory,
            String defaultHost,
            int defaultPort,
            Path configRoot) {
        this(scheduler, agentFactory, defaultHost, defaultPort, configRoot,
                defaultNetworkSpawner(registries, eventLoop));
    }

    public HeadlessFleetController(
            HeadlessTickScheduler scheduler,
            IHeadlessAgentFactory agentFactory,
            String defaultHost,
            int defaultPort,
            Path configRoot,
            BotSpawner spawner) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.agentFactory = agentFactory;
        this.defaultHost = (defaultHost == null || defaultHost.isBlank()) ? "127.0.0.1" : defaultHost;
        this.defaultPort = defaultPort > 0 ? defaultPort : 25565;
        this.configRoot = configRoot != null ? configRoot : Path.of("headless-bots");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
    }

    private static BotSpawner defaultNetworkSpawner(
            RegistryAccess.Frozen registries,
            EventLoopGroupHolder eventLoop) {
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(eventLoop, "eventLoop");
        return (account, context, host, port) -> {
            Connection connection = new Connection(PacketFlow.CLIENTBOUND);
            Connection.connect(new InetSocketAddress(host, port), eventLoop, connection);
            HeadlessTransport.ConnectionTransport transport = new HeadlessTransport.ConnectionTransport(connection);
            HeadlessBot bot = new HeadlessBot(
                    transport, host, port,
                    account.username(), account.uuid(),
                    registries, context, account.accessToken());
            return new SpawnedBot(bot, transport);
        };
    }

    public ManagedBot spawnBot(HeadlessAccount account, String hostOverride, Integer portOverride, Path configDirOverride) {
        Objects.requireNonNull(account, "account");
        String id = account.username();
        if (bots.containsKey(id)) {
            throw new IllegalStateException("bot already exists: " + id);
        }
        String host = (hostOverride == null || hostOverride.isBlank()) ? defaultHost : hostOverride.trim();
        int port = (portOverride != null && portOverride > 0) ? portOverride : defaultPort;
        Path configDir = configDirOverride != null ? configDirOverride : configRoot.resolve(id);

        baritone.api.Settings botSettings = new baritone.api.Settings();
        botSettings.runMode.value = baritone.api.run.RunMode.Value.HEADLESS;
        BotContext context = new BotContext(
                id,
                configDir,
                true,
                botSettings,
                new AltoClefSettings());
        SpawnedBot spawned = spawner.create(account, context, host, port);
        HeadlessBot bot = spawned.bot();
        HeadlessSwarmLink swarmLink = swarmBus.register(id, this);
        bot.attachSwarmLink(swarmLink);
        if (agentFactory != null) {
            IHeadlessAgent agent;
            try {
                agent = BotContextScope.callWith(context, () -> agentFactory.create(context, bot));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create headless agent for " + id, e);
            }
            if (agent != null) {
                bot.attachAgent(agent);
            }
        }
        ManagedBot managed = new ManagedBot(
                id, account, context, bot, spawned.transport(),
                host, port, configDir, System.currentTimeMillis());
        ManagedBot existing = bots.putIfAbsent(id, managed);
        if (existing != null) {
            throw new IllegalStateException("bot already exists: " + id);
        }
        scheduler.register(bot);
        bot.start();
        return managed;
    }

    public boolean stopBot(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        ManagedBot entry = bots.remove(id);
        if (entry == null) {
            return false;
        }
        teardownManagedBot(entry, "stopped by fleet controller");
        return true;
    }

    public int stopAllBots() {
        List<String> ids = new ArrayList<>(bots.keySet());
        int stopped = 0;
        for (String id : ids) {
            ManagedBot entry = bots.remove(id);
            if (entry != null) {
                teardownManagedBot(entry, "fleet shutdown");
                stopped++;
            }
        }
        return stopped;
    }

    private void teardownManagedBot(ManagedBot entry, String reason) {
        scheduler.unregister(entry.bot());
        executions.remove(entry.id());
        swarmBus.unregister(entry.id());
        try {
            entry.bot().closeHost();
        } catch (RuntimeException ignored) {
        }
        try {
            entry.bot().onDisconnect(reason);
        } catch (RuntimeException ignored) {
        }
        try {
            entry.transport().disconnect(reason);
        } catch (RuntimeException ignored) {
        }
    }

    public List<ManagedBot> listBots() {
        List<ManagedBot> list = new ArrayList<>(bots.values());
        list.sort(Comparator.comparing(ManagedBot::id));
        return list;
    }

    public ManagedBot getBot(String id) {
        return id == null ? null : bots.get(id);
    }

    public int botCount() {
        return bots.size();
    }

    public int activeConnectedCount() {
        int n = 0;
        for (ManagedBot entry : bots.values()) {
            if (entry.bot().isConnected()) {
                n++;
            }
        }
        return n;
    }

    public HeadlessTickScheduler scheduler() {
        return scheduler;
    }

    public HeadlessSwarmBus swarmBus() {
        return swarmBus;
    }

    public void requestShutdown() {
        shutdownRequested.set(true);
    }

    public boolean isShutdownRequested() {
        return shutdownRequested.get();
    }

    public JsonObject dispatchCommand(String target, String command) {
        return dispatchCommand(target, command, "control", null);
    }

    public JsonObject dispatchCommand(String target, String command, String issuer, String commandId) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        String trimmedCmd = command.trim();
        String normalizedTarget = target == null ? "" : target.trim();
        List<String> affected = new ArrayList<>();
        if ("all".equalsIgnoreCase(normalizedTarget) || "*".equals(normalizedTarget)) {
            for (ManagedBot entry : listBots()) {
                recordExecution(entry.id(), trimmedCmd, issuer, commandId);
                entry.bot().dispatchAgentCommand(trimmedCmd);
                affected.add(entry.id());
            }
        } else {
            ManagedBot entry = bots.get(normalizedTarget);
            if (entry == null) {
                throw new NoSuchElementException("unknown bot: " + target);
            }
            recordExecution(entry.id(), trimmedCmd, issuer, commandId);
            entry.bot().dispatchAgentCommand(trimmedCmd);
            affected.add(entry.id());
        }
        JsonObject result = new JsonObject();
        result.addProperty("target", normalizedTarget);
        result.addProperty("command", trimmedCmd);
        result.addProperty("dispatched", affected.size());
        JsonArray ids = new JsonArray();
        for (String id : affected) {
            ids.add(id);
        }
        result.add("bots", ids);
        return result;
    }

    private void recordExecution(String botId, String command, String issuer, String commandId) {
        long now = System.currentTimeMillis();
        String norm = command.startsWith("#") ? command.substring(1).trim() : command;
        boolean isCancel = "stop".equalsIgnoreCase(norm) || "cancel".equalsIgnoreCase(norm) || "@stop".equalsIgnoreCase(norm);
        Deque<ExecutionEntry> deque = executions.computeIfAbsent(botId, k -> new ArrayDeque<>());
        synchronized (deque) {
            if (isCancel) {
                for (ExecutionEntry e : deque) {
                    if ("running".equals(e.execution) || "accepted".equals(e.execution)) {
                        e.execution = "canceled";
                        e.observation = "confirmed";
                        e.endReason = "stopped";
                    }
                }
            }
            String id = (commandId == null || commandId.isBlank()) ? UUID.randomUUID().toString() : commandId;
            String state = isCancel ? "succeeded" : "running";
            String obs = isCancel ? "confirmed" : "unknown";
            deque.addFirst(new ExecutionEntry(id, norm, issuer == null ? "control" : issuer, state, obs, now));
            while (deque.size() > BotFeed.MAX_EXECUTIONS) {
                deque.removeLast();
            }
        }
    }

    private List<BotFeed.Execution> feedExecutions(String botId, long nowMs) {
        Deque<ExecutionEntry> deque = executions.get(botId);
        if (deque == null) {
            return List.of();
        }
        List<BotFeed.Execution> out = new ArrayList<>();
        synchronized (deque) {
            for (ExecutionEntry e : deque) {
                out.add(new BotFeed.Execution(e.id, e.command, e.issuer, e.execution, e.observation,
                        Math.max(0L, nowMs - e.startedAtMs), e.endReason));
            }
        }
        return out;
    }

    public JsonObject schedulerJson() {
        JsonObject sched = new JsonObject();
        sched.addProperty("activeBots", scheduler.activeBots());
        sched.addProperty("ticks", scheduler.tickCount());
        sched.addProperty("overruns", scheduler.overrunCount());
        sched.addProperty("avgTickWorkMs", scheduler.avgTickDurationNanos() / 1_000_000.0);
        sched.addProperty("lastTickWorkMs", scheduler.lastTickDurationNanos() / 1_000_000.0);
        sched.addProperty("maxDriftMs", scheduler.maxDriftNanos() / 1_000_000.0);
        sched.addProperty("errors", scheduler.errorCount());
        return sched;
    }

    public JsonObject healthJson() {
        JsonObject json = new JsonObject();
        json.addProperty("status", shutdownRequested.get() ? "stopping" : "ok");
        json.addProperty("shutdownRequested", shutdownRequested.get());
        json.addProperty("managedBots", bots.size());
        json.addProperty("connectedBots", activeConnectedCount());
        json.add("scheduler", schedulerJson());
        return json;
    }

    public JsonObject listBotsJson() {
        JsonObject root = healthJson();
        JsonArray arr = new JsonArray();
        for (ManagedBot entry : listBots()) {
            arr.add(botSummaryJson(entry));
        }
        root.add("bots", arr);
        return root;
    }

    public JsonObject botDetailJson(String id) {
        ManagedBot entry = bots.get(id);
        if (entry == null) {
            throw new NoSuchElementException("unknown bot: " + id);
        }
        JsonObject json = botSummaryJson(entry);
        HeadlessBot bot = entry.bot();
        JsonArray invArr = new JsonArray();
        HeadlessPlayer player = bot.inventory().player();
        if (player != null) {
            Inventory inv = player.getInventory();
            synchronized (inv) {
                int size = inv.getContainerSize();
                for (int i = 0; i < size; i++) {
                    ItemStack stack = inv.getItem(i);
                    if (!stack.isEmpty()) {
                        JsonObject itemObj = new JsonObject();
                        itemObj.addProperty("slot", i);
                        itemObj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                        itemObj.addProperty("count", stack.getCount());
                        invArr.add(itemObj);
                    }
                }
            }
        }
        json.add("inventory", invArr);
        json.addProperty("packetsIn", bot.packetsIn());
        json.addProperty("packetsOut", bot.packetsOut());
        json.addProperty("corrections", bot.corrections());
        json.addProperty("host", entry.host());
        json.addProperty("port", entry.port());
        json.addProperty("configDir", entry.configDir().toString());
        return json;
    }

    public JsonObject botSummaryJson(ManagedBot entry) {
        HeadlessBot bot = entry.bot();
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entry.id());
        obj.addProperty("username", bot.username());
        obj.addProperty("uuid", bot.uuid().toString());
        obj.addProperty("authenticated", entry.account().hasAccessToken());
        obj.addProperty("state", bot.state().name());
        obj.addProperty("connected", bot.isConnected());
        if (bot.disconnectReason() != null) {
            obj.addProperty("disconnectReason", bot.disconnectReason());
        }
        obj.addProperty("health", bot.health());
        obj.addProperty("food", bot.food());
        obj.addProperty("saturation", bot.saturation());
        obj.addProperty("x", bot.x());
        obj.addProperty("y", bot.y());
        obj.addProperty("z", bot.z());
        obj.addProperty("yaw", bot.yRot());
        obj.addProperty("pitch", bot.xRot());
        obj.addProperty("onGround", bot.onGround());
        obj.addProperty("currentTask", bot.currentTask());
        obj.addProperty("process", bot.processName());
        obj.addProperty("dimension", bot.dimension());
        obj.addProperty("dim", String.valueOf(bot.dimensionChar()));
        obj.addProperty("maxHealth", bot.maxHealth());
        obj.addProperty("dead", bot.isDead());
        obj.addProperty("host", entry.host());
        obj.addProperty("port", entry.port());
        obj.addProperty("server", entry.host() + ":" + entry.port());
        obj.addProperty("runMode", "headless");
        obj.addProperty("ticks", bot.ticks());
        obj.addProperty("loadedChunks", bot.chunks().loadedChunks());
        return obj;
    }

    /** Builds a {@code ostinato.center} v1 {@link BotFeed} snapshot for one managed bot. */
    public BotFeed buildBotFeed(ManagedBot entry, long nowMs, long seq, boolean commandHost, boolean coordinator) {
        long t0 = System.nanoTime();
        HeadlessBot bot = entry.bot();
        BotFeed.Pos pos = (bot.state() == HeadlessBot.State.PLAY && bot.spawnKnown())
                ? new BotFeed.Pos(bot.dimensionChar(), bot.x(), bot.y(), bot.z(), bot.yRot())
                : null;
        int health = (int) Math.ceil(bot.health());
        int maxHealth = (int) Math.ceil(bot.maxHealth());
        int hunger = bot.food();
        boolean dead = bot.isDead();
        BotFeed.Inventory inv = buildInventorySummary(bot);
        Runtime rt = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        int ramUsed = (int) ((rt.totalMemory() - rt.freeMemory()) / mb);
        int ramMax = rt.maxMemory() == Long.MAX_VALUE ? -1 : (int) (rt.maxMemory() / mb);
        BotFeed.Perf perf = new BotFeed.Perf(
                bot.lastPathMs(),
                scheduler.tpsX10(),
                -1,
                ramUsed,
                ramMax,
                0,
                0,
                0,
                0);
        long gatherUs = Math.max(0L, (System.nanoTime() - t0) / 1000L);
        return new BotFeed(
                entry.id(),
                nowMs,
                seq,
                entry.host() + ":" + entry.port(),
                "headless",
                commandHost,
                coordinator,
                pos,
                health,
                maxHealth,
                hunger,
                dead,
                bot.processName(),
                bot.pathRoute(),
                inv,
                feedExecutions(entry.id(), nowMs),
                swarmBus.heldLeases(entry.id(), nowMs),
                perf,
                swarmBus.linkFor(entry.id()),
                gatherUs);
    }

    private static BotFeed.Inventory buildInventorySummary(HeadlessBot bot) {
        HeadlessPlayer player = bot.inventory().player();
        if (player == null) {
            return BotFeed.Inventory.UNKNOWN;
        }
        Inventory inv = player.getInventory();
        List<InventorySummary.Slot> slots = new ArrayList<>();
        synchronized (inv) {
            for (ItemStack st : inv.getNonEquipmentItems()) {
                if (st.isEmpty()) {
                    slots.add(null);
                    continue;
                }
                slots.add(new InventorySummary.Slot(
                        BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(),
                        st.getCount(),
                        st.get(DataComponents.FOOD) != null,
                        st.isDamageableItem() ? st.getDamageValue() : 0,
                        st.isDamageableItem() ? st.getMaxDamage() : 0));
            }
        }
        return InventorySummary.of(slots);
    }

    /** Command catalog exposed to the standalone Command Center window. */
    public CatalogFeed centerCatalog(String hostBot, long nowMs, long seq) {
        List<CatalogFeed.Button> buttons = List.of(
                new CatalogFeed.Button("goto", CommandPermission.SWARM,
                        FormDecl.of("goto", "Goto", "goto", FormPlacement.TILE, ArgDecl.rest("target", true))),
                new CatalogFeed.Button("mine", CommandPermission.SWARM,
                        FormDecl.of("mine", "Mine", "mine", FormPlacement.TILE,
                                ArgDecl.integer("count", false), ArgDecl.token("block", true))),
                new CatalogFeed.Button("get", CommandPermission.SWARM,
                        FormDecl.of("get", "Get", "@get", FormPlacement.TILE,
                                ArgDecl.token("item", true), ArgDecl.integer("count", false))),
                new CatalogFeed.Button("stop", CommandPermission.OWNER,
                        FormDecl.of("stop", "Stop", "stop", FormPlacement.TILE)),
                new CatalogFeed.Button("swarm", CommandPermission.OWNER,
                        FormDecl.of("cancel", "Cancel", "swarm cancel", FormPlacement.TILE, ArgDecl.token("id", false))),
                new CatalogFeed.Button("swarm", CommandPermission.OWNER,
                        FormDecl.of("despawn", "Despawn", "swarm despawn", FormPlacement.TILE, ArgDecl.token("name", true))),
                new CatalogFeed.Button("swarm", CommandPermission.OWNER,
                        FormDecl.of("spawn", "Spawn", "swarm spawn", FormPlacement.WINDOW,
                                ArgDecl.token("name", true), ArgDecl.integer("count", false),
                                ArgDecl.token("server", false), ArgDecl.token("account", false))),
                new CatalogFeed.Button("swarm", CommandPermission.OWNER,
                        FormDecl.of("stop-all", "Stop all", "swarm despawn all", FormPlacement.HEADER)),
                new CatalogFeed.Button("center", CommandPermission.OWNER,
                        FormDecl.of("layout-wall", "Wall", "center layout wall", FormPlacement.HEADER)),
                new CatalogFeed.Button("center", CommandPermission.OWNER,
                        FormDecl.of("layout-map", "War room", "center layout map", FormPlacement.HEADER)),
                new CatalogFeed.Button("theme", CommandPermission.OWNER,
                        FormDecl.of("theme-pick", "Theme", "theme", FormPlacement.HEADER, ArgDecl.token("name", true))));
        return new CatalogFeed(hostBot == null ? "" : hostBot, nowMs, seq, buttons);
    }

    /** Full {@code ostinato.center} v1 snapshot consumed by {@code HeadlessControlSource}. */
    public JsonObject centerSnapshotJson() {
        return centerSnapshotJson(0);
    }

    public JsonObject centerSnapshotJson(int controlPort) {
        long now = System.currentTimeMillis();
        long seq = centerSeq.incrementAndGet();
        List<ManagedBot> list = listBots();
        String leaderId = swarmBus.leader();
        String hostBot = (leaderId != null && bots.containsKey(leaderId))
                ? leaderId
                : (list.isEmpty() ? "fleet" : list.get(0).id());

        JsonObject root = new JsonObject();
        root.addProperty("schema", CenterFeed.SCHEMA);
        root.addProperty("v", CenterFeed.VERSION);
        root.addProperty("writtenAtMs", now);
        root.addProperty("seq", seq);
        root.addProperty("server", defaultHost + ":" + defaultPort);
        root.add("scheduler", schedulerJson());

        HostInfo hostInfo = new HostInfo(hostBot, now, seq, controlPort, ProcessHandle.current().pid(), startedAtMs);
        root.add("host", JsonParser.parseString(hostInfo.toJson()));

        CatalogFeed catalog = centerCatalog(hostBot, now, seq);
        root.add("catalog", JsonParser.parseString(catalog.toJson()));

        JsonArray botArr = new JsonArray();
        for (int i = 0; i < list.size(); i++) {
            ManagedBot entry = list.get(i);
            BotFeed feed = buildBotFeed(entry, now, seq, entry.id().equals(hostBot), entry.id().equals(hostBot));
            botArr.add(JsonParser.parseString(feed.toJson()));
        }
        root.add("bots", botArr);

        CoordinatorFeed coord = swarmBus.toCoordinatorFeed(hostBot, now, seq, defaultHost + ":" + defaultPort);
        root.add("coordinator", JsonParser.parseString(coord.toJson()));
        return root;
    }

    /** Executes one Command Center {@link CenterRequest} and returns a {@link CenterReply}. */
    public CenterReply handleCenterRequest(CenterRequest req) {
        long now = System.currentTimeMillis();
        List<ManagedBot> list = listBots();
        String hostBot = list.isEmpty() ? "fleet" : list.get(0).id();
        if (req == null) {
            return CenterReply.refused("", "invalid request", now);
        }
        if (req.op() == CenterRequest.Op.PING) {
            return new CenterReply(req.id(), now, true, false, "accepted", "", hostBot);
        }
        String line;
        if (req.op() == CenterRequest.Op.LINE) {
            line = req.line() == null ? "" : req.line().trim();
            if (line.startsWith("#")) {
                line = line.substring(1).trim();
            }
            if (line.isEmpty()) {
                return new CenterReply(req.id(), now, false, false, "invalid", "", hostBot);
            }
        } else {
            CatalogFeed cat = centerCatalog(hostBot, now, 0);
            ButtonModel button = null;
            for (CatalogFeed.Button b : cat.buttons()) {
                if (b.command().equals(req.command()) && b.form().id().equals(req.formId())) {
                    button = b.model();
                    break;
                }
            }
            if (button == null) {
                return CenterReply.refused(req.id(), "unknown form", now);
            }
            String target = TileRoute.target(req.bot(), hostBot);
            try {
                line = TileRoute.route(button, target, req.values());
            } catch (IllegalArgumentException e) {
                return new CenterReply(req.id(), now, false, false, "invalid", "", hostBot);
            }
        }
        if (req.dryRun()) {
            return new CenterReply(req.id(), now, false, false, "preview", line, hostBot);
        }
        return executeCenterLine(req.id(), req.bot(), hostBot, line, now);
    }

    private CenterReply executeCenterLine(String reqId, String targetBot, String hostBot, String line, long now) {
        String[] parts = line.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return new CenterReply(reqId, now, false, false, "invalid", line, hostBot);
        }
        String head = parts[0].toLowerCase(Locale.ROOT);
        try {
            if ("center".equals(head) || "theme".equals(head)) {
                return new CenterReply(reqId, now, true, true, "accepted", line, hostBot);
            }
            if ("swarm".equals(head)) {
                return executeCenterSwarmLine(reqId, targetBot, hostBot, line, parts, now);
            }
            String target = (targetBot == null || targetBot.isBlank()) ? hostBot : targetBot.trim();
            dispatchCommand(target, line, "center", reqId);
            return new CenterReply(reqId, now, true, true, "accepted", line, hostBot);
        } catch (NoSuchElementException e) {
            return new CenterReply(reqId, now, false, false, "offline", line, hostBot);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new CenterReply(reqId, now, false, false, "invalid", line, hostBot);
        }
    }

    private CenterReply executeCenterSwarmLine(String reqId, String targetBot, String hostBot, String line, String[] parts, long now) {
        if (parts.length < 2) {
            return new CenterReply(reqId, now, false, false, "invalid", line, hostBot);
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "send" -> {
                if (parts.length < 4) {
                    return new CenterReply(reqId, now, false, false, "invalid", line, hostBot);
                }
                String bot = parts[2];
                String cmd = line.substring(line.indexOf(bot) + bot.length()).trim();
                dispatchCommand(bot, cmd, "center", reqId);
                return new CenterReply(reqId, now, true, true, "accepted", line, hostBot);
            }
            case "lease" -> {
                if (parts.length < 5) {
                    return new CenterReply(reqId, now, false, false, "invalid", line, hostBot);
                }
                String bot = parts[2];
                String resource = parts[3];
                int resIdx = line.indexOf(resource);
                String cmd = line.substring(resIdx + resource.length()).trim();
                if (!swarmBus.tryClaim(bot, resource, 120_000L, cmd)) {
                    return new CenterReply(reqId, now, false, false, "lease", line, hostBot);
                }
                dispatchCommand(bot, cmd, "center", reqId);
                return new CenterReply(reqId, now, true, true, "accepted", line, hostBot);
            }
            case "cancel" -> {
                String cancelId = parts.length >= 3 ? parts[2] : "";
                if (cancelId.isEmpty() && (targetBot == null || targetBot.isBlank())) {
                    return new CenterReply(reqId, now, false, false, "bad-cancel-target", line, hostBot);
                }
                String ownerBot = findExecutionBot(cancelId);
                if (ownerBot == null) {
                    ownerBot = (targetBot == null || targetBot.isBlank()) ? hostBot : targetBot;
                }
                if (!bots.containsKey(ownerBot)) {
                    return new CenterReply(reqId, now, false, false, "unknown-id", line, hostBot);
                }
                dispatchCommand(ownerBot, "cancel", "center", reqId);
                return new CenterReply(reqId, now, true, true, "accepted", line, hostBot);
            }
            case "despawn" -> {
                if (parts.length < 3) {
                    return new CenterReply(reqId, now, false, false, "invalid", line, hostBot);
                }
                String who = parts[2];
                if ("all".equalsIgnoreCase(who) || "*".equals(who)) {
                    stopAllBots();
                    return new CenterReply(reqId, now, true, true, "accepted", line, hostBot);
                }
                boolean stopped = stopBot(who);
                return new CenterReply(reqId, now, stopped, stopped, stopped ? "accepted" : "unknown-bot", line, hostBot);
            }
            case "spawn" -> {
                if (parts.length < 3) {
                    return new CenterReply(reqId, now, false, false, "invalid", line, hostBot);
                }
                String name = parts[2];
                int count = 1;
                String spawnHost = null;
                Integer spawnPort = null;
                for (int i = 3; i < parts.length; i++) {
                    String tok = parts[i];
                    if (tok.startsWith("account:")) {
                        continue;
                    }
                    if (tok.matches("\\d+") && i == 3) {
                        count = Math.max(1, Integer.parseInt(tok));
                    } else if (tok.contains(":")) {
                        String[] hp = tok.split(":", 2);
                        spawnHost = hp[0];
                        spawnPort = Integer.parseInt(hp[1]);
                    } else {
                        spawnHost = tok;
                    }
                }
                if (count == 1) {
                    spawnBot(HeadlessAccount.offline(name), spawnHost, spawnPort, null);
                } else {
                    for (int k = 1; k <= count; k++) {
                        spawnBot(HeadlessAccount.offline(name + k), spawnHost, spawnPort, null);
                    }
                }
                return new CenterReply(reqId, now, true, true, "accepted", line, hostBot);
            }
            default -> {
                return new CenterReply(reqId, now, false, false, "unsupported", line, hostBot);
            }
        }
    }

    private String findExecutionBot(String cmdId) {
        if (cmdId == null || cmdId.isBlank()) {
            return null;
        }
        for (Map.Entry<String, Deque<ExecutionEntry>> e : executions.entrySet()) {
            synchronized (e.getValue()) {
                for (ExecutionEntry ex : e.getValue()) {
                    if (cmdId.equals(ex.id)) {
                        return e.getKey();
                    }
                }
            }
        }
        return null;
    }

    /** Returns live in-JVM swarm bus status, claims, and tracked assignments. */
    public JsonObject swarmStatusJson() {
        return swarmBus.statusJson();
    }

    /** Executes an in-JVM swarm coordinator action ({@code lead}, {@code join}, {@code acquire}, {@code cancel}). */
    public JsonObject dispatchSwarmAction(JsonObject req) {
        String action = req != null && req.has("action") && !req.get("action").isJsonNull()
                ? req.get("action").getAsString().trim().toLowerCase(Locale.ROOT)
                : "status";
        JsonObject res = new JsonObject();
        res.addProperty("action", action);
        switch (action) {
            case "status" -> res.add("swarm", swarmBus.statusJson());
            case "lead" -> {
                String bot = req != null && req.has("bot") && !req.get("bot").isJsonNull()
                        ? req.get("bot").getAsString().trim()
                        : swarmBus.leader();
                if (bot == null || !bots.containsKey(bot)) {
                    throw new NoSuchElementException("unknown leader bot: " + bot);
                }
                swarmBus.setLeader(bot);
                dispatchCommand(bot, "@swarm lead", "swarm", null);
                res.addProperty("leader", bot);
            }
            case "join" -> {
                String target = req != null && req.has("bot") && !req.get("bot").isJsonNull()
                        ? req.get("bot").getAsString().trim()
                        : "all";
                if ("all".equalsIgnoreCase(target) || "*".equals(target)) {
                    String leader = swarmBus.leader();
                    List<String> joined = new ArrayList<>();
                    for (ManagedBot mb : listBots()) {
                        if (!mb.id().equalsIgnoreCase(leader)) {
                            dispatchCommand(mb.id(), "@swarm join", "swarm", null);
                            joined.add(mb.id());
                        }
                    }
                    res.addProperty("joinedCount", joined.size());
                } else {
                    dispatchCommand(target, "@swarm join", "swarm", null);
                    res.addProperty("joined", target);
                }
            }
            case "acquire" -> {
                String leader = req != null && req.has("bot") && !req.get("bot").isJsonNull()
                        ? req.get("bot").getAsString().trim()
                        : swarmBus.leader();
                if (leader == null || !bots.containsKey(leader)) {
                    throw new NoSuchElementException("no active swarm leader");
                }
                String item = req != null && req.has("item") && !req.get("item").isJsonNull()
                        ? req.get("item").getAsString().trim()
                        : "";
                if (item.isEmpty()) {
                    throw new IllegalArgumentException("item is required");
                }
                int count = req.has("count") && !req.get("count").isJsonNull()
                        ? Math.max(1, req.get("count").getAsInt())
                        : 1;
                dispatchCommand(leader, "@swarm acquire " + item + " " + count, "swarm", null);
                res.addProperty("leader", leader);
                res.addProperty("item", item);
                res.addProperty("count", count);
            }
            case "cancel" -> {
                String leader = req != null && req.has("bot") && !req.get("bot").isJsonNull()
                        ? req.get("bot").getAsString().trim()
                        : swarmBus.leader();
                String id = req != null && req.has("id") && !req.get("id").isJsonNull()
                        ? req.get("id").getAsString().trim()
                        : "";
                if (leader != null && bots.containsKey(leader)) {
                    dispatchCommand(leader, id.isEmpty() ? "@swarm cancel" : "@swarm cancel " + id, "swarm", null);
                }
                res.addProperty("canceled", true);
            }
            default -> throw new IllegalArgumentException("unsupported swarm action: " + action);
        }
        return res;
    }

    /** Convenience helper to initialize a single-JVM swarm ({@code @swarm lead} + {@code @swarm join}). */
    public void bootstrapLocalSwarm(String leaderId) {
        List<ManagedBot> list = listBots();
        if (list.isEmpty()) {
            return;
        }
        String chosen = (leaderId != null && !leaderId.isBlank() && bots.containsKey(leaderId.trim()))
                ? leaderId.trim()
                : list.get(0).id();
        swarmBus.setLeader(chosen);
        dispatchCommand(chosen, "@swarm lead", "swarm", null);
        for (ManagedBot mb : list) {
            if (!mb.id().equalsIgnoreCase(chosen)) {
                dispatchCommand(mb.id(), "@swarm join", "swarm", null);
            }
        }
    }

    /**
     * Executes one console/stdin control command and returns a concise response line.
     */
    public String executeConsoleLine(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return "";
        }
        String line = rawLine.trim();
        if (line.startsWith("@")) {
            JsonObject res = dispatchCommand("all", line);
            return "dispatched '" + line + "' to " + res.get("dispatched").getAsInt() + " bot(s)";
        }
        String[] parts = line.split("\\s+", 3);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "help", "?" -> {
                return "Commands: list | stats | status <bot> | cmd <bot|all> <command> | swarm <status|lead|join|acquire> | spawn <user> [host] [port] | stop <bot|all> | shutdown";
            }
            case "swarm" -> {
                String[] sw = line.split("\\s+", 4);
                String sub = sw.length >= 2 ? sw[1].toLowerCase(Locale.ROOT) : "status";
                JsonObject req = new JsonObject();
                req.addProperty("action", sub);
                if ("lead".equals(sub) && sw.length >= 3) {
                    req.addProperty("bot", sw[2]);
                } else if ("join".equals(sub) && sw.length >= 3) {
                    req.addProperty("bot", sw[2]);
                } else if ("acquire".equals(sub) && sw.length >= 3) {
                    req.addProperty("item", sw[2]);
                    if (sw.length >= 4) {
                        req.addProperty("count", Integer.parseInt(sw[3]));
                    }
                }
                return dispatchSwarmAction(req).toString();
            }
            case "list", "ls" -> {
                List<ManagedBot> list = listBots();
                if (list.isEmpty()) {
                    return "no bots managed";
                }
                StringBuilder sb = new StringBuilder();
                sb.append("bots=").append(list.size()).append(" connected=").append(activeConnectedCount());
                for (ManagedBot entry : list) {
                    HeadlessBot b = entry.bot();
                    sb.append(String.format(Locale.ROOT,
                            "%n  %-16s state=%-12s hp=%.1f food=%d pos=(%.1f,%.1f,%.1f) task=%s",
                            entry.id(), b.state(), b.health(), b.food(), b.x(), b.y(), b.z(), b.currentTask()));
                }
                return sb.toString();
            }
            case "stats" -> {
                return String.format(Locale.ROOT,
                        "bots=%d connected=%d activeTick=%d ticks=%d overruns=%d avgWorkMs=%.3f maxDriftMs=%.3f errors=%d",
                        bots.size(),
                        activeConnectedCount(),
                        scheduler.activeBots(),
                        scheduler.tickCount(),
                        scheduler.overrunCount(),
                        scheduler.avgTickDurationNanos() / 1_000_000.0,
                        scheduler.maxDriftNanos() / 1_000_000.0,
                        scheduler.errorCount());
            }
            case "status" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    return "usage: status <botId>";
                }
                try {
                    return botDetailJson(parts[1].trim()).toString();
                } catch (NoSuchElementException e) {
                    return e.getMessage();
                }
            }
            case "cmd", "exec" -> {
                if (parts.length < 3 || parts[2].isBlank()) {
                    return "usage: cmd <botId|all> <command>";
                }
                try {
                    JsonObject res = dispatchCommand(parts[1].trim(), parts[2].trim());
                    return "dispatched '" + parts[2].trim() + "' to " + res.get("dispatched").getAsInt() + " bot(s)";
                } catch (RuntimeException e) {
                    return "error: " + e.getMessage();
                }
            }
            case "spawn" -> {
                String[] spawnArgs = line.split("\\s+");
                if (spawnArgs.length < 2 || spawnArgs[1].isBlank()) {
                    return "usage: spawn <username> [host] [port]";
                }
                String username = spawnArgs[1].trim();
                String host = spawnArgs.length >= 3 ? spawnArgs[2].trim() : null;
                Integer port = null;
                if (host != null && host.contains(":")) {
                    String[] hp = host.split(":", 2);
                    host = hp[0];
                    port = Integer.parseInt(hp[1]);
                } else if (spawnArgs.length >= 4) {
                    port = Integer.parseInt(spawnArgs[3].trim());
                }
                try {
                    ManagedBot spawned = spawnBot(HeadlessAccount.offline(username), host, port, null);
                    return "spawned " + spawned.id() + " -> " + spawned.host() + ":" + spawned.port();
                } catch (RuntimeException e) {
                    return "spawn failed: " + e.getMessage();
                }
            }
            case "stop" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    return "usage: stop <botId|all>";
                }
                String target = parts[1].trim();
                if ("all".equalsIgnoreCase(target) || "*".equals(target)) {
                    return "stopped " + stopAllBots() + " bot(s)";
                }
                return stopBot(target) ? "stopped " + target : "unknown bot: " + target;
            }
            case "shutdown", "quit", "exit" -> {
                requestShutdown();
                return "shutdown requested";
            }
            default -> {
                return "unknown command '" + parts[0] + "' (type 'help')";
            }
        }
    }
}
