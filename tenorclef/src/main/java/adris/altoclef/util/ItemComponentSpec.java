package adris.altoclef.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Exact item component / enchantment requirements for resource acquisition and villager trading.
 * <p>
 * Pure Java (no Minecraft runtime dependency) so parsing, equality, and matching can be
 * exercised directly in unit tests as well as against live {@code ItemStack} data components.
 * <p>
 * Syntax examples:
 * <ul>
 *     <li>{@code mending} — requires exactly {@code minecraft:mending} at any positive level</li>
 *     <li>{@code mending=1} — requires exactly {@code minecraft:mending} at level 1</li>
 *     <li>{@code minecraft:unbreaking=3+mending=1} — requires exact set of both enchantments</li>
 * </ul>
 */
public final class ItemComponentSpec {

    /** Sentinel level meaning "present at any positive level (>= 1)". */
    public static final int ANY_POSITIVE_LEVEL = 0;

    public static final ItemComponentSpec EMPTY = new ItemComponentSpec(Collections.emptyMap());

    private final Map<String, Integer> requiredEnchantments;

    private ItemComponentSpec(Map<String, Integer> requiredEnchantments) {
        if (requiredEnchantments == null || requiredEnchantments.isEmpty()) {
            this.requiredEnchantments = Collections.emptyMap();
        } else {
            Map<String, Integer> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : requiredEnchantments.entrySet()) {
                String id = normalizeEnchantmentId(e.getKey());
                int lvl = e.getValue() == null ? ANY_POSITIVE_LEVEL : e.getValue();
                if (lvl < ANY_POSITIVE_LEVEL) {
                    throw new IllegalArgumentException("Enchantment level must be >= 0 (0 = any positive), got " + lvl + " for " + id);
                }
                normalized.put(id, lvl);
            }
            this.requiredEnchantments = Collections.unmodifiableMap(normalized);
        }
    }

    public static ItemComponentSpec ofEnchantment(String enchantmentId) {
        return ofEnchantment(enchantmentId, ANY_POSITIVE_LEVEL);
    }

    public static ItemComponentSpec ofEnchantment(String enchantmentId, int level) {
        if (level < ANY_POSITIVE_LEVEL) {
            throw new IllegalArgumentException("Enchantment level must be >= 0, got " + level);
        }
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put(normalizeEnchantmentId(enchantmentId), level);
        return new ItemComponentSpec(map);
    }

    public static ItemComponentSpec ofEnchantments(Map<String, Integer> enchantments) {
        if (enchantments == null || enchantments.isEmpty()) {
            return EMPTY;
        }
        return new ItemComponentSpec(enchantments);
    }

    /**
     * Parses an enchantment component spec such as {@code mending}, {@code {mending}},
     * {@code {mending=1}}, or {@code {unbreaking=3+mending=1}}.
     */
    public static ItemComponentSpec parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Component spec cannot be null");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Component spec cannot be empty");
        }

        String[] parts = trimmed.split("[+&]");
        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Empty enchantment entry in spec '" + raw + "'");
            }
            String idPart;
            int level = ANY_POSITIVE_LEVEL;
            int eq = token.indexOf('=');
            if (eq >= 0) {
                idPart = token.substring(0, eq).trim();
                String lvlStr = token.substring(eq + 1).trim();
                if (lvlStr.isEmpty()) {
                    throw new IllegalArgumentException("Missing level after '=' in spec '" + raw + "'");
                }
                try {
                    level = Integer.parseInt(lvlStr);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid enchantment level '" + lvlStr + "' in spec '" + raw + "'", e);
                }
                if (level <= 0) {
                    throw new IllegalArgumentException("Enchantment level must be >= 1, got " + level + " in spec '" + raw + "'");
                }
            } else {
                idPart = token;
            }
            String normalizedId = normalizeEnchantmentId(idPart);
            parsed.put(normalizedId, level);
        }
        return new ItemComponentSpec(parsed);
    }

    /**
     * Normalizes an enchantment identifier to lowercase {@code namespace:path}
     * (defaulting namespace to {@code minecraft}).
     */
    public static String normalizeEnchantmentId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Enchantment id cannot be null");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Enchantment id cannot be empty");
        }
        if (s.indexOf(':') < 0) {
            s = "minecraft:" + s;
        }
        int colon = s.indexOf(':');
        String ns = s.substring(0, colon);
        String path = s.substring(colon + 1);
        if (ns.isEmpty() || path.isEmpty() || !ns.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid enchantment id '" + raw + "'");
        }
        return s;
    }

    public boolean isEmpty() {
        return requiredEnchantments.isEmpty();
    }

    public Map<String, Integer> getRequiredEnchantments() {
        return requiredEnchantments;
    }

    /**
     * Checks exact enchantment match against a normalized map of {@code namespace:path -> level}.
     * <p>
     * Rejects look-alike items that have different enchantments, wrong levels, or extra
     * unrequested enchantments.
     */
    public boolean matchesEnchantments(Map<String, Integer> actualEnchantments) {
        if (requiredEnchantments.isEmpty()) {
            return true;
        }
        if (actualEnchantments == null || actualEnchantments.size() != requiredEnchantments.size()) {
            return false;
        }
        for (Map.Entry<String, Integer> req : requiredEnchantments.entrySet()) {
            Integer actualLevel = actualEnchantments.get(req.getKey());
            if (actualLevel == null || actualLevel <= 0) {
                return false;
            }
            int expectedLevel = req.getValue();
            if (expectedLevel > ANY_POSITIVE_LEVEL && actualLevel != expectedLevel) {
                return false;
            }
        }
        return true;
    }

    /** Canonical brace representation, e.g. {@code {mending}} or {@code {unbreaking=3+mending=1}}. */
    public String formatBraceSpec() {
        if (requiredEnchantments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : requiredEnchantments.entrySet()) {
            if (!first) sb.append('+');
            first = false;
            String id = e.getKey();
            if (id.startsWith("minecraft:")) {
                id = id.substring("minecraft:".length());
            }
            sb.append(id);
            if (e.getValue() > ANY_POSITIVE_LEVEL) {
                sb.append('=').append(e.getValue());
            }
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemComponentSpec that)) return false;
        return Objects.equals(requiredEnchantments, that.requiredEnchantments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requiredEnchantments);
    }

    @Override
    public String toString() {
        return isEmpty() ? "{}" : formatBraceSpec();
    }
}
