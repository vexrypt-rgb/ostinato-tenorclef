package baritone.swarm.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ostinato: Pure JDK block-state specification parser, validator, and comparator shared by {@code center-app}
 * and the bot. Legacy name-only specs (no {@code [...]}) have empty properties and match by block type only.
 * Supported explicit block-state properties in this slice:
 * <ul>
 *   <li>Logs / pillars: {@code axis} ({@code x}, {@code y}, {@code z})</li>
 *   <li>Slabs: {@code type} ({@code bottom}, {@code top}) and dry {@code waterlogged=false}</li>
 *   <li>Straight stairs: {@code facing} ({@code north}, {@code south}, {@code east}, {@code west}),
 *       {@code half} ({@code bottom}, {@code top}), {@code shape=straight}, and dry {@code waterlogged=false}</li>
 * </ul>
 * Any other explicit property or unsupported value is preserved on import/save/reload and explicitly reported
 * as unsupported so the editor and bot never silently ignore it or mark it complete.
 */
public final class BlockStateSpec {

    public static final Set<String> VALID_AXES = Set.of("x", "y", "z");
    public static final Set<String> VALID_SLAB_HALVES = Set.of("bottom", "top");
    public static final Set<String> VALID_FACINGS = Set.of("north", "south", "east", "west");
    public static final Set<String> VALID_STAIR_HALVES = Set.of("bottom", "top");

    private static final Set<String> EXTRA_PILLAR_BLOCKS = Set.of(
            "bamboo_block", "stripped_bamboo_block",
            "basalt", "polished_basalt",
            "quartz_pillar", "purpur_pillar",
            "bone_block", "hay_block",
            "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
            "deepslate", "muddy_mangrove_roots", "creaking_heart"
    );

    public record Parsed(String block, Map<String, String> properties) {
        public Parsed {
            block = validateBlockId(block);
            properties = normalizeProperties(properties);
        }

        public boolean hasExplicitProperties() {
            return !properties.isEmpty();
        }

        public String spec() {
            return format(block, properties);
        }
    }

    private BlockStateSpec() {}

