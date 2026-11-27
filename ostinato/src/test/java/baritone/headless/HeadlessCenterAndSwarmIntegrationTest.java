/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.headless;

import baritone.api.Settings;
import baritone.api.context.BotContext;
import baritone.api.context.BotContextScope;
import baritone.api.headless.IHeadlessAgent;
import baritone.api.headless.IHeadlessAgentFactory;
import baritone.api.headless.IHeadlessHost;
import baritone.api.swarm.SwarmPriority;
import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.app.AppStyle;
import baritone.swarm.center.app.CenterShell;
import baritone.swarm.center.app.Commands;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.channel.HeadlessControlCommandPort;
import baritone.swarm.center.channel.HeadlessEndpoint;
import baritone.swarm.center.feed.HeadlessControlSource;
import baritone.swarm.center.feed.SwarmView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.Cipher;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration test inside Ostinato for:
 * 1) Real Command Center ({@link HeadlessControlSource} + {@link HeadlessControlCommandPort} + {@link CenterShell})
 *    connected to {@link HeadlessControlServer}.
 * 2) In-JVM {@link HeadlessSwarmBus} + {@link HeadlessSwarmLink} message isolation, reservation claims,
 *    remote fallback, and coordinator feed projection.
 */
public class HeadlessCenterAndSwarmIntegrationTest {

