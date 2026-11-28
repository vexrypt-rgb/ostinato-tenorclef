package baritone.swarm.center.app;

import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.Json;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ostinato: JDK-only custom theme JSON loader/writer for {@code center-app} (no Gson).
 * Uses the exact same JSON schema as mod-side {@code ThemeStore} ({@code "base"} + 12 {@code "#RRGGBB"} tokens),
 * stored under {@code <prefsDir>/themes/<name>.json}.
 */
public final class CustomThemeStore {

    private CustomThemeStore() {}

    public static Path dir(Path prefsDir) {
        Path root = prefsDir == null ? Path.of(System.getProperty("user.home"), ".ostinato") : prefsDir;
        return root.resolve("themes");
    }

    public static String normalizeName(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-').replaceAll("[^a-z0-9.-]", "");
    }

    public static String toJson(String base, CenterTheme theme) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (base != null && !base.isBlank()) {
            m.put("base", base);
        }
        for (String token : CenterTheme.TOKENS) {
            m.put(token, hex(theme.token(token)));
        }
        return Json.write(m);
    }

    public static CenterTheme parse(String json) {
        Map<String, Object> obj = Json.object(json);
        CenterTheme base = CenterTheme.DEFAULT_DARK;
        if (obj.containsKey("base")) {
            String wanted = ThemeChoice.canonical(Json.str(obj, "base", ""));
            if (wanted == null) {
                throw new IllegalArgumentException("unknown base theme");
            }
            base = CenterTheme.builtIn(wanted);
        }
        Map<String, Integer> tokens = new LinkedHashMap<>();
        for (String token : CenterTheme.TOKENS) {
            if (obj.containsKey(token)) {
                tokens.put(token, parseHex(Json.str(obj, token, "")));
            }
        }
        return base.with(tokens);
    }

    public static Map<String, CenterTheme> loadAll(Path prefsDir) {
        Map<String, CenterTheme> out = new TreeMap<>();
        if (prefsDir == null) {
            return out;
        }
        Path folder = dir(prefsDir);
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path file : files) {
                String fn = file.getFileName().toString();
                String name = normalizeName(fn.substring(0, fn.length() - ".json".length()));
                if (name.isEmpty() || ThemeChoice.canonical(name) != null) {
                    continue;
                }
                try {
                    out.put(name, parse(Files.readString(file)));
                } catch (RuntimeException | IOException ignored) {
                    // skip broken theme files cleanly
                }
            }
        } catch (IOException ignored) {
            return out;
        }
        return out;
    }

    /** Resolve built-in OR custom theme by name, returning null when unknown. */
    public static CenterTheme resolve(Path prefsDir, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String canonical = ThemeChoice.canonical(rawName);
        if (canonical != null) {
            return CenterTheme.builtIn(canonical);
        }
        String norm = normalizeName(rawName);
        return loadAll(prefsDir).get(norm);
    }

    public static Path save(Path prefsDir, String rawName, String base, CenterTheme theme) throws IOException {
        String name = normalizeName(rawName);
        if (name.isEmpty() || ThemeChoice.canonical(name) != null) {
            throw new IllegalArgumentException("custom name cannot be empty or overwrite a built-in");
        }
        Path target = dir(prefsDir).resolve(name + ".json");
        CenterFeed.writeAtomic(target, toJson(base, theme));
        return target;
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    public static int parseHex(String text) {
        String s = text == null ? "" : text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (!s.matches("[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("color must be #RRGGBB");
        }
        return Integer.parseInt(s, 16);
    }
}
