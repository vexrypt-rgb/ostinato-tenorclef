/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.headless;

import baritone.api.headless.IHeadlessAgentFactory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.network.EventLoopGroupHolder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * Single-JVM multi-instance headless launcher.
 * Runs N independent {@link HeadlessBot} instances sharing one frozen {@link RegistryAccess}
 * and one Netty {@link EventLoopGroupHolder} while keeping per-bot {@code Settings},
 * {@code AltoClefSettings}, config directories, and {@link baritone.context.BotContext} isolated.
 *
 * <p>Supports interactive stdin fleet control, an optional {@code 127.0.0.1} HTTP/WebSocket control
 * endpoint ({@code --control-port}), and per-bot credential loading ({@code --accounts-file}).
 */
public final class HeadlessMultiLauncher {

    private HeadlessMultiLauncher() {
    }

    public static void main(String[] args) throws Exception {
        int requestedBots = 1;
        boolean botsExplicit = false;
        String host = "127.0.0.1";
        int port = 25565;
        String prefix = "Bot";
        Path configRoot = Path.of("headless-bots");
        Path accountsFile = null;
        int controlPort = -1;
        boolean enableStdin = true;
        int seconds = 30;
        long staggerMs = 200L;
        int tickThreads = 4;
        boolean withTenorClef = false;
        String swarmLeader = null;
        boolean swarmAuto = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--bots" -> {
                    requestedBots = Integer.parseInt(requireValue(args, ++i, "--bots"));
                    botsExplicit = true;
                }
                case "--host" -> host = requireValue(args, ++i, "--host");
                case "--port" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                case "--prefix" -> prefix = requireValue(args, ++i, "--prefix");
                case "--config-root", "--config-dir" -> configRoot = Path.of(requireValue(args, ++i, arg));
                case "--accounts-file" -> accountsFile = Path.of(requireValue(args, ++i, "--accounts-file"));
                case "--control-port" -> controlPort = Integer.parseInt(requireValue(args, ++i, "--control-port"));
                case "--no-stdin" -> enableStdin = false;
                case "--seconds" -> seconds = Integer.parseInt(requireValue(args, ++i, "--seconds"));
                case "--stagger-ms" -> staggerMs = Long.parseLong(requireValue(args, ++i, "--stagger-ms"));
                case "--tick-threads" -> tickThreads = Integer.parseInt(requireValue(args, ++i, "--tick-threads"));
                case "--with-tenorclef" -> withTenorClef = true;
                case "--swarm-leader" -> swarmLeader = requireValue(args, ++i, "--swarm-leader");
                case "--swarm-auto" -> swarmAuto = true;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        List<HeadlessAccount> accounts = resolveAccounts(accountsFile, requestedBots, botsExplicit, prefix);
        int botCount = accounts.size();
        if (botCount == 0 && controlPort < 0) {
            throw new IllegalArgumentException("No accounts/bots configured and --control-port is disabled");
        }

        RegistryAccess.Frozen registries = HeadlessRuntime.sharedRegistries();
        EventLoopGroupHolder eventLoop = HeadlessRuntime.sharedEventLoop();
        HeadlessTickScheduler scheduler = new HeadlessTickScheduler(tickThreads);
        IHeadlessAgentFactory agentFactory = withTenorClef ? loadAgentFactory() : null;

        HeadlessFleetController fleet = new HeadlessFleetController(
                scheduler, registries, eventLoop, agentFactory, host, port, configRoot);

        HeadlessControlServer controlServer = null;
        if (controlPort >= 0) {
            controlServer = new HeadlessControlServer(fleet, controlPort);
            controlServer.start();
            System.out.println("[multi] control endpoint listening on http://127.0.0.1:"
                    + controlServer.port() + "/ and ws://127.0.0.1:" + controlServer.port() + "/ws");
        }

        System.out.printf(Locale.ROOT,
                "[multi] starting %d bots -> %s:%d (configRoot=%s, accounts=%s, tickThreads=%d, staggerMs=%d, agent=%s)%n",
                botCount, host, port, configRoot,
                accountsFile != null ? accountsFile : "generated",
                tickThreads, staggerMs,
                agentFactory != null ? agentFactory.getClass().getSimpleName() : "baritone-only");

        for (int i = 0; i < botCount; i++) {
            HeadlessAccount account = accounts.get(i);
            fleet.spawnBot(account, host, port, configRoot.resolve(account.username()));
            if (staggerMs > 0 && i + 1 < botCount) {
                Thread.sleep(staggerMs);
            }
        }

        scheduler.start();

        if (swarmLeader != null && !swarmLeader.isBlank()) {
            fleet.swarmBus().setLeader(swarmLeader);
        }
        if (swarmAuto && botCount > 0) {
            fleet.bootstrapLocalSwarm(swarmLeader);
        }

