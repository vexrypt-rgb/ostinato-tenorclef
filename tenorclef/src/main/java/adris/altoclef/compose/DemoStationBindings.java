package adris.altoclef.compose;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Pure-Java registry mapping named semantic stations (e.g. {@code "pantry"}, {@code "serving barrel"})
 * to concrete container coordinates in a world/dimension. Rebinding these station names lets any
 * saved {@link DemoRoutine} replay at a different site without editing the routine itself.
 */
public final class DemoStationBindings {

    public static final String FILE_NAME = "station-bindings.properties";

    public record StationPos(String dimension, int x, int y, int z) {
        public StationPos {
            dimension = normalizeDimension(dimension);
        }

        public static String normalizeDimension(String raw) {
            if (raw == null || raw.isBlank()) {
                return "OVERWORLD";
            }
            return raw.trim().toUpperCase(Locale.ROOT);
        }

        public boolean isSameOrAdjacentHorizontal(StationPos other) {
            if (other == null || !dimension.equals(other.dimension) || y != other.y) {
                return false;
            }
            return Math.abs(x - other.x) + Math.abs(z - other.z) <= 1;
        }

        public String coordsText() {
            return x + "," + y + "," + z;
        }

        @Override
        public String toString() {
            return dimension.toLowerCase(Locale.ROOT) + "(" + coordsText() + ")";
        }
    }

    public record Binding(String stationKey, String displayName, StationPos pos) {
        public String describe() {
            return "\"" + displayName + "\"=" + pos;
        }
    }

    private final Path file; // null => in-memory only (unit tests)
    private final Map<String, Binding> byKey = new LinkedHashMap<>();
    private boolean loaded;

    public DemoStationBindings(Path file) {
        this.file = file;
        this.loaded = (file == null);
    }

    public static DemoStationBindings inMemory() {
        return new DemoStationBindings(null);
    }

    public static DemoStationBindings standard() {
        return new DemoStationBindings(Paths.get("altoclef", "demos", FILE_NAME));
    }

    public synchronized Binding bind(String rawStationName, String dimension, int x, int y, int z) {
        ensureLoaded();
        String display = DemoStep.normalizeStationDisplay(rawStationName);
        if (display.isEmpty()) {
            throw new IllegalArgumentException("Station name must not be blank");
        }
        String key = DemoStep.normalizeStationKey(display);
        Binding binding = new Binding(key, display, new StationPos(dimension, x, y, z));
        byKey.put(key, binding);
        save();
        return binding;
    }

    public synchronized Optional<Binding> resolve(String rawStationName) {
        ensureLoaded();
        if (rawStationName == null || rawStationName.isBlank()) {
            return Optional.empty();
        }
        try {
            String key = DemoStep.normalizeStationKey(rawStationName);
            return Optional.ofNullable(byKey.get(key));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Reverse lookup: returns a bound station matching the exact position (or an adjacent half of a double chest).
     */
    public synchronized Optional<Binding> findByPos(StationPos pos) {
        ensureLoaded();
        if (pos == null) {
            return Optional.empty();
        }
        for (Binding b : byKey.values()) {
            if (b.pos().equals(pos)) {
                return Optional.of(b);
            }
        }
        for (Binding b : byKey.values()) {
            if (b.pos().isSameOrAdjacentHorizontal(pos)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    public synchronized boolean unbind(String rawStationName) {
        ensureLoaded();
        try {
            String key = DemoStep.normalizeStationKey(rawStationName);
            boolean removed = byKey.remove(key) != null;
            if (removed) {
                save();
            }
            return removed;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public synchronized void clear() {
        byKey.clear();
        loaded = true;
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized Map<String, Binding> all() {
        ensureLoaded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(byKey));
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        byKey.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            Properties props = new Properties();
            props.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            for (String key : props.stringPropertyNames()) {
                String val = props.getProperty(key);
                if (val == null) continue;
                String[] parts = val.split("\\|", 5);
                if (parts.length != 5) continue;
                try {
                    String display = DemoStep.normalizeStationDisplay(parts[0]);
                    String normKey = DemoStep.normalizeStationKey(display.isEmpty() ? key : display);
                    String dim = StationPos.normalizeDimension(parts[1]);
                    int x = Integer.parseInt(parts[2].trim());
                    int y = Integer.parseInt(parts[3].trim());
                    int z = Integer.parseInt(parts[4].trim());
                    byKey.put(normKey, new Binding(normKey, display.isEmpty() ? normKey : display, new StationPos(dim, x, y, z)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties props = new Properties();
            for (Binding b : byKey.values()) {
                props.setProperty(b.stationKey(),
                        b.displayName() + "|" + b.pos().dimension() + "|"
                                + b.pos().x() + "|" + b.pos().y() + "|" + b.pos().z());
            }
            StringWriter sw = new StringWriter();
            props.store(sw, "TenorClef teach-by-demonstration station bindings");
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, sw.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
