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
import baritone.api.context.BotContext;
import baritone.api.context.BotContextScope;
import baritone.api.headless.IHeadlessAgent;
import baritone.api.headless.IHeadlessAgentFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

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

        BotContext context = new BotContext(
                id,
                configDir,
                true,
                BaritoneAPI.getSettings(),
                new AltoClefSettings());
        SpawnedBot spawned = spawner.create(account, context, host, port);
        HeadlessBot bot = spawned.bot();
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

    public void requestShutdown() {
        shutdownRequested.set(true);
    }

    public boolean isShutdownRequested() {
        return shutdownRequested.get();
    }

    public JsonObject dispatchCommand(String target, String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        String trimmedCmd = command.trim();
        String normalizedTarget = target == null ? "" : target.trim();
        List<String> affected = new ArrayList<>();
        if ("all".equalsIgnoreCase(normalizedTarget) || "*".equals(normalizedTarget)) {
            for (ManagedBot entry : listBots()) {
                entry.bot().dispatchAgentCommand(trimmedCmd);
                affected.add(entry.id());
            }
        } else {
            ManagedBot entry = bots.get(normalizedTarget);
            if (entry == null) {
                throw new NoSuchElementException("unknown bot: " + target);
            }
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
        obj.addProperty("ticks", bot.ticks());
        obj.addProperty("loadedChunks", bot.chunks().loadedChunks());
        return obj;
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
                return "Commands: list | stats | status <bot> | cmd <bot|all> <command> | spawn <user> [host] [port] | stop <bot|all> | shutdown";
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
