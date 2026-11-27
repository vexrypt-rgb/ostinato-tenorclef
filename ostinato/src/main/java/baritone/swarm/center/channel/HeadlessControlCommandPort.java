package baritone.swarm.center.channel;

import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.Json;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Ostinato: {@link CommandPort} backed by one or more {@code HeadlessControlServer} endpoints via
 * {@code POST /center/request}. Never throws; transport failures return a refused {@link CenterReply}.
 */
public final class HeadlessControlCommandPort implements CommandPort {

    public static final int TIMEOUT_MS = 5_000;

    private final List<HeadlessEndpoint> endpoints;
    private final Function<String, HeadlessEndpoint> routeLookup;
    private final LongSupplier clock;
    private final HttpClient http;

    public HeadlessControlCommandPort(
            List<HeadlessEndpoint> endpoints,
            Function<String, HeadlessEndpoint> routeLookup,
            LongSupplier clock) {
        this.endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        this.routeLookup = routeLookup;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2000)).build();
    }

    @Override
    public CenterReply send(CenterRequest request) {
        long now = clock.getAsLong();
        if (endpoints.isEmpty()) {
            return CenterReply.refused(request.id(), "no headless control endpoint", now);
        }
        HeadlessEndpoint ep = resolveEndpoint(request);
        String body = Json.write(Json.map(
                "v", CenterFeed.VERSION,
                "id", request.id(),
                "atMs", request.atMs(),
                "op", request.op().name().toLowerCase(Locale.ROOT),
                "command", request.command(),
                "formId", request.formId(),
                "values", request.values(),
                "bot", request.bot(),
                "line", request.line(),
                "dryRun", request.dryRun()));
        try {
            HttpRequest httpReq = HttpRequest.newBuilder(ep.httpUri("/center/request"))
                    .timeout(Duration.ofMillis(TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.body() == null || resp.body().isBlank()) {
                return CenterReply.refused(request.id(), "empty reply (" + resp.statusCode() + ")", clock.getAsLong());
            }
            Map<String, Object> m = Json.object(resp.body());
            if (resp.statusCode() >= 400) {
                return CenterReply.refused(request.id(), Json.str(m, "error", "HTTP " + resp.statusCode()),
                        clock.getAsLong());
            }
            return new CenterReply(
                    Json.str(m, "id", request.id()),
                    Json.num(m, "atMs", clock.getAsLong()),
                    Json.bool(m, "admitted", false),
                    Json.bool(m, "executed", false),
                    Json.str(m, "reason", "unknown"),
                    Json.str(m, "line", ""),
                    Json.str(m, "host", ep.label()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CenterReply.refused(request.id(), "interrupted", clock.getAsLong());
        } catch (IOException | RuntimeException e) {
            return CenterReply.refused(request.id(), "headless endpoint unreachable: " + ep.label(), clock.getAsLong());
        }
    }

    private HeadlessEndpoint resolveEndpoint(CenterRequest request) {
        if (routeLookup != null && request.bot() != null && !request.bot().isBlank()) {
            HeadlessEndpoint found = routeLookup.apply(request.bot());
            if (found != null) {
                return found;
            }
        }
        return endpoints.get(0);
    }
}
