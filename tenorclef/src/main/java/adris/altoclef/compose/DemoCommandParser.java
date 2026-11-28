package adris.altoclef.compose;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java command parser for {@code @teach} / {@code @demo} with quoted-token support so
 * multi-word station names like {@code "serving barrel"} parse cleanly.
 * <p>
 * Supported forms:
 * <ul>
 *     <li>{@code @teach start [<stationHint1> <stationHint2> ...]} (aliases: {@code record}, {@code watch})</li>
 *     <li>{@code @teach stop}</li>
 *     <li>{@code @teach cancel}</li>
 *     <li>{@code @teach summary} (aliases: {@code show}, {@code status})</li>
 *     <li>{@code @teach edit <step#> <station|item|count|action> <value>}</li>
 *     <li>{@code @teach edit <step#> <withdraw|deposit> <count> <item> <station>}</li>
 *     <li>{@code @teach confirm <step#> [<withdraw|deposit> <count> <item> <station>]}</li>
 *     <li>{@code @teach remove <step#>}</li>
 *     <li>{@code @teach save <routineName>}</li>
 *     <li>{@code @teach load <routineName>}</li>
 *     <li>{@code @teach list}</li>
 *     <li>{@code @teach bind <stationName> [<x> <y> <z>]}</li>
 *     <li>{@code @teach bindings}</li>
 *     <li>{@code @teach run <routineName>} (alias: {@code replay})</li>
 * </ul>
 */
public final class DemoCommandParser {

    public enum Action {
        START,
        STOP,
        CANCEL,
        SUMMARY,
        EDIT_FIELD,
        EDIT_FULL,
        CONFIRM,
        REMOVE,
        SAVE,
        LOAD,
        LIST,
        BIND,
        BINDINGS,
        RUN
    }

    public record ParsedCommand(
            Action action,
            String name,
            List<String> hints,
            Integer stepIndex,
            String field,
            String value,
            DemoStep.Action stepAction,
            Integer count,
            String itemId,
            String stationName,
            Integer x,
            Integer y,
            Integer z) {

        public boolean hasCoords() {
            return x != null && y != null && z != null;
        }

        public boolean hasFullStepSpec() {
            return stepAction != null && count != null && itemId != null && stationName != null;
        }
    }

    private DemoCommandParser() {
    }

    public static ParsedCommand parse(String rawLine) {
        return parseTokens(tokenize(rawLine));
    }

    /** Splits a command argument line into tokens, respecting double or single quotes. */
    public static List<String> tokenize(String rawLine) {
        List<String> tokens = new ArrayList<>();
        if (rawLine == null || rawLine.isBlank()) {
            return tokens;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Unclosed quote in command arguments");
        }
        if (!cur.isEmpty()) {
            tokens.add(cur.toString());
        }
        return tokens;
    }

    public static ParsedCommand parseTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return simple(Action.SUMMARY, null);
        }
        String cmd = tokens.get(0).toLowerCase(Locale.ROOT);
        return switch (cmd) {
            case "start", "record", "watch" -> {
                List<String> hints = tokens.size() > 1 ? new ArrayList<>(tokens.subList(1, tokens.size())) : List.of();
                yield new ParsedCommand(Action.START, null, hints, null, null, null, null, null, null, null, null, null, null);
            }
            case "stop" -> simple(Action.STOP, null);
            case "cancel" -> simple(Action.CANCEL, null);
            case "summary", "show", "status", "info" -> simple(Action.SUMMARY, null);
            case "list" -> simple(Action.LIST, null);
            case "bindings", "stations" -> simple(Action.BINDINGS, null);
            case "save" -> {
                if (tokens.size() != 2) {
                    throw new IllegalArgumentException("Usage: @teach save <routineName>");
                }
                yield simple(Action.SAVE, tokens.get(1));
            }
            case "load" -> {
                if (tokens.size() != 2) {
                    throw new IllegalArgumentException("Usage: @teach load <routineName>");
                }
                yield simple(Action.LOAD, tokens.get(1));
            }
            case "run", "replay", "play" -> {
                if (tokens.size() != 2) {
                    throw new IllegalArgumentException("Usage: @teach run <routineName>");
                }
                yield simple(Action.RUN, tokens.get(1));
            }
            case "remove", "delete", "drop" -> {
                if (tokens.size() != 2) {
                    throw new IllegalArgumentException("Usage: @teach remove <step#>");
                }
                int idx = parsePositiveInt(tokens.get(1), "step#");
                yield new ParsedCommand(Action.REMOVE, null, List.of(), idx, null, null, null, null, null, null, null, null, null);
            }
            case "bind" -> parseBind(tokens);
            case "edit" -> parseEdit(tokens);
            case "confirm", "resolve" -> parseConfirm(tokens);
            default -> throw new IllegalArgumentException(
                    "Unknown subcommand '" + tokens.get(0)
                            + "'. Use: start|stop|summary|edit|confirm|remove|save|load|list|bind|bindings|run|cancel");
        };
    }

    private static ParsedCommand parseBind(List<String> tokens) {
        if (tokens.size() < 2) {
            throw new IllegalArgumentException("Usage: @teach bind <stationName> [<x> <y> <z>]");
        }
        // Check if last 3 tokens are integer coordinates (supports unquoted multi-word station names too!)
        if (tokens.size() >= 5 && isInt(tokens.get(tokens.size() - 3))
                && isInt(tokens.get(tokens.size() - 2))
                && isInt(tokens.get(tokens.size() - 1))) {
            String station = joinRange(tokens, 1, tokens.size() - 3);
            DemoStep.normalizeStationKey(station);
            int x = Integer.parseInt(tokens.get(tokens.size() - 3));
            int y = Integer.parseInt(tokens.get(tokens.size() - 2));
            int z = Integer.parseInt(tokens.get(tokens.size() - 1));
            return new ParsedCommand(Action.BIND, null, List.of(), null, null, null, null, null, null, station, x, y, z);
        }
        String station = joinRange(tokens, 1, tokens.size());
        DemoStep.normalizeStationKey(station);
        return new ParsedCommand(Action.BIND, null, List.of(), null, null, null, null, null, null, station, null, null, null);
    }

    private static ParsedCommand parseEdit(List<String> tokens) {
        if (tokens.size() < 4) {
            throw new IllegalArgumentException(
                    "Usage: @teach edit <step#> <station|item|count|action> <value> OR @teach edit <step#> <withdraw|deposit> <count> <item> <station>");
        }
        int idx = parsePositiveInt(tokens.get(1), "step#");
        String third = tokens.get(2).toLowerCase(Locale.ROOT);
        if (third.equals("station") || third.equals("role") || third.equals("item")
                || third.equals("count") || third.equals("qty") || third.equals("action")) {
            String field = third.equals("role") ? "station" : (third.equals("qty") ? "count" : third);
            String value = joinRange(tokens, 3, tokens.size());
            return new ParsedCommand(Action.EDIT_FIELD, null, List.of(), idx, field, value, null, null, null, null, null, null, null);
        }
        if (third.equals("withdraw") || third.equals("deposit")) {
            if (tokens.size() < 6) {
                throw new IllegalArgumentException("Usage: @teach edit <step#> <withdraw|deposit> <count> <item> <station>");
            }
            DemoStep.Action act = parseStepAction(third);
            int qty = parsePositiveInt(tokens.get(3), "count");
            String item = DemoStep.normalizeItemId(tokens.get(4));
            if (item.isEmpty()) {
                throw new IllegalArgumentException("Invalid item id: '" + tokens.get(4) + "'");
            }
            String station = joinRange(tokens, 5, tokens.size());
            DemoStep.normalizeStationKey(station);
            return new ParsedCommand(Action.EDIT_FULL, null, List.of(), idx, null, null, act, qty, item, station, null, null, null);
        }
        throw new IllegalArgumentException("Unknown edit field/action '" + tokens.get(2)
                + "' (expected station, item, count, action, withdraw, or deposit)");
    }

    private static ParsedCommand parseConfirm(List<String> tokens) {
        if (tokens.size() < 2) {
            throw new IllegalArgumentException(
                    "Usage: @teach confirm <step#> [<withdraw|deposit> <count> <item> <station>]");
        }
        int idx = parsePositiveInt(tokens.get(1), "step#");
        if (tokens.size() == 2) {
            return new ParsedCommand(Action.CONFIRM, null, List.of(), idx, null, null, null, null, null, null, null, null, null);
        }
        if (tokens.size() < 6) {
            throw new IllegalArgumentException(
                    "Usage: @teach confirm <step#> [<withdraw|deposit> <count> <item> <station>]");
        }
        DemoStep.Action act = parseStepAction(tokens.get(2));
        int qty = parsePositiveInt(tokens.get(3), "count");
        String item = DemoStep.normalizeItemId(tokens.get(4));
        if (item.isEmpty()) {
            throw new IllegalArgumentException("Invalid item id: '" + tokens.get(4) + "'");
        }
        String station = joinRange(tokens, 5, tokens.size());
        DemoStep.normalizeStationKey(station);
        return new ParsedCommand(Action.CONFIRM, null, List.of(), idx, null, null, act, qty, item, station, null, null, null);
    }

    public static DemoStep.Action parseStepAction(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Expected withdraw or deposit");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "withdraw", "take", "pickup", "get" -> DemoStep.Action.WITHDRAW;
            case "deposit", "put", "store", "place" -> DemoStep.Action.DEPOSIT;
            default -> throw new IllegalArgumentException("Expected 'withdraw' or 'deposit', got '" + raw + "'");
        };
    }

    private static ParsedCommand simple(Action action, String name) {
        return new ParsedCommand(action, name, List.of(), null, null, null, null, null, null, null, null, null, null);
    }

    private static String joinRange(List<String> tokens, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append(' ');
            sb.append(tokens.get(i));
        }
        return sb.toString().trim();
    }

    private static int parsePositiveInt(String raw, String field) {
        try {
            int v = Integer.parseInt(raw.trim());
            if (v <= 0) {
                throw new IllegalArgumentException(field + " must be > 0, got " + v);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected integer for " + field + ", got '" + raw + "'");
        }
    }

    private static boolean isInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            Integer.parseInt(raw.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