    private static RegistryAccess.Frozen registries;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    public void commandCenterAndInJvmSwarmBridgeIntegrateEndToEnd() throws Exception {
        HeadlessTickScheduler scheduler = new HeadlessTickScheduler(4);
        List<String> agentCommands = new CopyOnWriteArrayList<>();

        IHeadlessAgentFactory factory = (ctx, host) -> new IHeadlessAgent() {
            private String task = "idle";

            @Override
            public void onAttach(BotContext context, IHeadlessHost h) {
            }

            @Override
            public void onTick() {
            }

            @Override
            public void onCommand(String command) {
                agentCommands.add(ctx.botId() + ":" + command);
                task = ("@stop".equals(command) || "stop".equals(command)) ? "idle" : "running:" + command;
            }

            @Override
            public String currentTask() {
                return task;
            }
        };

        HeadlessFleetController fleet = new HeadlessFleetController(
                scheduler,
                factory,
                "127.0.0.1",
                25570,
                temp.newFolder("fleet").toPath(),
                (account, ctx, host, port) -> {
                    FakeTransport transport = new FakeTransport();
                    HeadlessBot bot = new HeadlessBot(
                            transport, host, port, account.username(), account.uuid(),
                            registries, ctx, account.accessToken());
                    return new HeadlessFleetController.SpawnedBot(bot, transport);
                });

        HeadlessFleetController.ManagedBot lead = fleet.spawnBot(HeadlessAccount.offline("LeadBot"), null, null, null);
        HeadlessFleetController.ManagedBot one = fleet.spawnBot(HeadlessAccount.offline("BotOne"), null, null, null);
        HeadlessFleetController.ManagedBot two = fleet.spawnBot(HeadlessAccount.offline("BotTwo"), null, null, null);

        // Verify per-bot Settings & BotContext isolation
        assertNotSame(lead.context(), one.context());
        assertNotSame(lead.context().settings(), one.context().settings());
        assertNotSame(one.context().settings(), two.context().settings());
        assertEquals(baritone.api.run.RunMode.Value.HEADLESS, one.context().settings().runMode.value);

        try (HeadlessControlServer server = new HeadlessControlServer(fleet, 0)) {
            server.start();
            HeadlessEndpoint ep = HeadlessEndpoint.parse("127.0.0.1:" + server.port());

            try (HeadlessControlSource source = new HeadlessControlSource(List.of(ep), System::currentTimeMillis)) {
                SwarmView view = source.snapshot();
                assertEquals(3, view.bots().size());
                assertNotNull(view.bot("LeadBot"));
                assertNotNull(view.bot("BotOne"));
                assertNotNull(view.bot("BotTwo"));
                assertNotNull(view.catalog());
                assertNotNull(view.host());

                HeadlessControlCommandPort port = new HeadlessControlCommandPort(
                        List.of(ep), source::endpointForBot, System::currentTimeMillis);
                Commands commands = new Commands(port, Runnable::run, Runnable::run, System::currentTimeMillis);
                CenterShell shell = new CenterShell(new AppStyle(CenterTheme.DEFAULT_DARK), commands, CenterLayout.WALL);
                shell.update(view);
                shell.component().setSize(1280, 860);
                layout(shell.component());
                layout(shell.component());

                assertNotNull(find(shell.component(), "tile:LeadBot"));
                assertNotNull(find(shell.component(), "tile:BotOne"));
                assertNotNull(find(shell.component(), "tile:BotTwo"));

                // Dry run preview
                CenterReply dry = port.send(CenterRequest.press(
                        "goto", "goto", Map.of("target", "100 64 200"), "BotOne", System.currentTimeMillis()).dry());
                assertEquals("preview", dry.reason());
                assertFalse(dry.executed());

                // Command dispatch: goto, mine, get, stop, 2-arg goto line, spawn, despawn
                assertTrue(port.send(CenterRequest.press(
                        "goto", "goto", Map.of("target", "100 64 200"), "BotOne", System.currentTimeMillis())).executed());
                assertTrue(port.send(CenterRequest.press(
                        "mine", "mine", Map.of("count", "4", "block", "oak_log"), "BotTwo", System.currentTimeMillis())).executed());
                assertTrue(port.send(CenterRequest.press(
                        "get", "get", Map.of("item", "dirt", "count", "8"), "BotOne", System.currentTimeMillis())).executed());
                assertTrue(port.send(CenterRequest.press(
                        "stop", "stop", Map.of(), "BotOne", System.currentTimeMillis())).executed());
                assertTrue(port.send(CenterRequest.line(
                        "#goto 10 20", "BotTwo", System.currentTimeMillis())).executed());

                assertTrue(agentCommands.contains("BotOne:@get dirt 8"));
                assertTrue(agentCommands.contains("BotOne:@stop"));

                SwarmView afterDispatch = awaitView(source, v ->
                        v.bot("BotOne") != null
                                && v.bot("BotOne").feed().executions().stream()
                                        .anyMatch(e -> "goto 100 64 200".equals(e.command()) && "canceled".equals(e.execution()))
                                && v.bot("BotTwo") != null
                                && v.bot("BotTwo").feed().executions().stream()
                                        .anyMatch(e -> "mine 4 oak_log".equals(e.command()))
                                && v.bot("BotTwo").feed().executions().stream()
                                        .anyMatch(e -> "goto 10 20".equals(e.command())));
                assertNotNull(afterDispatch);

                CenterReply spawnReply = port.send(CenterRequest.press(
                        "swarm", "spawn", Map.of("name", "BotFour"), "LeadBot", System.currentTimeMillis()));
                assertTrue(spawnReply.executed());
                assertEquals(4, fleet.botCount());

                SwarmView refreshed = awaitView(source, v -> v.bot("BotFour") != null);
                assertNotNull(refreshed.bot("BotFour"));

                CenterReply despawnReply = port.send(CenterRequest.press(
                        "swarm", "despawn", Map.of("name", "BotFour"), "BotFour", System.currentTimeMillis()));
                assertTrue(despawnReply.executed());
                assertEquals(3, fleet.botCount());

                // ---- Phase 2: In-JVM Swarm Bus + Context Isolation + Claims + Remote Fallback ----
                AtomicReference<BotContext> handlerContext = new AtomicReference<>();
                AtomicReference<String> handlerBody = new AtomicReference<>();
                one.bot().swarmLink().registerHandler("TCS", (from, group, body) -> {
                    handlerContext.set(BotContextScope.currentOrNull());
                    handlerBody.set(from + ":" + body);
                });

                assertTrue(lead.bot().swarmLink().send(
                        HeadlessSwarmBus.DEFAULT_GROUP, "BotOne", "TCS",
                        "s1 offer a=a1 item=oak_log n=2", SwarmPriority.NORMAL));
                // Must NOT execute synchronously on caller thread; must wait for BotOne's tick()
                assertNull(handlerBody.get());

                one.bot().tick();
                assertEquals("LeadBot:s1 offer a=a1 item=oak_log n=2", handlerBody.get());
                assertSame(one.context(), handlerContext.get());

                // ReservationKey overlapping region claim check
                assertTrue(fleet.swarmBus().tryClaim("BotOne", "r:o:0,60,0:10,70,10", 60_000, "clear"));
                assertFalse(fleet.swarmBus().tryClaim("BotTwo", "r:o:5,64,5:15,72,15", 60_000, "overlap"));
                fleet.swarmBus().releaseClaim("BotOne", "r:o:0,60,0:10,70,10");
                assertTrue(fleet.swarmBus().tryClaim("BotTwo", "r:o:5,64,5:15,72,15", 60_000, "now-free"));

                // Remote fallback hook for unknown/non-JVM peer
                AtomicReference<String> fallbackTarget = new AtomicReference<>();
                fleet.swarmBus().setRemoteFallback((from, group, to, type, body, prio) -> {
                    fallbackTarget.set(from + "->" + to + ":" + body);
                    return true;
                });
                assertTrue(lead.bot().swarmLink().send(
                        HeadlessSwarmBus.DEFAULT_GROUP, "RemotePeer", "TCS", "s1 reg caps=mine", SwarmPriority.NORMAL));
                assertEquals("LeadBot->RemotePeer:s1 reg caps=mine", fallbackTarget.get());

                // Passive TCS observation updates /center/snapshot leases + /swarm/status
                assertTrue(one.bot().swarmLink().send(
                        HeadlessSwarmBus.DEFAULT_GROUP, "LeadBot", "TCS",
                        "s1 accept a=a1", SwarmPriority.NORMAL));
                assertTrue(one.bot().swarmLink().send(
                        HeadlessSwarmBus.DEFAULT_GROUP, "LeadBot", "TCS",
                        "s1 prog a=a1 n=1 out=2", SwarmPriority.LOW));

                SwarmView afterTcs = awaitView(source, v ->
                        v.coordinator() != null
                                && v.coordinator().leases().stream().anyMatch(l -> "a1".equals(l.id()) && "BotOne".equals(l.bot()))
                                && v.bot("BotOne") != null
                                && v.bot("BotOne").feed().leases().stream().anyMatch(l -> "a1".equals(l.id())));
                assertNotNull(afterTcs);

                HttpResponse<String> swarmStatusResp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/swarm/status"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, swarmStatusResp.statusCode());
                JsonObject swarmJson = JsonParser.parseString(swarmStatusResp.body()).getAsJsonObject();
                assertEquals("LeadBot", swarmJson.get("leader").getAsString());
                assertTrue(swarmJson.get("localMessagesDelivered").getAsLong() >= 1L);
                assertTrue(swarmJson.get("remoteFallbackSent").getAsLong() >= 1L);
            }
        } finally {
            fleet.stopAllBots();
            scheduler.stop();
        }
    }

    private static SwarmView awaitView(HeadlessControlSource source, Predicate<SwarmView> check) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        SwarmView latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = source.snapshot();
            if (check.test(latest)) {
                return latest;
            }
            Thread.sleep(75L);
        }
        return check.test(latest) ? latest : null;
    }

    private static Component find(Container root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName())) {
                return c;
            }
            if (c instanceof Container child) {
                Component found = find(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void layout(Component root) {
        root.doLayout();
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                layout(child);
            }
        }
    }

    private static final class FakeTransport implements HeadlessTransport {
        @Override
        public void initiate(String host, int port, ClientLoginPacketListener loginListener) {
        }

        @Override
        public void send(Packet<?> packet) {
        }

        @Override
        public void setupInbound(ProtocolInfo<?> info, PacketListener listener) {
        }

        @Override
        public void setupOutbound(ProtocolInfo<?> info) {
        }

        @Override
        public void setupCompression(int threshold, boolean rejectBadPackets) {
        }

        @Override
        public void setupEncryption(Cipher decryptCipher, Cipher encryptCipher) {
        }

        @Override
        public void disconnect(String reason) {
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }
}
