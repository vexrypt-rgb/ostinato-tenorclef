package adris.altoclef.tasks.replenish;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java parser for the {@code @replenish} player command.
 * <p>
 * Supported forms:
 * <ul>
 *     <li>{@code @replenish chest <name> [<x> <y> <z>]} — register a named chest</li>
 *     <li>{@code @replenish chests} — list registered named chests</li>
 *     <li>{@code @replenish status} — show active replenishment rule, phase, and cost metrics</li>
 *     <li>{@code @replenish stop} — stop active replenishment cleanly and release chest leases</li>
 *     <li>{@code @replenish <item> from <source> to <dest> below <threshold> target <target> reserve <reserve> [interval=<sec>]}</li>
 *     <li>{@code @replenish item=<item> from=<source> to=<dest> below=<threshold> target=<target> reserve=<reserve> [interval=<sec>]}</li>
 * </ul>
 */
public final class ReplenishCommandParser {

    public enum Action {
        REGISTER_CHEST,
        LIST_CHESTS,
        STATUS,
        STOP,
        START_RULE
    }

    public record ParsedCommand(
            Action action,
            String chestName,
            Integer x,
            Integer y,
            Integer z,
            ReplenishmentRule rule) {

        public boolean hasCoords() {
            return x != null && y != null && z != null;
        }
    }

    private ReplenishCommandParser() {
    }

    public static ParsedCommand parse(String rawArgs) {
        List<String> tokens = tokenize(rawArgs);
        return parseTokens(tokens);
    }

    public static ParsedCommand parseTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return new ParsedCommand(Action.STATUS, null, null, null, null, null);
        }
        String first = tokens.get(0).toLowerCase(Locale.ROOT);
        switch (first) {
            case "status", "info" -> {
                return new ParsedCommand(Action.STATUS, null, null, null, null, null);
            }
            case "stop", "cancel", "off" -> {
                return new ParsedCommand(Action.STOP, null, null, null, null, null);
            }
            case "chests", "list" -> {
                return new ParsedCommand(Action.LIST_CHESTS, null, null, null, null, null);
            }
            case "chest", "name", "setchest" -> {
                return parseRegisterChest(tokens);
            }
            default -> {
                return parseStartRule(tokens);
            }
        }
    }

    private static ParsedCommand parseRegisterChest(List<String> tokens) {
        if (tokens.size() != 2 && tokens.size() != 5) {
            throw new IllegalArgumentException("Usage: @replenish chest <name> [<x> <y> <z>]");
        }
        String name = ReplenishmentRule.normalizeChestName(tokens.get(1), "chest");
        if (tokens.size() == 2) {
            return new ParsedCommand(Action.REGISTER_CHEST, name, null, null, null, null);
        }
        int x = parseInt(tokens.get(2), "x");
        int y = parseInt(tokens.get(3), "y");
        int z = parseInt(tokens.get(4), "z");
        return new ParsedCommand(Action.REGISTER_CHEST, name, x, y, z, null);
    }

    private static ParsedCommand parseStartRule(List<String> tokens) {
        String item = null;
        String from = null;
        String to = null;
        Integer below = null;
        Integer target = null;
        Integer reserve = 0;
        long intervalMs = ReplenishmentRule.DEFAULT_POLL_INTERVAL_MS;

        int i = 0;
        // Optional leading bare item name: "@replenish torch from home to quarry below 32 target 64 reserve 16"
        if (i < tokens.size() && !tokens.get(i).contains("=") && !isKeyword(tokens.get(i))) {
            item = tokens.get(i++);
        }

        while (i < tokens.size()) {
            String tok = tokens.get(i++);
            String key;
            String val;
            int eq = tok.indexOf('=');
            if (eq > 0 && eq < tok.length() - 1) {
                key = tok.substring(0, eq).toLowerCase(Locale.ROOT);
                val = tok.substring(eq + 1);
            } else {
                key = tok.toLowerCase(Locale.ROOT);
                if (i >= tokens.size()) {
                    throw new IllegalArgumentException("Missing value after '" + tok + "'");
                }
                val = tokens.get(i++);
            }
            switch (key) {
                case "item" -> item = val;
                case "from", "src", "source" -> from = val;
                case "to", "dst", "dest", "destination" -> to = val;
                case "below", "min", "threshold", "under" -> below = parseInt(val, key);
                case "target", "refill", "fill", "max" -> target = parseInt(val, key);
                case "reserve", "keep", "leave" -> reserve = parseInt(val, key);
                case "interval", "poll", "every" -> intervalMs = parseIntervalMs(val);
                default -> throw new IllegalArgumentException("Unknown option '" + key + "'");
            }
        }

        if (item == null || from == null || to == null || below == null || target == null) {
            throw new IllegalArgumentException(
                    "Usage: @replenish <item> from <source> to <dest> below <threshold> target <target> [reserve <reserve>] [interval=<sec>]");
        }
        ReplenishmentRule rule = new ReplenishmentRule(
                item, from, to, below, target, reserve,
                ReplenishmentRule.DEFAULT_MAX_STACK_SIZE, intervalMs);
        return new ParsedCommand(Action.START_RULE, null, null, null, null, rule);
    }

    private static boolean isKeyword(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "item", "from", "src", "source", "to", "dst", "dest", "destination",
                 "below", "min", "threshold", "under", "target", "refill", "fill", "max",
                 "reserve", "keep", "leave", "interval", "poll", "every" -> true;
            default -> false;
        };
    }

    private static int parseInt(String raw, String field) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected integer for " + field + ", got '" + raw + "'");
        }
    }

    private static long parseIntervalMs(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("ms")) {
                return Long.parseLong(s.substring(0, s.length() - 2));
            }
            if (s.endsWith("s")) {
                return Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1000.0);
            }
            return Math.round(Double.parseDouble(s) * 1000.0);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected seconds (or <n>ms) for interval, got '" + raw + "'");
        }
    }

    private static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }
}
