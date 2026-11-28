package adris.altoclef.tasks.replenish;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Persists active replenishment order state and courier inventory accounting across
 * task interruptions and full bot restarts so in-transit items are reconstructed
 * accurately without duplicate withdrawals or duplicate deliveries.
 */
public final class ReplenishmentJournal {

    public record Entry(
            String ruleKey,
            long orderId,
            boolean activeOrder,
            String phase,
            int baselinePersonalCount,
            int carriedForOrder,
            int plannedDeficit,
            boolean partialShortage,
            int lastKnownDestCount,
            long lastDestCheckMs,
            int lastKnownSourceCount,
            long lastSourceCheckMs,
            long nextCheckMs,
            long backoffUntilMs,
            int consecutiveShortages,
            int consecutiveFailures,
            String lastReport,
            ReplenishmentMetrics.Snapshot metrics) {
    }

    public interface Store {
        Optional<Entry> load(String ruleKey);

        void save(Entry entry);

        void clear(String ruleKey);
    }

    private ReplenishmentJournal() {
    }

    /** In-memory store for pure unit tests. */
    public static final class MemoryStore implements Store {
        private final java.util.Map<String, Entry> map = new java.util.HashMap<>();

        @Override
        public Optional<Entry> load(String ruleKey) {
            return Optional.ofNullable(map.get(ruleKey));
        }

        @Override
        public void save(Entry entry) {
            if (entry != null && entry.ruleKey() != null) {
                map.put(entry.ruleKey(), entry);
            }
        }

        @Override
        public void clear(String ruleKey) {
            map.remove(ruleKey);
        }
    }

    /** Atomic file-backed store ({@code altoclef/replenish-journal.properties}). */
    public static final class FileStore implements Store {
        private final Path file;

        public FileStore(Path file) {
            this.file = file;
        }

        @Override
        public synchronized Optional<Entry> load(String ruleKey) {
            if (ruleKey == null || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Properties props = new Properties();
                props.load(new StringReader(content));
                String storedKey = props.getProperty("ruleKey");
                if (!ruleKey.equals(storedKey)) {
                    return Optional.empty();
                }
                ReplenishmentMetrics.Snapshot metrics = new ReplenishmentMetrics.Snapshot(
                        getInt(props, "m.destChecks", 0),
                        getInt(props, "m.destOpens", 0),
                        getInt(props, "m.sourceOpens", 0),
                        getInt(props, "m.sourceVisits", 0),
                        getInt(props, "m.destDeliveries", 0),
                        getInt(props, "m.sourceReturns", 0),
                        getInt(props, "m.emptyTrips", 0),
                        getInt(props, "m.unnecessaryChestOpens", 0),
                        getInt(props, "m.itemsWithdrawn", 0),
                        getInt(props, "m.itemsDeposited", 0),
                        getInt(props, "m.itemsReturned", 0),
                        getInt(props, "m.completedRefills", 0),
                        getInt(props, "m.shortageReports", 0),
                        getInt(props, "m.interruptRecoveries", 0),
                        getInt(props, "m.failedAttempts", 0));
                return Optional.of(new Entry(
                        storedKey,
                        getLong(props, "orderId", 0L),
                        Boolean.parseBoolean(props.getProperty("activeOrder", "false")),
                        props.getProperty("phase", "IDLE_MONITORING"),
                        getInt(props, "baselinePersonalCount", 0),
                        getInt(props, "carriedForOrder", 0),
                        getInt(props, "plannedDeficit", 0),
                        Boolean.parseBoolean(props.getProperty("partialShortage", "false")),
                        getInt(props, "lastKnownDestCount", -1),
                        getLong(props, "lastDestCheckMs", 0L),
                        getInt(props, "lastKnownSourceCount", -1),
                        getLong(props, "lastSourceCheckMs", 0L),
                        getLong(props, "nextCheckMs", 0L),
                        getLong(props, "backoffUntilMs", 0L),
                        getInt(props, "consecutiveShortages", 0),
                        getInt(props, "consecutiveFailures", 0),
                        props.getProperty("lastReport", ""),
                        metrics));
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        }

        @Override
        public synchronized void save(Entry e) {
            if (e == null) return;
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Properties props = new Properties();
                props.setProperty("ruleKey", e.ruleKey());
                props.setProperty("orderId", Long.toString(e.orderId()));
                props.setProperty("activeOrder", Boolean.toString(e.activeOrder()));
                props.setProperty("phase", e.phase());
                props.setProperty("baselinePersonalCount", Integer.toString(e.baselinePersonalCount()));
                props.setProperty("carriedForOrder", Integer.toString(e.carriedForOrder()));
                props.setProperty("plannedDeficit", Integer.toString(e.plannedDeficit()));
                props.setProperty("partialShortage", Boolean.toString(e.partialShortage()));
                props.setProperty("lastKnownDestCount", Integer.toString(e.lastKnownDestCount()));
                props.setProperty("lastDestCheckMs", Long.toString(e.lastDestCheckMs()));
                props.setProperty("lastKnownSourceCount", Integer.toString(e.lastKnownSourceCount()));
                props.setProperty("lastSourceCheckMs", Long.toString(e.lastSourceCheckMs()));
                props.setProperty("nextCheckMs", Long.toString(e.nextCheckMs()));
                props.setProperty("backoffUntilMs", Long.toString(e.backoffUntilMs()));
                props.setProperty("consecutiveShortages", Integer.toString(e.consecutiveShortages()));
                props.setProperty("consecutiveFailures", Integer.toString(e.consecutiveFailures()));
                props.setProperty("lastReport", e.lastReport() == null ? "" : e.lastReport());
                ReplenishmentMetrics.Snapshot m = e.metrics();
                if (m != null) {
                    props.setProperty("m.destChecks", Integer.toString(m.destChecks()));
                    props.setProperty("m.destOpens", Integer.toString(m.destOpens()));
                    props.setProperty("m.sourceOpens", Integer.toString(m.sourceOpens()));
                    props.setProperty("m.sourceVisits", Integer.toString(m.sourceVisits()));
                    props.setProperty("m.destDeliveries", Integer.toString(m.destDeliveries()));
                    props.setProperty("m.sourceReturns", Integer.toString(m.sourceReturns()));
                    props.setProperty("m.emptyTrips", Integer.toString(m.emptyTrips()));
                    props.setProperty("m.unnecessaryChestOpens", Integer.toString(m.unnecessaryChestOpens()));
                    props.setProperty("m.itemsWithdrawn", Integer.toString(m.itemsWithdrawn()));
                    props.setProperty("m.itemsDeposited", Integer.toString(m.itemsDeposited()));
                    props.setProperty("m.itemsReturned", Integer.toString(m.itemsReturned()));
                    props.setProperty("m.completedRefills", Integer.toString(m.completedRefills()));
                    props.setProperty("m.shortageReports", Integer.toString(m.shortageReports()));
                    props.setProperty("m.interruptRecoveries", Integer.toString(m.interruptRecoveries()));
                    props.setProperty("m.failedAttempts", Integer.toString(m.failedAttempts()));
                }
                StringWriter sw = new StringWriter();
                props.store(sw, "TenorClef worksite replenishment journal");
                Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
                Files.writeString(tmp, sw.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }

        @Override
        public synchronized void clear(String ruleKey) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }

        private static int getInt(Properties p, String key, int def) {
            try {
                return Integer.parseInt(p.getProperty(key, Integer.toString(def)));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static long getLong(Properties p, String key, long def) {
            try {
                return Long.parseLong(p.getProperty(key, Long.toString(def)));
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }
}
