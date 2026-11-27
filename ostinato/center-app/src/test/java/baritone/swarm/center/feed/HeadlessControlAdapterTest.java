package baritone.swarm.center.feed;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.app.AppStyle;
import baritone.swarm.center.app.CenterShell;
import baritone.swarm.center.app.Commands;
import baritone.swarm.center.app.fake.FakeCommandPort;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.channel.CompositeCommandPort;
import baritone.swarm.center.channel.HeadlessControlCommandPort;
import baritone.swarm.center.channel.HeadlessEndpoint;
import baritone.swarm.center.channel.HostInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JDK verification for {@link HeadlessEndpoint}, {@link HeadlessControlSource},
 * {@link CompositeSwarmSource}, {@link HeadlessControlCommandPort}, and {@link CompositeCommandPort}
 * wired into {@link CenterShell}.
 */
public class HeadlessControlAdapterTest {

    @Test
    public void parsesEndpointsDiscoversMultipleControlServersRoutesCommandsAndRendersInShell() throws Exception {
        AtomicLong clock = new AtomicLong(2_000_000L);
        List<Map<String, Object>> ep1Requests = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> ep2Requests = new CopyOnWriteArrayList<>();

        HttpServer ep1Server = startMockControlServer(clock, ep1Requests, now -> {
            BotFeed alpha = new BotFeed(
                    "HeadlessAlpha", now, 1, "127.0.0.1:25570", "headless", true, true,
                    new BotFeed.Pos('o', 12.5, 64.0, -8.5, 90f),
                    20, 20, 20, false, "idle", BotFeed.Route.IDLE,
                    new BotFeed.Inventory(30, 36, 4, List.of(new BotFeed.Item("oak_log", 6)), 90, "iron_pickaxe"),
                    List.of(new BotFeed.Execution("c1", "goto 12 64 -8", "center", "succeeded", "confirmed", 100, "")),
                    List.of(), new BotFeed.Perf(4, 200, 12, 128, 1024, 0, 10, 0, 0),
                    new BotFeed.Link(true, 2, ""), 150);
            BotFeed beta = new BotFeed(
                    "HeadlessBeta", now, 1, "127.0.0.1:25570", "headless", false, false,
                    new BotFeed.Pos('o', 40.0, 64.0, 15.0, 0f),
                    18, 20, 18, false, "Mine",
                    new BotFeed.Route("pathing", "mine oak_log", new int[]{42, 64, 16}, 2.2, 1, 4, List.of()),
                    new BotFeed.Inventory(28, 36, 8, List.of(new BotFeed.Item("dirt", 12)), 85, "stone_pickaxe"),
                    List.of(),
                    List.of(new BotFeed.HeldLease("l1", 1, "v:o:42,64,16", "active", 30_000, true)),
                    new BotFeed.Perf(6, 200, 15, 130, 1024, 0, 10, 0, 0),
                    new BotFeed.Link(true, 2, ""), 200);
            HostInfo host = new HostInfo("HeadlessAlpha", now, 1, 0, 4242, now - 10_000);
            CatalogFeed catalog = new CatalogFeed("HeadlessAlpha", now, 1, FakeSwarm.CATALOG);
            CoordinatorFeed coord = new CoordinatorFeed(
                    "HeadlessAlpha", now, 1, "active", "world",
                    List.of(new CoordinatorFeed.Lease("l1", "v:o:42,64,16", "vein", 'o',
                            new int[]{42, 64, 16, 42, 65, 16}, "HeadlessBeta", 1, "granted",
                            "mine oak_log", "running", "confirmed", "")),
                    List.of(new CoordinatorFeed.Observation("v:o:42,64,16", "vein", 'o',
                            new int[]{42, 64, 16, 42, 65, 16}, "HeadlessBeta", 90, "oak_log", "confirmed", false, 500)),
                    1);
            return buildSnapshotJson(now, "127.0.0.1:25570", host, catalog, coord, List.of(alpha, beta));
        });

        HttpServer ep2Server = startMockControlServer(clock, ep2Requests, now -> {
            BotFeed gamma = new BotFeed(
                    "HeadlessGamma", now, 1, "127.0.0.1:25570", "headless", false, false,
                    new BotFeed.Pos('o', -20.0, 64.0, 30.0, 180f),
                    20, 20, 20, false, "idle", BotFeed.Route.IDLE,
                    BotFeed.Inventory.UNKNOWN, List.of(), List.of(),
                    new BotFeed.Perf(2, 200, 10, 110, 1024, 0, 10, 0, 0),
                    new BotFeed.Link(true, 2, ""), 120);
            HostInfo host = new HostInfo("HeadlessGamma", now, 1, 0, 4243, now - 5_000);
            CatalogFeed catalog = new CatalogFeed("HeadlessGamma", now, 1, FakeSwarm.CATALOG);
            CoordinatorFeed coord = new CoordinatorFeed("HeadlessGamma", now, 1, "idle", "world", List.of(), List.of(), 0);
            return buildSnapshotJson(now, "127.0.0.1:25570", host, catalog, coord, List.of(gamma));
        });

        int port1 = ep1Server.getAddress().getPort();
        int port2 = ep2Server.getAddress().getPort();
        HeadlessEndpoint ep1 = HeadlessEndpoint.parse(String.valueOf(port1));
        HeadlessEndpoint ep2 = HeadlessEndpoint.parse("127.0.0.1:" + port2);
        assertEquals("127.0.0.1", ep1.host());
        assertEquals(port1, ep1.port());
        assertEquals(port2, ep2.port());

        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            deadPort = probe.getLocalPort();
        }
        HeadlessEndpoint deadEp = HeadlessEndpoint.parse("127.0.0.1:" + deadPort);

