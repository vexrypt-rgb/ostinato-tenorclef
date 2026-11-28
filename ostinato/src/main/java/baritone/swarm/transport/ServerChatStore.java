/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Per-server persisted cache and manual override map for swarm chat parameters
 * (whisper template, max line length, and send rate). Stored in the bot's own
 * config directory keyed by normalized server address; manual overrides always win.
 */
public final class ServerChatStore {

    public record ServerProfile(String whisperTemplate, int maxLineChars, double ratePerSec, long updatedAtMs) {
        public static final ServerProfile EMPTY = new ServerProfile("", -1, -1.0, 0L);

        public boolean hasTemplate() {
            return whisperTemplate != null && !whisperTemplate.isBlank();
        }

        public boolean hasMaxLineChars() {
            return maxLineChars >= 128;
        }

        public boolean hasRate() {
            return ratePerSec > 0.0 && !Double.isNaN(ratePerSec);
        }
    }

    private final Path file;
    private final Map<String, ServerProfile> learned = new LinkedHashMap<>();
    private final Map<String, ServerProfile> overrides = new LinkedHashMap<>();

    public ServerChatStore(Path file) {
        this.file = file;
        load();
    }

    public static String normalizeServer(String server) {
        if (server == null || server.isBlank()) {
            return "local";
        }
        String s = server.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(":25565")) {
            s = s.substring(0, s.length() - ":25565".length());
        }
        return s.replaceAll("[^a-z0-9.:_-]", "_");
    }

    /**
     * Parses a semicolon-separated override map string, e.g.:
     * {@code mc.example.com=/tell {to} {msg}|200|0.4;play.other.net=/w {to} {msg}}
     */
    public synchronized void loadOverridesSpec(String spec) {
        overrides.clear();
        if (spec == null || spec.isBlank()) {
            return;
        }
        for (String entry : spec.split(";")) {
            String trimmed = entry.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq >= trimmed.length() - 1) {
                continue;
            }
            String server = normalizeServer(trimmed.substring(0, eq));
            String rhs = trimmed.substring(eq + 1).trim();
            String[] parts = rhs.split("\\|", -1);
            String tpl = parts.length > 0 ? parts[0].trim() : "";
            int maxChars = parts.length > 1 ? parseInt(parts[1].trim(), -1) : -1;
            double rate = parts.length > 2 ? parseDouble(parts[2].trim(), -1.0) : -1.0;
            overrides.put(server, new ServerProfile(tpl, maxChars, rate, 0L));
        }
    }

    public synchronized void putOverride(String server, ServerProfile profile) {
        String key = normalizeServer(server);
        if (profile == null) {
            overrides.remove(key);
        } else {
            overrides.put(key, profile);
        }
    }

    public synchronized ServerProfile overrideFor(String server) {
        return overrides.get(normalizeServer(server));
    }

    public synchronized ServerProfile learnedFor(String server) {
        return learned.getOrDefault(normalizeServer(server), ServerProfile.EMPTY);
    }

    /**
     * Effective profile for {@code server}: manual override fields always win over learned fields.
     */
    public synchronized ServerProfile effectiveFor(String server) {
        String key = normalizeServer(server);
        ServerProfile ov = overrides.get(key);
        ServerProfile lr = learned.getOrDefault(key, ServerProfile.EMPTY);
        if (ov == null) {
            return lr;
        }
        String tpl = ov.hasTemplate() ? ov.whisperTemplate() : lr.whisperTemplate();
        int maxChars = ov.hasMaxLineChars() ? ov.maxLineChars() : lr.maxLineChars();
        double rate = ov.hasRate() ? ov.ratePerSec() : lr.ratePerSec();
        return new ServerProfile(tpl, maxChars, rate, lr.updatedAtMs());
    }

    public synchronized void putLearned(String server, ServerProfile profile) {
        if (profile == null) {
            return;
        }
        learned.put(normalizeServer(server), profile);
        saveQuietly();
    }

    public synchronized Map<String, ServerProfile> allLearned() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(learned));
    }

    public synchronized void load() {
        learned.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException ignored) {
            return;
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                keys.put(name.substring(0, dot), "");
            }
        }
        for (String server : keys.keySet()) {
            String tpl = p.getProperty(server + ".template", "").trim();
            int maxChars = parseInt(p.getProperty(server + ".maxChars", "-1"), -1);
            double rate = parseDouble(p.getProperty(server + ".rate", "-1"), -1.0);
            long updated = parseLong(p.getProperty(server + ".updatedAtMs", "0"), 0L);
            learned.put(server, new ServerProfile(tpl, maxChars, rate, updated));
        }
    }

    public synchronized void save() throws IOException {
        if (file == null) {
            return;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties p = new Properties();
        for (Map.Entry<String, ServerProfile> e : learned.entrySet()) {
            String k = e.getKey();
            ServerProfile v = e.getValue();
            if (v.hasTemplate()) {
                p.setProperty(k + ".template", v.whisperTemplate());
            }
            if (v.hasMaxLineChars()) {
                p.setProperty(k + ".maxChars", Integer.toString(v.maxLineChars()));
            }
            if (v.hasRate()) {
                p.setProperty(k + ".rate", String.format(Locale.ROOT, "%.4f", v.ratePerSec()));
            }
            p.setProperty(k + ".updatedAtMs", Long.toString(v.updatedAtMs()));
        }
        Path tmp = Files.createTempFile(parent == null ? Path.of(".") : parent, ".swarm-srv-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "Ostinato per-server learned swarm chat profiles");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException ignored) {
            // Non-fatal: in-memory learned profile still applies for this session.
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
