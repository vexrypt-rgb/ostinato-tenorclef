package baritone.gui.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: P3 per-server vs global settings override store (design doc: In-game menus —
 * "A searchable settings page shows type, default, current, per-server vs global, and reset").
 * Pure (no Minecraft imports, headless-testable): stores per-server overrides in
 * {@code <baritoneDir>/servers/<serverKey>.txt} using the same {@link SettingsFileMerger} format as {@code settings.txt}.
 */
public final class ServerSettingsStore {

    public enum Scope {
        DEFAULT("DEF"),
        GLOBAL("GLB"),
        SERVER("SRV");

        public final String badge;

        Scope(String badge) {
            this.badge = badge;
        }
    }

    private String activeServer = "";
    private final Map<String, String> activeOverrides = new LinkedHashMap<>();
    private final Map<String, String> globalBackup = new LinkedHashMap<>();

    public synchronized String activeServer() {
        return activeServer;
    }

    public synchronized Map<String, String> activeOverrides() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(activeOverrides));
    }

    public synchronized boolean isServerOverride(String settingName) {
        return settingName != null && activeOverrides.containsKey(settingName.toLowerCase(Locale.ROOT));
    }

    public synchronized Scope scopeOf(String settingName, boolean isModified) {
        if (isServerOverride(settingName)) {
            return Scope.SERVER;
        }
        return isModified ? Scope.GLOBAL : Scope.DEFAULT;
    }

    /** Sanitizes a server address or world name into a safe file stem. */
    public static String serverKey(String rawServer) {
        if (rawServer == null || rawServer.isBlank()) {
            return "singleplayer";
        }
        String clean = rawServer.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) {
            return "singleplayer";
        }
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    public static Path fileFor(Path baritoneDir, String serverKey) {
        return baritoneDir.resolve("servers").resolve(serverKey(serverKey) + ".txt");
    }

    /** Loads all {@code key value} overrides from {@code <baritoneDir>/servers/<serverKey>.txt}. */
    public static Map<String, String> readOverrides(Path baritoneDir, String serverKey) {
        Map<String, String> out = new LinkedHashMap<>();
        if (baritoneDir == null) {
            return out;
        }
        Path file = fileFor(baritoneDir, serverKey);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int sp = line.indexOf(' ');
                if (sp <= 0) {
                    continue;
                }
                String k = line.substring(0, sp).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(sp + 1).trim();
                if (!k.isEmpty()) {
                    out.put(k, v);
                }
            }
        } catch (IOException ignored) {
            // Leave empty map if unreadable
        }
        return out;
    }

    /** Writes or removes (when {@code valueLine == null}) one per-server override on disk. */
    public static void writeOverride(Path baritoneDir, String serverKey, String key, String valueLine) throws IOException {
        if (baritoneDir == null || key == null || key.isBlank()) {
            return;
        }
        Path file = fileFor(baritoneDir, serverKey);
        List<String> lines = Files.isRegularFile(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put(key.toLowerCase(Locale.ROOT), valueLine);
        List<String> merged = SettingsFileMerger.merge(lines, updates);
        Files.createDirectories(file.getParent());
        Files.write(file, merged, StandardCharsets.UTF_8);
    }

    /** Activates a server context, remembering pre-override global values via {@code readCurrent} and applying overrides via {@code apply}. */
    public synchronized int enterServer(Path baritoneDir, String rawServer,
                                        java.util.function.Function<String, String> readCurrent,
                                        java.util.function.BiConsumer<String, String> apply) {
        String key = serverKey(rawServer);
        activeServer = key;
        activeOverrides.clear();
        globalBackup.clear();
        Map<String, String> loaded = readOverrides(baritoneDir, key);
        int applied = 0;
        for (Map.Entry<String, String> e : loaded.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (readCurrent != null) {
                String prev = readCurrent.apply(k);
                if (prev != null) {
                    globalBackup.put(k, prev);
                }
            }
            activeOverrides.put(k, v);
            if (apply != null) {
                apply.accept(k, v);
            }
            applied++;
        }
        return applied;
    }

    /** Restores backed-up global values when leaving the server. */
    public synchronized int leaveServer(java.util.function.BiConsumer<String, String> apply) {
        int restored = 0;
        if (apply != null) {
            for (Map.Entry<String, String> e : globalBackup.entrySet()) {
                apply.accept(e.getKey(), e.getValue());
                restored++;
            }
        }
        globalBackup.clear();
        activeOverrides.clear();
        activeServer = "";
        return restored;
    }

    /** Pins or unpins a setting as a per-server override for the active server. Returns the new {@link Scope}. */
    public synchronized Scope toggleServerOverride(Path baritoneDir, String settingName, String fullValueLine,
                                                   String rawValue, boolean isModified) throws IOException {
        String k = settingName.toLowerCase(Locale.ROOT);
        String srv = activeServer.isEmpty() ? "singleplayer" : activeServer;
        if (activeOverrides.containsKey(k)) {
            activeOverrides.remove(k);
            writeOverride(baritoneDir, srv, k, null);
            return isModified ? Scope.GLOBAL : Scope.DEFAULT;
        }
        activeOverrides.put(k, rawValue);
        writeOverride(baritoneDir, srv, k, fullValueLine);
        return Scope.SERVER;
    }
}