        try (HeadlessControlSource sourceWithDead = new HeadlessControlSource(List.of(ep1, deadEp), clock::get);
             HeadlessControlSource source = new HeadlessControlSource(List.of(ep1, ep2), clock::get)) {
            SwarmView partial = sourceWithDead.snapshot();
            assertEquals(2, partial.bots().size());
            assertFalse("Unreachable endpoint should surface in problems", partial.problems().isEmpty());

            SwarmView live = source.snapshot();
            assertEquals(3, live.bots().size());
            assertNotNull(live.bot("HeadlessAlpha"));
            assertNotNull(live.bot("HeadlessBeta"));
            assertNotNull(live.bot("HeadlessGamma"));
            assertEquals(ep1, source.endpointForBot("HeadlessAlpha"));
            assertEquals(ep1, source.endpointForBot("HeadlessBeta"));
            assertEquals(ep2, source.endpointForBot("HeadlessGamma"));

            FakeSwarm graphical = FakeSwarm.threeBots(clock::get);
            CompositeSwarmSource compositeSource = new CompositeSwarmSource(List.of(graphical, source), clock::get);
            SwarmView combined = compositeSource.snapshot();
            assertEquals(6, combined.bots().size());
            assertNotNull(combined.bot("Copper"));
            assertNotNull(combined.bot("HeadlessGamma"));

            HeadlessControlCommandPort headlessPort = new HeadlessControlCommandPort(
                    List.of(ep1, ep2), source::endpointForBot, clock::get);
            FakeCommandPort graphicalPort = new FakeCommandPort("Copper");
            CompositeCommandPort compositePort = new CompositeCommandPort(
                    headlessPort,
                    graphicalPort,
                    bot -> source.endpointForBot(bot) != null,
                    () -> true);

            CenterReply dry = compositePort.send(CenterRequest.press(
                    "goto", "goto", Map.of("target", "10 64 20"), "HeadlessGamma", clock.get()).dry());
            assertEquals("preview", dry.reason());
            assertFalse(dry.executed());
            assertEquals(1, ep2Requests.size());
            assertEquals(0, ep1Requests.size());

            CenterReply lineReply = compositePort.send(CenterRequest.line("#swarm spawn Bot4", "HeadlessBeta", clock.get()));
            assertTrue(lineReply.admitted());
            assertTrue(lineReply.executed());
            assertEquals(1, ep1Requests.size());

            CenterReply gfxReply = compositePort.send(CenterRequest.press(
                    "goto", "goto", Map.of("target", "1 64 2"), "Azalea", clock.get()));
            assertTrue(gfxReply.admitted());
            assertEquals(1, graphicalPort.sent().size());

            Commands uiCommands = new Commands(headlessPort, Runnable::run, Runnable::run, clock::get);
            CenterShell shell = new CenterShell(new AppStyle(CenterTheme.DEFAULT_DARK), uiCommands, CenterLayout.WALL);
            shell.update(live);
            shell.component().setSize(1280, 860);
            layout(shell.component());
            layout(shell.component());

            assertEquals("Server 127.0.0.1:25570",
                    textOf(shell.component(), "header.server"));
            assertEquals("Host HeadlessAlpha", textOf(shell.component(), "header.host"));
            assertNotNull(find(shell.component(), "tile:HeadlessAlpha"));
            assertNotNull(find(shell.component(), "tile:HeadlessBeta"));
            assertNotNull(find(shell.component(), "tile:HeadlessGamma"));

            JTextComponent targetField = (JTextComponent) find(shell.component(), "tile:HeadlessBeta:field:goto:target");
            assertNotNull(targetField);
            targetField.setText("55 64 -12");
            AbstractButton gotoBtn = (AbstractButton) find(shell.component(), "tile:HeadlessBeta:button:goto");
            assertNotNull(gotoBtn);
            gotoBtn.doClick();

            assertEquals(2, ep1Requests.size());
            assertEquals("HeadlessBeta", ep1Requests.get(1).get("bot"));
            assertEquals("goto", ep1Requests.get(1).get("formId"));
        } finally {
            ep1Server.stop(0);
            ep2Server.stop(0);
        }
    }

    @FunctionalInterface
    private interface SnapshotSupplier {
        String build(long now);
    }

    private static HttpServer startMockControlServer(
            AtomicLong clock,
            List<Map<String, Object>> recordedRequests,
            SnapshotSupplier snapshots) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/center/snapshot", exchange -> {
            String body = snapshots.build(clock.get());
            respondJson(exchange, 200, body);
        });
        server.createContext("/center/request", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = Json.object(reqBody);
            recordedRequests.add(req);
            boolean dry = Json.bool(req, "dryRun", false);
            String line = Json.str(req, "line", "");
            if (line.isBlank()) {
                line = Json.str(req, "command", "cmd") + " " + Json.str(req, "bot", "");
            }
            String reply = Json.write(Json.map(
                    "v", CenterFeed.VERSION,
                    "id", Json.str(req, "id", "1"),
                    "atMs", clock.get(),
                    "admitted", !dry,
                    "executed", !dry,
                    "reason", dry ? "preview" : "accepted",
                    "line", line.trim(),
                    "host", "mock-host"));
            respondJson(exchange, 200, reply);
        });
        server.start();
        return server;
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String buildSnapshotJson(
            long now,
            String server,
            HostInfo host,
            CatalogFeed catalog,
            CoordinatorFeed coordinator,
            List<BotFeed> bots) {
        List<Object> botObjs = new ArrayList<>();
        for (BotFeed b : bots) {
            botObjs.add(Json.object(b.toJson()));
        }
        return Json.write(Json.map(
                "schema", CenterFeed.SCHEMA,
                "v", CenterFeed.VERSION,
                "writtenAtMs", now,
                "seq", 1L,
                "server", server,
                "host", Json.object(host.toJson()),
                "catalog", Json.object(catalog.toJson()),
                "coordinator", Json.object(coordinator.toJson()),
                "bots", botObjs));
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

    private static String textOf(Container root, String name) {
        Component c = find(root, name);
        assertNotNull("missing component " + name, c);
        if (c instanceof JLabel l) {
            return l.getText();
        }
        if (c instanceof JTextComponent t) {
            return t.getText();
        }
        return ((AbstractButton) c).getText();
    }

    private static void layout(Component root) {
        root.doLayout();
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                layout(child);
            }
        }
    }
}
