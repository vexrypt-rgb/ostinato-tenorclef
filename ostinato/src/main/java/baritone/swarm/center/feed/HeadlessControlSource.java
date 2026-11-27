package baritone.swarm.center.feed;

import baritone.swarm.center.channel.HeadlessEndpoint;
import baritone.swarm.center.channel.HostInfo;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Ostinato: {@link SwarmSource} that discovers and streams live telemetry from one or more
 * {@code HeadlessControlServer} endpoints via WebSocket {@code /ws} ({@code subscribe}) with HTTP
 * {@code GET /center/snapshot} fallback. Pure JDK; closing it leaves headless bots running.
 */
public final class HeadlessControlSource implements SwarmSource, AutoCloseable {

    private static final long HTTP_FALLBACK_POLL_MS = 450L;
    private static final long RECONNECT_BACKOFF_MS = 3_000L;

    private final List<HeadlessEndpoint> endpoints;
    private final LongSupplier clock;
    private final HttpClient http;
    private final Alerts.Stuck stuck = new Alerts.Stuck();
    private final Map<HeadlessEndpoint, EndpointState> states = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, HeadlessEndpoint> botOwners = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private static final class EndpointState {
        volatile String lastSnapshotJson;
        volatile long lastReceivedAtMs;
        volatile String lastError;
        volatile WebSocket ws;
        volatile boolean connecting;
        volatile long nextConnectAttemptMs;
        final StringBuilder frameBuf = new StringBuilder();
    }

    public HeadlessControlSource(List<HeadlessEndpoint> endpoints, LongSupplier clock) {
        this.endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();
        for (HeadlessEndpoint ep : this.endpoints) {
            states.put(ep, new EndpointState());
        }
    }

    public List<HeadlessEndpoint> endpoints() {
        return endpoints;
    }

