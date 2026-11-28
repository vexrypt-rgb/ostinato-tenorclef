package adris.altoclef.tasks.brewing;

import java.util.List;
import java.util.Locale;

/**
 * Pure-Java parser for the {@code @brew} command.
 * <p>
 * Supported forms:
 * <ul>
 *     <li>{@code @brew status}</li>
 *     <li>{@code @brew stop}</li>
 *     <li>{@code @brew healing [count] [at <x> <y> <z>] [radius=<r>]}</li>
 *     <li>{@code @brew potion_of_healing 3} / {@code @brew ordinary_healing 3} / {@code @brew potion{healing} 3}</li>
 * </ul>
 * Explicitly rejects unsupported potion forms/variants (splash, lingering, strong/II, other types)
 * rather than silently brewing a different potion.
 */
public final class BrewRequestParser {

    public enum Action {
        START,
        STATUS,
        STOP
    }

    public record ParsedCommand(Action action, BrewRequest request) {
    }

    private BrewRequestParser() {
    }

    public static ParsedCommand parseTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException(usage());
        }
        String first = tokens.get(0).trim().toLowerCase(Locale.ROOT);
        if (first.equals("status")) {
            if (tokens.size() != 1) {
                throw new IllegalArgumentException("Usage: @brew status");
            }
            return new ParsedCommand(Action.STATUS, null);
        }
        if (first.equals("stop") || first.equals("cancel")) {
            if (tokens.size() != 1) {
                throw new IllegalArgumentException("Usage: @brew stop");
            }
            return new ParsedCommand(Action.STOP, null);
        }

        int idx = 0;
        String recipeToken = first;
        if (recipeToken.equals("ordinary") && tokens.size() >= 2) {
            recipeToken = "ordinary_" + tokens.get(1).trim().toLowerCase(Locale.ROOT);
            idx = 2;
        } else {
            idx = 1;
        }
        BrewRecipe recipe = resolveSupportedRecipe(recipeToken);

        int count = BrewRequest.DEFAULT_BATCH_COUNT;
        boolean countSet = false;
        boolean hasPos = false;
        int x = 0, y = 0, z = 0;
        int radius = BrewRequest.DEFAULT_SUPPLY_RADIUS;

        while (idx < tokens.size()) {
            String tok = tokens.get(idx).trim();
            String lower = tok.toLowerCase(Locale.ROOT);
            if (lower.equals("at")) {
                if (idx + 3 >= tokens.size()) {
                    throw new IllegalArgumentException("Expected '<x> <y> <z>' after 'at'");
                }
                x = parseInt(tokens.get(idx + 1), "x");
                y = parseInt(tokens.get(idx + 2), "y");
                z = parseInt(tokens.get(idx + 3), "z");
                hasPos = true;
                idx += 4;
            } else if (lower.startsWith("radius=")) {
                radius = parseInt(tok.substring("radius=".length()), "radius");
                idx++;
            } else if (!countSet && isInteger(tok)) {
                count = parseInt(tok, "count");
                countSet = true;
                idx++;
            } else {
                throw new IllegalArgumentException("Unexpected argument '" + tok + "'. " + usage());
            }
        }

        if (count <= 0) {
            throw new IllegalArgumentException("Potion count must be >= 1, got " + count);
        }
        return new ParsedCommand(Action.START, new BrewRequest(recipe, count, hasPos, x, y, z, radius));
    }

    public static BrewRecipe resolveSupportedRecipe(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Missing potion recipe name. " + usage());
        }
        String norm = rawToken.trim().toLowerCase(Locale.ROOT);
        return switch (norm) {
            case "healing",
                 "minecraft:healing",
                 "ordinary_healing",
                 "potion_of_healing",
                 "ordinary_potion_of_healing",
                 "healing_potion",
                 "instant_health",
                 "potion{healing}",
                 "potion{minecraft:healing}",
                 "minecraft:potion{healing}",
                 "minecraft:potion{minecraft:healing}" -> BrewRecipe.ORDINARY_HEALING;
            default -> {
                if (norm.contains("splash") || norm.contains("lingering")
                        || norm.contains("strong") || norm.contains("ii") || norm.contains("2")) {
                    throw new IllegalArgumentException("Unsupported potion variant '" + rawToken
                            + "': only ordinary Potion of Healing (minecraft:potion[minecraft:healing]) is supported");
                }
                throw new IllegalArgumentException("Unsupported potion recipe '" + rawToken
                        + "': currently supported: healing (ordinary Potion of Healing)");
            }
        };
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) return false;
        int start = (s.charAt(0) == '-' || s.charAt(0) == '+') ? 1 : 0;
        if (start >= s.length()) return false;
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static int parseInt(String raw, String field) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + field + ": '" + raw + "'", e);
        }
    }

    public static String usage() {
        return "Usage: @brew healing [count] [at <x> <y> <z>] [radius=<r>] | @brew status | @brew stop";
    }
}
