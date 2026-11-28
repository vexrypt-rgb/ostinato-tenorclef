package baritone.swarm.center;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java mutable shopping list model shared between the in-game Shopping List desk and the
 * standalone Command Center window.
 */
public final class ShoppingListModel {

    public static final int MAX_COUNT = 999_999;

    public record LineItem(String id, int count) {}

    private final LinkedHashMap<String, Integer> items = new LinkedHashMap<>();

    public synchronized int countOf(String rawId) {
        String id = normalize(rawId);
        return id.isEmpty() ? 0 : items.getOrDefault(id, 0);
    }

    /** Adds {@code delta} (may be negative) to {@code rawId}; removes the entry when the total drops to {@code <= 0}. */
    public synchronized int add(String rawId, int delta) {
        String id = normalize(rawId);
        if (id.isEmpty() || delta == 0) {
            return countOf(id);
        }
        long next = (long) items.getOrDefault(id, 0) + delta;
        if (next <= 0L) {
            items.remove(id);
            return 0;
        }
        int clamped = (int) Math.min(MAX_COUNT, next);
        items.put(id, clamped);
        return clamped;
    }

    /** Sets exact quantity for {@code rawId}; removes when {@code <= 0}. */
    public synchronized int set(String rawId, int count) {
        String id = normalize(rawId);
        if (id.isEmpty()) {
            return 0;
        }
        if (count <= 0) {
            items.remove(id);
            return 0;
        }
        int clamped = Math.min(MAX_COUNT, count);
        items.put(id, clamped);
        return clamped;
    }

    public synchronized void remove(String rawId) {
        String id = normalize(rawId);
        if (!id.isEmpty()) {
            items.remove(id);
        }
    }

    public synchronized void clear() {
        items.clear();
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    public synchronized int distinctCount() {
        return items.size();
    }

    public synchronized int totalCount() {
        int sum = 0;
        for (int v : items.values()) {
            sum += v;
        }
        return sum;
    }

    public synchronized List<LineItem> entries() {
        List<LineItem> out = new ArrayList<>(items.size());
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            out.add(new LineItem(e.getKey(), e.getValue()));
        }
        return Collections.unmodifiableList(out);
    }

    /** Formats {@code item:count} pairs separated by spaces (without command head). */
    public synchronized String formatSpec() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** Formats the command line (without {@code #} prefix), e.g. {@code shop gather oak_log:32 cobblestone:64}. */
    public synchronized String buildShopCommand() {
        if (items.isEmpty()) {
            return "";
        }
        return "shop gather " + formatSpec();
    }

    /**
     * Parses a shopping list gather specification into ordered {@code itemId -> count} pairs.
     * Accepts {@code oak_log:32 cobblestone:64}, {@code oak_log 32 cobblestone 64}, or comma-separated lists.
     */
    public static Map<String, Integer> parseSpec(String spec) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        String cleaned = spec.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        String[] tokens = cleaned.split("[\\s,;]+");
        int i = 0;
        while (i < tokens.length) {
            String tok = tokens[i].trim();
            if (tok.isEmpty()) {
                i++;
                continue;
            }
            String idPart;
            int count = 1;
            int lastColon = tok.lastIndexOf(':');
            if (lastColon > 0 && lastColon < tok.length() - 1 && tok.substring(lastColon + 1).matches("\\d+")) {
                idPart = tok.substring(0, lastColon);
                count = parsePositiveInt(tok.substring(lastColon + 1));
                i++;
            } else {
                idPart = tok;
                if (i + 1 < tokens.length && tokens[i + 1].trim().matches("\\d+")) {
                    count = parsePositiveInt(tokens[i + 1].trim());
                    i += 2;
                } else {
                    i++;
                }
            }
            String norm = normalize(idPart);
            if (!norm.isEmpty() && count > 0) {
                long merged = (long) out.getOrDefault(norm, 0) + count;
                out.put(norm, (int) Math.min(MAX_COUNT, merged));
            }
        }
        return out;
    }

    private static int parsePositiveInt(String raw) {
        try {
            long v = Long.parseLong(raw);
            return v <= 0 ? 1 : (int) Math.min(MAX_COUNT, v);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return s;
    }
}