    /** Parses {@code cobblestone}, {@code minecraft:oak_log[axis=x]}, or {@code oak_stairs[facing=east,half=top]}. */
    public static Parsed parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("unknown block");
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("unknown block");
        }
        int open = trimmed.indexOf('[');
        String head;
        Map<String, String> props;
        if (open >= 0) {
            if (!trimmed.endsWith("]") || trimmed.indexOf('[', open + 1) >= 0) {
                throw new IllegalArgumentException("bad block state " + raw);
            }
            head = trimmed.substring(0, open);
            String body = trimmed.substring(open + 1, trimmed.length() - 1);
            props = parseProperties(body, raw);
        } else {
            if (trimmed.indexOf(']') >= 0) {
                throw new IllegalArgumentException("bad block state " + raw);
            }
            head = trimmed;
            props = Map.of();
        }
        String id = stripNamespace(head);
        return new Parsed(id, props);
    }

    public static Parsed of(String block, Map<String, String> properties) {
        Parsed base = parse(block);
        if (properties == null || properties.isEmpty()) {
            return base;
        }
        Map<String, String> merged = new TreeMap<>(base.properties());
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("null block property on " + block);
            }
            String k = validateToken(e.getKey().trim().toLowerCase(Locale.ROOT), "property key");
            String v = validateToken(e.getValue().trim().toLowerCase(Locale.ROOT), "property value");
            merged.put(k, v);
        }
        return new Parsed(base.block(), merged);
    }

    /** Formats a canonical block spec without spaces: {@code oak_log} or {@code oak_stairs[facing=east,half=top]}. */
    public static String format(String block, Map<String, String> properties) {
        String id = validateBlockId(block);
        if (properties == null || properties.isEmpty()) {
            return id;
        }
        Map<String, String> sorted = properties instanceof TreeMap ? properties : new TreeMap<>(properties);
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    /** Strips {@code minecraft:} (or any namespace) and optional {@code [properties]}. */
    public static String baseBlockId(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        int bracket = id.indexOf('[');
        if (bracket >= 0) {
            id = id.substring(0, bracket);
        }
        return stripNamespace(id);
    }

    public static boolean isPillarBlock(String block) {
        if (block == null) {
            return false;
        }
        return block.endsWith("_log") || block.endsWith("_wood")
                || block.endsWith("_stem") || block.endsWith("_hyphae")
                || EXTRA_PILLAR_BLOCKS.contains(block);
    }

    public static boolean isSlabBlock(String block) {
        return block != null && block.endsWith("_slab");
    }

    public static boolean isStairBlock(String block) {
        return block != null && block.endsWith("_stairs");
    }

    /**
     * Returns empty list when this block + explicit properties are supported in this slice.
     * Legacy name-only specs (empty properties) are always supported.
     */
    public static List<String> unsupportedReasons(String block, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }
        String id = baseBlockId(block);
        List<String> reasons = new ArrayList<>();
        if (isPillarBlock(id)) {
            for (Map.Entry<String, String> e : properties.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                if ("axis".equals(k)) {
                    if (!VALID_AXES.contains(v)) {
                        reasons.add("unsupported axis=" + v + " on " + id);
                    }
                } else {
                    reasons.add("unsupported property " + k + "=" + v + " on " + id);
                }
            }
            return List.copyOf(reasons);
        }
        if (isSlabBlock(id)) {
            for (Map.Entry<String, String> e : properties.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                if ("type".equals(k)) {
                    if (!VALID_SLAB_HALVES.contains(v)) {
                        reasons.add("unsupported slab type=" + v + " on " + id + " (only bottom/top supported)");
                    }
                } else if ("waterlogged".equals(k)) {
                    if (!"false".equals(v)) {
                        reasons.add("unsupported waterlogged=" + v + " on " + id);
                    }
                } else {
                    reasons.add("unsupported property " + k + "=" + v + " on " + id);
                }
            }
            return List.copyOf(reasons);
        }
        if (isStairBlock(id)) {
            for (Map.Entry<String, String> e : properties.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                if ("facing".equals(k)) {
                    if (!VALID_FACINGS.contains(v)) {
                        reasons.add("unsupported stair facing=" + v + " on " + id);
                    }
                } else if ("half".equals(k)) {
                    if (!VALID_STAIR_HALVES.contains(v)) {
                        reasons.add("unsupported stair half=" + v + " on " + id);
                    }
                } else if ("shape".equals(k)) {
                    if (!"straight".equals(v)) {
                        reasons.add("unsupported stair shape=" + v + " on " + id + " (only straight stairs supported)");
                    }
                } else if ("waterlogged".equals(k)) {
                    if (!"false".equals(v)) {
                        reasons.add("unsupported waterlogged=" + v + " on " + id);
                    }
                } else {
                    reasons.add("unsupported property " + k + "=" + v + " on " + id);
                }
            }
            return List.copyOf(reasons);
        }
        // Any other block family with explicit state properties is unsupported in this slice.
        StringBuilder propsText = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!first) {
                propsText.append(',');
            }
            first = false;
            propsText.append(e.getKey()).append('=').append(e.getValue());
        }
        reasons.add("unsupported explicit state on " + id + ": " + propsText);
        return List.copyOf(reasons);
    }

    public static boolean isSupported(String block, Map<String, String> properties) {
        return unsupportedReasons(block, properties).isEmpty();
    }

    /**
     * Pure comparison of an expected cell against an observed block + properties.
     * Unsupported explicit requirements never match. Legacy name-only cells match by block type only.
     */
    public static boolean matches(String expectedBlock, Map<String, String> expectedProps,
                                  String actualBlock, Map<String, String> actualProps) {
        if (!isSupported(expectedBlock, expectedProps)) {
            return false;
        }
        if (actualBlock == null || !baseBlockId(expectedBlock).equals(baseBlockId(actualBlock))) {
            return false;
        }
        if (expectedProps == null || expectedProps.isEmpty()) {
            return true;
        }
        if (actualProps == null) {
            return false;
        }
        for (Map.Entry<String, String> e : expectedProps.entrySet()) {
            String have = actualProps.get(e.getKey());
            if (!e.getValue().equals(have)) {
                return false;
            }
        }
        return true;
    }

    /** Describes mismatched explicit properties between expected and actual (when block types match). */
    public static String mismatchReason(Map<String, String> expectedProps, Map<String, String> actualProps) {
        if (expectedProps == null || expectedProps.isEmpty()) {
            return "";
        }
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, String> e : expectedProps.entrySet()) {
            String have = actualProps == null ? null : actualProps.get(e.getKey());
            if (!e.getValue().equals(have)) {
                diffs.add(e.getKey() + "=" + (have == null ? "?" : have) + " (want " + e.getValue() + ")");
            }
        }
        return String.join(", ", diffs);
    }

    /** Subset of actual properties relevant to expectedProps (for concise status output). */
    public static Map<String, String> relevantActualProperties(Map<String, String> expectedProps,
                                                               Map<String, String> actualProps) {
        if (expectedProps == null || expectedProps.isEmpty() || actualProps == null || actualProps.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new TreeMap<>();
        for (String key : expectedProps.keySet()) {
            String v = actualProps.get(key);
            if (v != null) {
                out.put(key, v);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> parseProperties(String body, String raw) {
        if (body.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new TreeMap<>();
        for (String part : body.split(",", -1)) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("bad block state " + raw);
            }
            int eq = part.indexOf('=');
            if (eq <= 0 || eq >= part.length() - 1 || part.indexOf('=', eq + 1) >= 0) {
                throw new IllegalArgumentException("bad block property in " + raw);
            }
            String k = validateToken(part.substring(0, eq), "property key");
            String v = validateToken(part.substring(eq + 1), "property value");
            out.put(k, v);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> normalizeProperties(Map<String, String> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("null block property");
            }
            String k = validateToken(e.getKey().trim().toLowerCase(Locale.ROOT), "property key");
            String v = validateToken(e.getValue().trim().toLowerCase(Locale.ROOT), "property value");
            out.put(k, v);
        }
        return Collections.unmodifiableMap(out);
    }

    private static String stripNamespace(String id) {
        if (id.startsWith("minecraft:")) {
            return id.substring("minecraft:".length());
        }
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    static String validateBlockId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("unknown block");
        }
        String id = stripNamespace(raw.trim().toLowerCase(Locale.ROOT));
        if (id.isEmpty() || "air".equals(id) || "cave_air".equals(id) || "void_air".equals(id)) {
            throw new IllegalArgumentException("unknown block " + raw);
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!isTokenChar(c)) {
                throw new IllegalArgumentException("unknown block " + raw);
            }
        }
        return id;
    }

    private static String validateToken(String token, String kind) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("empty " + kind);
        }
        for (int i = 0; i < token.length(); i++) {
            if (!isTokenChar(token.charAt(i))) {
                throw new IllegalArgumentException("bad " + kind + " " + token);
            }
        }
        return token;
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }
}