    public HeadlessEndpoint endpointForBot(String botName) {
        if (botName == null || botName.isBlank()) {
            return null;
        }
        return botOwners.get(botName.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public SwarmView snapshot() {
        return collectSnapshot(false);
    }

    /** Forces an immediate HTTP refresh across endpoints before building the snapshot. */
    public SwarmView refreshNow() {
        return collectSnapshot(true);
    }

    private SwarmView collectSnapshot(boolean forceHttp) {
        long now = clock.getAsLong();
        List<String> problems = new ArrayList<>();
        Map<String, SwarmView.BotState> mergedBots = new LinkedHashMap<>();
        CatalogFeed catalog = null;
        HostInfo host = null;
        CoordinatorFeed coordinator = null;
        List<CoordinatorFeed.Lease> leases = new ArrayList<>();
        List<CoordinatorFeed.Observation> observations = new ArrayList<>();
        int obsTotal = 0;

        for (HeadlessEndpoint ep : endpoints) {
            EndpointState st = states.get(ep);
            ensureSubscribed(ep, st, now);
            boolean needHttp = forceHttp
                    || st.lastSnapshotJson == null
                    || (st.ws == null && now - st.lastReceivedAtMs >= HTTP_FALLBACK_POLL_MS);
            if (needHttp && !closed.get()) {
                pollHttp(ep, st, now);
            }
            String raw = st.lastSnapshotJson;
            if (raw == null) {
                if (st.lastError != null) {
                    problems.add(ep.label() + ": " + st.lastError);
                }
                continue;
            }
            if (st.lastError != null && now - st.lastReceivedAtMs > CenterFeed.FRESH_MS) {
                problems.add(ep.label() + ": " + st.lastError);
            }
            try {
                Map<String, Object> root = Json.object(raw);
                for (Map<String, Object> botMap : Json.objs(root, "bots")) {
                    BotFeed bf = BotFeed.fromJson(Json.write(botMap));
                    long age = Math.max(0L, now - bf.writtenAtMs());
                    SwarmView.BotState bs = new SwarmView.BotState(bf, age, SwarmView.BotState.freshness(age));
                    String key = bf.name().toLowerCase(Locale.ROOT);
                    mergedBots.put(key, bs);
                    botOwners.put(key, ep);
                }
                if (catalog == null) {
                    Map<String, Object> catMap = Json.obj(root, "catalog");
                    if (catMap != null) {
                        catalog = CatalogFeed.fromJson(Json.write(catMap));
                    }
                }
                if (host == null) {
                    Map<String, Object> hostMap = Json.obj(root, "host");
                    if (hostMap != null) {
                        host = HostInfo.fromJson(Json.write(hostMap));
                    }
                }
                Map<String, Object> coordMap = Json.obj(root, "coordinator");
                if (coordMap != null) {
                    CoordinatorFeed cf = CoordinatorFeed.fromJson(Json.write(coordMap));
                    if (coordinator == null) {
                        coordinator = cf;
                    }
                    leases.addAll(cf.leases());
                    observations.addAll(cf.observations());
                    obsTotal += cf.observationTotal();
                }
            } catch (IllegalArgumentException e) {
                problems.add(ep.label() + ": " + e.getMessage());
            }
        }

        List<SwarmView.BotState> bots = new ArrayList<>(mergedBots.values());
        bots.sort(Comparator.comparing(b -> b.name().toLowerCase(Locale.ROOT)));
        CoordinatorFeed mergedCoord = coordinator == null ? null : new CoordinatorFeed(
                coordinator.self(), now, coordinator.seq(), coordinator.mode(), coordinator.world(),
                leases, observations, Math.max(obsTotal, observations.size()));
        List<Alert> alerts = Alerts.derive(bots, mergedCoord, stuck, now);
        return new SwarmView(now, bots, mergedCoord, catalog, host, Map.of(), alerts, problems);
    }

    private void pollHttp(HeadlessEndpoint ep, EndpointState st, long now) {
        try {
            HttpRequest req = HttpRequest.newBuilder(ep.httpUri("/center/snapshot"))
                    .timeout(Duration.ofMillis(1200))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                st.lastSnapshotJson = resp.body();
                st.lastReceivedAtMs = now;
                st.lastError = null;
            } else {
                st.lastError = "HTTP " + resp.statusCode();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            st.lastError = "interrupted";
        } catch (IOException | RuntimeException e) {
            st.lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private void ensureSubscribed(HeadlessEndpoint ep, EndpointState st, long now) {
        if (closed.get() || st.ws != null || st.connecting || now < st.nextConnectAttemptMs) {
            return;
        }
        st.connecting = true;
        st.nextConnectAttemptMs = now + RECONNECT_BACKOFF_MS;
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(1500))
                .buildAsync(ep.wsUri(), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        st.ws = webSocket;
                        st.connecting = false;
                        st.lastError = null;
                        webSocket.sendText("{\"action\":\"subscribe\",\"intervalMs\":500}", true);
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        synchronized (st.frameBuf) {
                            st.frameBuf.append(data);
                            if (last) {
                                String payload = st.frameBuf.toString();
                                st.frameBuf.setLength(0);
                                handleWsPayload(st, payload);
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        st.ws = null;
                        st.connecting = false;
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        st.ws = null;
                        st.connecting = false;
                        st.lastError = error == null || error.getMessage() == null ? "ws error" : error.getMessage();
                    }
                })
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        st.connecting = false;
                        st.ws = null;
                    }
                });
    }

    private void handleWsPayload(EndpointState st, String payload) {
        try {
            Map<String, Object> m = Json.object(payload);
            if (!Json.bool(m, "ok", false)) {
                return;
            }
            String event = Json.str(m, "event", "");
            String action = Json.str(m, "action", "");
            if ("snapshot".equals(event) || "snapshot".equals(action)) {
                Map<String, Object> data = Json.obj(m, "data");
                if (data != null) {
                    st.lastSnapshotJson = Json.write(data);
                    st.lastReceivedAtMs = clock.getAsLong();
                    st.lastError = null;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed or non-snapshot frames
        }
    }

    @Override
    public void close() {
        closed.set(true);
        for (EndpointState st : states.values()) {
            WebSocket ws = st.ws;
            st.ws = null;
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "closing");
                } catch (RuntimeException ignored) {
                    ws.abort();
                }
            }
        }
    }
}
