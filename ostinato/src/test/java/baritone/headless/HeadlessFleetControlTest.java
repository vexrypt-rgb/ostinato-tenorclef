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
import baritone.api.headless.IHeadlessAgent;
import baritone.api.headless.IHeadlessAgentFactory;
import baritone.api.headless.IHeadlessHost;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.Cipher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HeadlessFleetControlTest {

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
    public void loadsJsonAndDelimitedAccountsAndResolvesLauncherCounts() throws Exception {
        Path jsonPath = temp.newFile("accounts.json").toPath();
        Files.writeString(jsonPath, """
                [
                  "OfflineOne",
                  {"username": "AuthTwo", "uuid": "11111111222233334444555566667777", "accessToken": "tok-abc"},
                  {"name": "OfflineThree", "uuid": "00000000-0000-4000-8000-000000000003"}
                ]
                """, StandardCharsets.UTF_8);

        List<HeadlessAccount> jsonAccounts = HeadlessAccount.load(jsonPath);
        assertEquals(3, jsonAccounts.size());
        assertEquals("OfflineOne", jsonAccounts.get(0).username());
        assertFalse(jsonAccounts.get(0).hasAccessToken());
        assertEquals(UUID.fromString("11111111-2222-3333-4444-555566667777"), jsonAccounts.get(1).uuid());
        assertTrue(jsonAccounts.get(1).hasAccessToken());
        assertEquals("tok-abc", jsonAccounts.get(1).accessToken());

        Path csvPath = temp.newFile("accounts.txt").toPath();
        Files.writeString(csvPath, """
                # comment line
                BotAlpha
                BotBeta,11111111-2222-3333-4444-555566667778,tok-beta
                BotGamma:-:tok-gamma
                """, StandardCharsets.UTF_8);

        List<HeadlessAccount> csvAccounts = HeadlessAccount.load(csvPath);
        assertEquals(3, csvAccounts.size());
        assertEquals("BotAlpha", csvAccounts.get(0).username());
        assertEquals("tok-beta", csvAccounts.get(1).accessToken());
        assertEquals("BotGamma", csvAccounts.get(2).username());
        assertEquals("tok-gamma", csvAccounts.get(2).accessToken());

        assertEquals(3, HeadlessMultiLauncher.resolveAccounts(jsonPath, 1, false, "Bot").size());
        assertEquals(2, HeadlessMultiLauncher.resolveAccounts(jsonPath, 2, true, "Bot").size());
    }

    @Test
    public void onlineModeHelloAuthenticatesAndEnablesEncryption() throws Exception {
        AtomicReference<JsonObject> authBody = new AtomicReference<>();
        CountDownLatch authLatch = new CountDownLatch(1);
        HttpServer mockMojang = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockMojang.createContext("/session/minecraft/join", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            authBody.set(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            authLatch.countDown();
        });
        mockMojang.start();

        String prevProp = System.getProperty("ostinato.sessionServerUrl");
        System.setProperty("ostinato.sessionServerUrl",
                "http://127.0.0.1:" + mockMojang.getAddress().getPort() + "/session/minecraft/join");
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(1024);
            KeyPair keys = gen.generateKeyPair();
            byte[] challenge = new byte[]{1, 2, 3, 4};

            FakeTransport okTransport = new FakeTransport();
            UUID botUuid = UUID.fromString("11111111-2222-3333-4444-555566667777");
            HeadlessBot okBot = new HeadlessBot(
                    okTransport, "127.0.0.1", 25565, "OnlineBot", botUuid, registries,
                    new BotContext("OnlineBot", temp.newFolder("online").toPath(), true,
                            BaritoneAPI.getSettings(), new AltoClefSettings()),
                    "secret-token-xyz");
            okBot.onPacket(new ClientboundHelloPacket("", keys.getPublic().getEncoded(), challenge, true));

            assertTrue("Mojang join call should complete", authLatch.await(5, TimeUnit.SECONDS));
            assertTrue("encryption should become active", okTransport.awaitEncryption(5, TimeUnit.SECONDS));
            assertNotNull(authBody.get());
            assertEquals("secret-token-xyz", authBody.get().get("accessToken").getAsString());
            assertEquals("11111111222233334444555566667777", authBody.get().get("selectedProfile").getAsString());
            assertTrue(okTransport.sentPackets.stream().anyMatch(p -> p instanceof ServerboundKeyPacket));

            FakeTransport noTokenTransport = new FakeTransport();
            HeadlessBot noTokenBot = new HeadlessBot(
                    noTokenTransport, "127.0.0.1", 25565, "NoTokenBot", botUuid, registries,
                    new BotContext("NoTokenBot", temp.newFolder("notoken").toPath(), true,
                            BaritoneAPI.getSettings(), new AltoClefSettings()),
                    "");
            noTokenBot.onPacket(new ClientboundHelloPacket("", keys.getPublic().getEncoded(), challenge, true));
            assertFalse(noTokenBot.isConnected());
            assertTrue(noTokenBot.disconnectReason().contains("accessToken"));
        } finally {
            if (prevProp == null) {
                System.clearProperty("ostinato.sessionServerUrl");
            } else {
                System.setProperty("ostinato.sessionServerUrl", prevProp);
            }
            mockMojang.stop(0);
        }
    }

    @Test
    public void fleetControllerHttpRestWebSocketAndStdinWorkEndToEnd() throws Exception {
        HeadlessTickScheduler scheduler = new HeadlessTickScheduler(2);
        List<String> commandsReceived = new CopyOnWriteArrayList<>();

        IHeadlessAgentFactory fakeAgentFactory = (context, host) -> new IHeadlessAgent() {
            private String task = "idle";

            @Override
            public void onAttach(BotContext ctx, IHeadlessHost h) {
            }

            @Override
            public void onTick() {
            }

            @Override
            public void onCommand(String command) {
                commandsReceived.add(context.botId() + ":" + command);
                task = "@stop".equals(command) ? "idle" : "running:" + command;
            }

            @Override
            public String currentTask() {
                return task;
            }
        };

        HeadlessFleetController fleet = new HeadlessFleetController(
                scheduler,
                fakeAgentFactory,
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

        fleet.spawnBot(HeadlessAccount.offline("Bot1"), null, null, null);
        fleet.spawnBot(HeadlessAccount.offline("Bot2"), null, null, null);

        try (HeadlessControlServer server = new HeadlessControlServer(fleet, 0)) {
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newHttpClient();

            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            JsonObject healthJson = JsonParser.parseString(health.body()).getAsJsonObject();
            assertEquals(2, healthJson.get("managedBots").getAsInt());

            HttpResponse<String> botsResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/bots")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, botsResp.statusCode());
            assertEquals(2, JsonParser.parseString(botsResp.body()).getAsJsonObject().getAsJsonArray("bots").size());

            HttpResponse<String> cmdResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/bots/Bot1/command"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"@mine oak_log\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cmdResp.statusCode());
            assertTrue(commandsReceived.contains("Bot1:@mine oak_log"));

            HttpResponse<String> spawnResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/bots/spawn"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"Bot3\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, spawnResp.statusCode());
            assertEquals(3, fleet.botCount());

            HttpResponse<String> detailResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/bots/Bot1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, detailResp.statusCode());
            JsonObject detailJson = JsonParser.parseString(detailResp.body()).getAsJsonObject();
            assertEquals("Bot1", detailJson.get("username").getAsString());
            assertEquals(20.0f, detailJson.get("health").getAsFloat(), 0.01f);
            assertTrue(detailJson.get("inventory").isJsonArray());
            assertTrue(detailJson.has("currentTask"));

            // Stdin console dispatch
            assertTrue(fleet.executeConsoleLine("cmd Bot2 @get dirt 4").contains("dispatched"));
            assertTrue(commandsReceived.contains("Bot2:@get dirt 4"));
            assertTrue(fleet.executeConsoleLine("list").contains("Bot3"));

            // RFC 6455 WebSocket control round-trip
            CompletableFuture<String> wsReply = new CompletableFuture<>();
            WebSocket ws = http.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/ws"), new WebSocket.Listener() {
                        private final StringBuilder buf = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            buf.append(data);
                            if (last) {
                                wsReply.complete(buf.toString());
                            }
                            webSocket.request(1);
                            return null;
                        }
                    })
                    .get(5, TimeUnit.SECONDS);
            ws.sendText("{\"action\":\"command\",\"bot\":\"all\",\"command\":\"@stop\"}", true).get(5, TimeUnit.SECONDS);
            JsonObject wsJson = JsonParser.parseString(wsReply.get(5, TimeUnit.SECONDS)).getAsJsonObject();
            assertTrue(wsJson.get("ok").getAsBoolean());
            assertTrue(commandsReceived.contains("Bot1:@stop"));
            assertTrue(commandsReceived.contains("Bot3:@stop"));
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

            // Stop dynamically spawned Bot3 via REST DELETE
            HttpResponse<String> delResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/bots/Bot3")).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, delResp.statusCode());
            assertEquals(2, fleet.botCount());

            assertEquals("shutdown requested", fleet.executeConsoleLine("shutdown"));
            assertTrue(fleet.isShutdownRequested());
        } finally {
            fleet.stopAllBots();
            scheduler.stop();
        }
    }

    private static final class FakeTransport implements HeadlessTransport {
        final List<Packet<?>> sentPackets = new CopyOnWriteArrayList<>();
        final AtomicBoolean connected = new AtomicBoolean(true);
        final CountDownLatch encryptionLatch = new CountDownLatch(1);

        @Override
        public void initiate(String host, int port, ClientLoginPacketListener loginListener) {
        }

        @Override
        public void send(Packet<?> packet) {
            sentPackets.add(packet);
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
            encryptionLatch.countDown();
        }

        @Override
        public void disconnect(String reason) {
            connected.set(false);
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        boolean awaitEncryption(long timeout, TimeUnit unit) throws InterruptedException {
            return encryptionLatch.await(timeout, unit);
        }
    }
}
