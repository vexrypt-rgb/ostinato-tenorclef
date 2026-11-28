package adris.altoclef.tasks.replenish;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.testrun2.util.GameFiles;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.IBaritone;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.IWorldData;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Persistent registry of named storage chests (e.g. {@code home}, {@code quarry}) for TenorClef's
 * worksite replenishment policy, with automatic fallback to Baritone's named waypoints via {@code baritone.api.*}.
 */
public final class NamedChestRegistry {

    public static final String FILE_NAME = "replenish-chests.properties";

    public record NamedChest(String name, Dimension dimension, BlockPos pos) {
        public String describe() {
            return name + "=" + dimension.name().toLowerCase(Locale.ROOT)
                    + "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
        }
    }

    private final Path file;
    private final Map<String, NamedChest> entries = new LinkedHashMap<>();
    private boolean loaded = false;

    public NamedChestRegistry(Path file) {
        this.file = file;
    }

    public static NamedChestRegistry defaultRegistry() {
        return new NamedChestRegistry(GameFiles.dir().resolve(FILE_NAME));
    }

    public synchronized void register(String rawName, Dimension dimension, BlockPos pos) {
        ensureLoaded();
        String name = ReplenishmentRule.normalizeChestName(rawName, "chest");
        Dimension dim = dimension == null ? Dimension.OVERWORLD : dimension;
        entries.put(name, new NamedChest(name, dim, pos.toImmutable()));
        save();
    }

    public synchronized boolean remove(String rawName) {
        ensureLoaded();
        String name = ReplenishmentRule.normalizeChestName(rawName, "chest");
        boolean removed = entries.remove(name) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized Map<String, NamedChest> all() {
        ensureLoaded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * Resolves {@code rawName} from the named chest registry first, falling back to a matching
     * Baritone waypoint in the current world (strictly via {@link baritone.api.cache.IWaypoint}).
     */
    public synchronized Optional<NamedChest> resolve(AltoClef mod, String rawName) {
        ensureLoaded();
        String name;
        try {
            name = ReplenishmentRule.normalizeChestName(rawName, "chest");
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        NamedChest direct = entries.get(name);
        if (direct != null) {
            return Optional.of(direct);
        }
        // Fallback: check Baritone waypoints for current world via baritone.api.*
        if (mod != null) {
            try {
                IBaritone baritone = mod.getClientBaritone();
                IWorldData worldData = baritone == null || baritone.getWorldProvider() == null
                        ? null : baritone.getWorldProvider().getCurrentWorld();
                if (worldData != null && worldData.getWaypoints() != null) {
                    IWaypoint best = null;
                    for (IWaypoint wp : worldData.getWaypoints().getAllWaypoints()) {
                        if (wp != null && wp.getName() != null && wp.getName().trim().equalsIgnoreCase(name)) {
                            if (best == null || wp.getCreationTimestamp() > best.getCreationTimestamp()) {
                                best = wp;
                            }
                        }
                    }
                    if (best != null && best.getLocation() != null) {
                        BetterBlockPos loc = best.getLocation();
                        Dimension dim = WorldHelper.getCurrentDimension();
                        return Optional.of(new NamedChest(name, dim, new BlockPos(loc.x, loc.y, loc.z)));
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return Optional.empty();
    }

    public synchronized void clear() {
        entries.clear();
        loaded = true;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        entries.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Properties props = new Properties();
            props.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            for (String key : props.stringPropertyNames()) {
                String val = props.getProperty(key);
                if (val == null) continue;
                String[] parts = val.split(",");
                if (parts.length != 4) continue;
                try {
                    Dimension dim = Dimension.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
                    int x = Integer.parseInt(parts[1].trim());
                    int y = Integer.parseInt(parts[2].trim());
                    int z = Integer.parseInt(parts[3].trim());
                    String norm = ReplenishmentRule.normalizeChestName(key, "chest");
                    entries.put(norm, new NamedChest(norm, dim, new BlockPos(x, y, z)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties props = new Properties();
            for (NamedChest nc : entries.values()) {
                props.setProperty(nc.name(), nc.dimension().name() + ","
                        + nc.pos().getX() + "," + nc.pos().getY() + "," + nc.pos().getZ());
            }
            StringWriter sw = new StringWriter();
            props.store(sw, "TenorClef named chests for worksite replenishment");
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, sw.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