        if (enableStdin) {
            startStdinLoop(fleet);
        }

        try {
            long deadline = seconds > 0
                    ? System.nanoTime() + seconds * 1_000_000_000L
                    : Long.MAX_VALUE;
            boolean waitForIdleControl = controlServer != null && botCount == 0;
            while (!fleet.isShutdownRequested()
                    && (seconds <= 0 || System.nanoTime() < deadline)
                    && (waitForIdleControl || scheduler.activeBots() > 0 || fleet.botCount() > 0)) {
                Thread.sleep(100L);
                if (!waitForIdleControl && scheduler.activeBots() == 0 && controlServer == null) {
                    break;
                }
            }
        } finally {
            if (controlServer != null) {
                try {
                    controlServer.close();
                } catch (IOException ignored) {
                }
            }
            scheduler.stop();
            fleet.stopAllBots();
            eventLoop.eventLoopGroup().shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS);
        }

        System.out.printf(Locale.ROOT,
                "[multi] done ticks=%d overruns=%d avgTickWorkMs=%.3f maxDriftMs=%.3f errors=%d connectedBots=%d%n",
                scheduler.tickCount(),
                scheduler.overrunCount(),
                scheduler.avgTickDurationNanos() / 1_000_000.0,
                scheduler.maxDriftNanos() / 1_000_000.0,
                scheduler.errorCount(),
                fleet.activeConnectedCount());
    }

    static List<HeadlessAccount> resolveAccounts(
            Path accountsFile,
            int requestedBots,
            boolean botsExplicit,
            String prefix) throws IOException {
        if (accountsFile != null) {
            List<HeadlessAccount> loaded = HeadlessAccount.load(accountsFile);
            if (!botsExplicit) {
                return loaded;
            }
            if (requestedBots < 0) {
                throw new IllegalArgumentException("--bots must be >= 0");
            }
            if (requestedBots > loaded.size()) {
                throw new IllegalArgumentException("--bots " + requestedBots
                        + " exceeds accounts loaded from " + accountsFile + " (" + loaded.size() + ")");
            }
            return loaded.subList(0, requestedBots);
        }
        if (requestedBots < 0) {
            throw new IllegalArgumentException("--bots must be >= 0");
        }
        List<HeadlessAccount> generated = new ArrayList<>(requestedBots);
        for (int i = 1; i <= requestedBots; i++) {
            generated.add(HeadlessAccount.offline(prefix + i));
        }
        return generated;
    }

    private static void startStdinLoop(HeadlessFleetController fleet) {
        Thread stdinThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while (!fleet.isShutdownRequested() && (line = reader.readLine()) != null) {
                    String out = fleet.executeConsoleLine(line);
                    if (!out.isEmpty()) {
                        System.out.println("[control] " + out);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "headless-fleet-stdin");
        stdinThread.setDaemon(true);
        stdinThread.start();
    }

    private static IHeadlessAgentFactory loadAgentFactory() {
        ServiceLoader<IHeadlessAgentFactory> loader = ServiceLoader.load(IHeadlessAgentFactory.class);
        return loader.findFirst().orElseThrow(() -> new IllegalStateException(
                "--with-tenorclef requested, but no META-INF/services/"
                        + IHeadlessAgentFactory.class.getName() + " provider was found on classpath"));
    }

    private static String requireValue(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[idx];
    }

    private static void printUsage() {
        System.out.println("Usage: HeadlessMultiLauncher [options]");
        System.out.println("  --bots <N>              Number of bots to launch (default 1, or all from --accounts-file)");
        System.out.println("  --host <host>           Target server hostname (default 127.0.0.1)");
        System.out.println("  --port <port>           Target server port (default 25565)");
        System.out.println("  --prefix <prefix>       Username prefix when generating offline accounts (default Bot)");
        System.out.println("  --config-root <dir>     Root directory for per-bot config folders (alias: --config-dir)");
        System.out.println("  --accounts-file <path>  Load offline/online accounts from JSON or CSV/colon text");
        System.out.println("  --control-port <port>   Start 127.0.0.1 HTTP JSON & /ws control endpoint (0 = ephemeral)");
        System.out.println("  --no-stdin              Disable interactive stdin command loop");
        System.out.println("  --seconds <N>           Duration in seconds (<= 0 runs until shutdown/EOF; default 30)");
        System.out.println("  --stagger-ms <ms>       Delay between sequential bot logins (default 200)");
        System.out.println("  --tick-threads <N>      Worker threads in HeadlessTickScheduler (default 4)");
        System.out.println("  --with-tenorclef        Load TenorClef agent factory via ServiceLoader");
        System.out.println("  --swarm-leader <botId>  Designate initial in-JVM swarm coordinator bot");
        System.out.println("  --swarm-auto            Auto-bootstrap @swarm lead/join across launched bots");
    }
}
