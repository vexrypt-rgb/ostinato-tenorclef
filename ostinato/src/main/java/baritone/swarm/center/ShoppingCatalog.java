package baritone.swarm.center;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java catalog of every Minecraft item (loaded from the bundled {@code items-26.3.txt} resource
 * generated from the Minecraft 26.3 client jar). Contains no Minecraft class references so both the
 * standalone Swing Command Center and the in-game GUI can share the exact same catalog and categories.
 */
public final class ShoppingCatalog {

    public enum Category {
        ALL("All"),
        BLOCKS("Blocks"),
        TOOLS("Tools"),
        COMBAT("Combat"),
        FOOD("Food"),
        REDSTONE("Redstone"),
        BREWING("Brewing"),
        MATERIALS("Materials"),
        DECOR("Decor"),
        MISC("Misc");

        public final String label;

        Category(String label) {
            this.label = label;
        }

        public static Category parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return MISC;
            }
            try {
                return Category.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return MISC;
            }
        }
    }

    public record Entry(String id, String name, Category category) {
        public String searchKey() {
            return id + " " + name.toLowerCase(Locale.ROOT);
        }
    }

    private static final String RESOURCE_PATH = "/baritone/swarm/center/items-26.3.txt";
    private static volatile List<Entry> cached;
    private static volatile Map<String, Entry> cachedById;

    private ShoppingCatalog() {}

    /** All bundled Minecraft 26.3 items in deterministic alphabetical order. */
    public static List<Entry> all() {
        ensureLoaded();
        return cached;
    }

    /** Lookup an item entry by registry path (with or without {@code minecraft:} prefix). */
    public static Entry byId(String rawId) {
        ensureLoaded();
        String norm = normalizeId(rawId);
        return norm.isEmpty() ? null : cachedById.get(norm);
    }

    /** Human-friendly display name for {@code rawId}, falling back to Title Case if custom/modded. */
    public static String displayName(String rawId) {
        Entry e = byId(rawId);
        if (e != null) {
            return e.name();
        }
        String norm = normalizeId(rawId);
        if (norm.isEmpty()) {
            return "";
        }
        String[] parts = norm.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    /** Filters the full bundled catalog by multi-word query and category. */
    public static List<Entry> filter(String query, Category category) {
        return filter(all(), category, query);
    }

    /** Filters {@code source} by category and multi-word case-insensitive query across display name and ID. */
    public static List<Entry> filter(List<Entry> source, Category category, String query) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Category cat = category == null ? Category.ALL : category;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] terms = q.isEmpty() ? new String[0] : q.split("\\s+");

        List<Entry> out = new ArrayList<>();
        for (Entry e : source) {
            if (cat != Category.ALL && e.category() != cat) {
                continue;
            }
            if (terms.length > 0) {
                String hay = e.searchKey();
                boolean ok = true;
                for (String term : terms) {
                    if (!hay.contains(term)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
            }
            out.add(e);
        }
        return out;
    }

    public static String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return s;
    }

    /** Classifies any item ID (used for live/modded items not present in the bundled list). */
    public static Category classifyId(String rawId) {
        Entry existing = byId(rawId);
        if (existing != null) {
            return existing.category();
        }
        String s = normalizeId(rawId);
        if (s.contains("_sword") || s.contains("_helmet") || s.contains("_chestplate")
                || s.contains("_leggings") || s.contains("_boots") || s.contains("shield") || s.contains("arrow")) {
            return Category.COMBAT;
        }
        if (s.contains("_pickaxe") || s.contains("_axe") || s.contains("_shovel")
                || s.contains("_hoe") || s.contains("bucket") || s.contains("shears")) {
            return Category.TOOLS;
        }
        if (s.contains("potion") || s.contains("brewing")) {
            return Category.BREWING;
        }
        if (s.contains("redstone") || s.contains("piston") || s.contains("rail") || s.contains("button")) {
            return Category.REDSTONE;
        }
        if (s.contains("ingot") || s.contains("nugget") || s.contains("gem") || s.contains("dye")) {
            return Category.MATERIALS;
        }
        if (s.contains("spawn_egg")) {
            return Category.MISC;
        }
        return Category.BLOCKS;
    }

    private static void ensureLoaded() {
        if (cached != null) {
            return;
        }
        synchronized (ShoppingCatalog.class) {
            if (cached != null) {
                return;
            }
            List<Entry> list = new ArrayList<>(1700);
            Map<String, Entry> map = new LinkedHashMap<>(2048);
            try (InputStream in = ShoppingCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
                if (in != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            String trimmed = line.trim();
                            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                                continue;
                            }
                            String[] parts = trimmed.split("\\|", 3);
                            if (parts.length < 2) {
                                continue;
                            }
                            String id = normalizeId(parts[0]);
                            if (id.isEmpty() || "air".equals(id)) {
                                continue;
                            }
                            String name = parts[1].trim();
                            Category cat = parts.length >= 3 ? Category.parse(parts[2]) : Category.MISC;
                            Entry entry = new Entry(id, name.isEmpty() ? displayName(id) : name, cat);
                            list.add(entry);
                            map.put(id, entry);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            cached = Collections.unmodifiableList(list);
            cachedById = Collections.unmodifiableMap(map);
        }
    }
}
