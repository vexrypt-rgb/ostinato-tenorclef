package baritone.swarm.center.feed;

import baritone.swarm.center.channel.HostInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Ostinato: combines multiple {@link SwarmSource} instances (for example a graphical {@link FeedReader} and a
 * {@link HeadlessControlSource}) into one unified {@link SwarmView}.
 */
public final class CompositeSwarmSource implements SwarmSource, AutoCloseable {

    private final List<SwarmSource> sources;
    private final LongSupplier clock;
    private final Alerts.Stuck stuck = new Alerts.Stuck();

    public CompositeSwarmSource(List<SwarmSource> sources, LongSupplier clock) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    @Override
    public SwarmView snapshot() {
        long now = clock.getAsLong();
        Map<String, SwarmView.BotState> botsByName = new LinkedHashMap<>();
        Map<String, TerrainFeed> terrain = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        CatalogFeed catalog = null;
        HostInfo host = null;
        CoordinatorFeed firstCoord = null;
        List<CoordinatorFeed.Lease> leases = new ArrayList<>();
        List<CoordinatorFeed.Observation> observations = new ArrayList<>();
        int obsTotal = 0;

        for (SwarmSource src : sources) {
            SwarmView v = src.snapshot();
            if (v == null) {
                continue;
            }
            for (SwarmView.BotState b : v.bots()) {
                String k = b.name().toLowerCase(Locale.ROOT);
                SwarmView.BotState prev = botsByName.get(k);
                if (prev == null || b.ageMs() < prev.ageMs()) {
                    botsByName.put(k, b);
                }
            }
            terrain.putAll(v.terrain());
            problems.addAll(v.problems());
            if (catalog == null && v.catalog() != null) {
                catalog = v.catalog();
            }
            if (host == null && v.host() != null) {
                host = v.host();
            }
            if (v.coordinator() != null) {
                if (firstCoord == null) {
                    firstCoord = v.coordinator();
                }
                leases.addAll(v.coordinator().leases());
                observations.addAll(v.coordinator().observations());
                obsTotal += v.coordinator().observationTotal();
            }
        }

        List<SwarmView.BotState> bots = new ArrayList<>(botsByName.values());
        bots.sort(Comparator.comparing(b -> b.name().toLowerCase(Locale.ROOT)));
        CoordinatorFeed coord = firstCoord == null ? null : new CoordinatorFeed(
                firstCoord.self(), now, firstCoord.seq(), firstCoord.mode(), firstCoord.world(),
                leases, observations, Math.max(obsTotal, observations.size()));
        List<Alert> alerts = Alerts.derive(bots, coord, stuck, now);
        return new SwarmView(now, bots, coord, catalog, host, terrain, alerts, problems);
    }

    @Override
    public void close() throws Exception {
        for (SwarmSource src : sources) {
            if (src instanceof AutoCloseable c) {
                c.close();
            }
        }
    }
}
