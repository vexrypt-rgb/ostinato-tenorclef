package baritone.gui.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ostinato: P3 in-game Ctrl+K command palette model (design doc: Unified command and menu system).
 * Pure (no Minecraft imports, headless-testable): fuzzy search over registry commands and saved presets,
 * shared session history and favorites, dry-run formatting, and target-bot routing.
 */
public final class InGamePalette {

    public record Entry(String id, String command, String description, String permission, boolean preset) {}

    private static final int MAX_HISTORY = 32;
    private static final Deque<String> SESSION_HISTORY = new ArrayDeque<>();
    private static final Set<String> SESSION_FAVORITES = new LinkedHashSet<>();

    public static final List<Entry> BUILT_IN_PRESETS = List.of(
            new Entry("preset:stop", "stop", "Emergency stop current path/process", "OWNER", true),
            new Entry("preset:home", "home", "Return to saved home waypoint", "OWNER", true),
            new Entry("preset:mine-diamond", "mine diamond_ore deepslate_diamond_ore", "Mine diamond ore veins", "OWNER", true),
            new Entry("preset:swarm-status", "swarm status", "Show swarm roster and link status", "SWARM", true),
            new Entry("preset:pov-show", "pov show", "Present hidden bot window (render-swap)", "OWNER", true),
            new Entry("preset:pov-hide", "pov hide", "Release hidden bot presentation", "OWNER", true),
            new Entry("preset:theme-nether", "theme nether", "Switch command center theme to Nether", "OWNER", true),
            new Entry("preset:center-map", "center layout map", "Switch command center to War Room map", "OWNER", true)
    );

    private final List<Entry> entries;

    public InGamePalette(List<Entry> registryEntries) {
        List<Entry> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (registryEntries != null) {
            for (Entry e : registryEntries) {
                if (e != null && e.command() != null && !e.command().isBlank() && seen.add(e.command().toLowerCase(Locale.ROOT))) {
                    merged.add(e);
                }
            }
        }
        for (Entry p : BUILT_IN_PRESETS) {
            if (seen.add(p.command().toLowerCase(Locale.ROOT))) {
                merged.add(p);
            }
        }
        this.entries = List.copyOf(merged);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Ranks matching commands/presets/history for {@code query}, prioritizing favorites, exact/prefix, and fuzzy matches. */
    public List<Entry> search(String query) {
        String q = clean(query);
        List<Scored> scored = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Include recent history entries as selectable suggestions
        for (String h : SESSION_HISTORY) {
            if (seen.add(h.toLowerCase(Locale.ROOT))) {
                int s = score(q, h, "recent command");
                if (s > 0) {
                    boolean fav = isFavorite(h);
                    scored.add(new Scored(new Entry("hist:" + h, h, "recent command", "HIST", false),
                            s + (fav ? 120 : 15)));
                }
            }
        }

        for (Entry e : entries) {
            if (!seen.add(e.command().toLowerCase(Locale.ROOT))) {
                continue;
            }
            int s = score(q, e.command(), e.description());
            if (s > 0) {
                if (isFavorite(e.command())) s += 120;
                if (e.preset() && q.isEmpty()) s += 10;
                scored.add(new Scored(e, s));
            }
        }

        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(a -> a.entry().command()));
        List<Entry> out = new ArrayList<>(scored.size());
        for (Scored sc : scored) {
            out.add(sc.entry());
        }
        return out;
    }

    /** Resolves final command line (without leading {@code #}) for {@code targetBot} ({@code self}, a bot name, or {@code all}). */
    public static String routeCommand(String rawCommand, String targetBot, List<String> swarmPeers) {
        String cmd = clean(rawCommand);
        if (cmd.isEmpty()) {
            return "";
        }
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (targetBot == null || targetBot.isBlank() || "self".equalsIgnoreCase(targetBot)
                || lower.startsWith("swarm ") || lower.startsWith("theme ") || lower.startsWith("center ")) {
            return cmd;
        }
        if ("all".equalsIgnoreCase(targetBot)) {
            if (swarmPeers == null || swarmPeers.isEmpty()) {
                return cmd;
            }
            return "swarm send all " + cmd;
        }
        return "swarm send " + targetBot.trim() + " " + cmd;
    }

    public static void recordHistory(String command) {
        String c = clean(command);
        if (c.isEmpty()) return;
        SESSION_HISTORY.remove(c);
        SESSION_HISTORY.addFirst(c);
        while (SESSION_HISTORY.size() > MAX_HISTORY) {
            SESSION_HISTORY.removeLast();
        }
    }

    public static List<String> history() {
        return List.copyOf(SESSION_HISTORY);
    }

    public static boolean toggleFavorite(String command) {
        String c = clean(command);
        if (c.isEmpty()) return false;
        if (SESSION_FAVORITES.contains(c)) {
            SESSION_FAVORITES.remove(c);
            return false;
        }
        SESSION_FAVORITES.add(c);
        return true;
    }

    public static boolean isFavorite(String command) {
        return SESSION_FAVORITES.contains(clean(command));
    }

    /** Tests only. */
    public static void resetSessionForTests() {
        SESSION_HISTORY.clear();
        SESSION_FAVORITES.clear();
    }

    private static String clean(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        return t.startsWith("#") ? t.substring(1).trim() : t;
    }

    private static int score(String query, String cmd, String desc) {
        if (query.isEmpty()) {
            return 10;
        }
        String q = query.toLowerCase(Locale.ROOT);
        String c = cmd == null ? "" : cmd.toLowerCase(Locale.ROOT);
        String d = desc == null ? "" : desc.toLowerCase(Locale.ROOT);
        if (c.equals(q)) return 200;
        if (c.startsWith(q)) return 140 - Math.min(40, c.length() - q.length());
        if (c.contains(q)) return 80;
        if (d.contains(q)) return 50;
        if (fuzzySubsequence(q, c)) return 30;
        return 0;
    }

    private static boolean fuzzySubsequence(String needle, String haystack) {
        int ni = 0;
        for (int hi = 0; hi < haystack.length() && ni < needle.length(); hi++) {
            if (haystack.charAt(hi) == needle.charAt(ni)) {
                ni++;
            }
        }
        return ni == needle.length();
    }

    private record Scored(Entry entry, int score) {}
}
