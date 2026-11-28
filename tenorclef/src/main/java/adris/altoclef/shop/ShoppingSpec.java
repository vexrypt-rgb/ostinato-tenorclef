package adris.altoclef.shop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure parser and formatter for shopping-list item specs shared by {@code @shop} and Ostinato's {@code #shop}.
 * Accepts {@code oak_log:32 cobblestone:64}, {@code oak_log 32 cobblestone 64}, {@code minecraft:diamond:5},
 * and comma/semicolon-separated tokens. Duplicate item IDs are merged by summing their requested counts.
 */
public final class ShoppingSpec {

    public static final int MAX_COUNT = 64 * 64 * 16;

    public record Entry(String id, int count) {}

    private ShoppingSpec() {}

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

    public static int clampCount(long count) {
        if (count <= 0) {
            return 0;
        }
        return (int) Math.min(MAX_COUNT, count);
    }

    public static List<Entry> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        String cleaned = spec.replace('[', ' ').replace(']', ' ').replace(',', ' ').replace(';', ' ').trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        String[] tokens = cleaned.split("\\s+");
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i].trim();
            if (tok.isEmpty()) {
                continue;
            }
            String idPart;
            int count = 1;
            int colon = tok.lastIndexOf(':');
            boolean trailingDigits = colon > 0 && colon < tok.length() - 1 && isNonNegativeInt(tok.substring(colon + 1));
            if (trailingDigits) {
                idPart = tok.substring(0, colon);
                count = parseClamped(tok.substring(colon + 1));
            } else {
                idPart = tok;
                if (i + 1 < tokens.length && isNonNegativeInt(tokens[i + 1])) {
                    count = parseClamped(tokens[++i]);
                }
            }
            String id = normalizeId(idPart);
            if (id.isEmpty() || count <= 0 || isNonNegativeInt(id)) {
                continue;
            }
            long total = (long) merged.getOrDefault(id, 0) + count;
            merged.put(id, clampCount(total));
        }
        List<Entry> out = new ArrayList<>(merged.size());
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            out.add(new Entry(e.getKey(), e.getValue()));
        }
        return out;
    }

    public static String format(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (e == null || e.id() == null || e.id().isBlank() || e.count() <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.id()).append(':').append(e.count());
        }
        return sb.toString();
    }

    private static boolean isNonNegativeInt(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        try {
            return Long.parseLong(s) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int parseClamped(String s) {
        try {
            return clampCount(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
