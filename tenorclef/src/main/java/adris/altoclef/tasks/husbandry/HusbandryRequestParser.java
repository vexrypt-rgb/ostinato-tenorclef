package adris.altoclef.tasks.husbandry;

import java.util.List;
import java.util.Locale;

/**
 * Pure-Java parser for the {@code @breed} player command.
 * <p>
 * Supported forms:
 * <ul>
 *     <li>{@code @breed cows 6} / {@code @breed cow 6} / {@code @breed cows to 6} / {@code @breed 6}</li>
 *     <li>{@code @breed cows 6 at <x> <y> <z>}</li>
 *     <li>{@code @breed cows 6 box <minX> <minY> <minZ> <maxX> <maxY> <maxZ>}</li>
 *     <li>{@code @breed status}</li>
 *     <li>{@code @breed stop}</li>
 * </ul>
 */
public final class HusbandryRequestParser {

    public enum Action {
        START,
        STATUS,
        STOP
    }

    public record ParsedCommand(Action action, HusbandryRequest request) {
    }

    private HusbandryRequestParser() {
    }

    public static ParsedCommand parseTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException(usage());
        }
        String first = tokens.get(0).trim().toLowerCase(Locale.ROOT);
        if (first.equals("status")) {
            return new ParsedCommand(Action.STATUS, null);
        }
        if (first.equals("stop") || first.equals("cancel")) {
            return new ParsedCommand(Action.STOP, null);
        }

        int idx = 0;
        String species = HusbandryRequest.SPECIES_COW;
        if (!isInteger(first)) {
            species = HusbandryRequest.normalizeSpecies(first);
            if (!HusbandryRequest.SPECIES_COW.equals(species)) {
                throw new IllegalArgumentException(
                        "Unsupported species '" + tokens.get(0) + "' (only cows are supported; " + usage() + ")");
            }
            idx++;
        }

        if (idx < tokens.size() && tokens.get(idx).equalsIgnoreCase("to")) {
            idx++;
        }

        if (idx >= tokens.size()) {
            throw new IllegalArgumentException("Missing target cow count. " + usage());
        }
        int targetCount = parsePositiveInt(tokens.get(idx++), "target count");

        boolean hasSeed = false;
        int seedX = 0, seedY = 0, seedZ = 0;
        boolean hasBox = false;
        int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;

        while (idx < tokens.size()) {
            String tok = tokens.get(idx).trim().toLowerCase(Locale.ROOT);
            if (tok.equals("at")) {
                if (hasBox) {
                    throw new IllegalArgumentException("Cannot specify both 'at' and 'box'");
                }
                if (idx + 3 >= tokens.size()) {
                    throw new IllegalArgumentException("'at' requires 3 integer coordinates: at <x> <y> <z>");
                }
                seedX = parseInt(tokens.get(idx + 1), "seedX");
                seedY = parseInt(tokens.get(idx + 2), "seedY");
                seedZ = parseInt(tokens.get(idx + 3), "seedZ");
                hasSeed = true;
                idx += 4;
            } else if (tok.equals("box")) {
                if (hasSeed) {
                    throw new IllegalArgumentException("Cannot specify both 'at' and 'box'");
                }
                if (idx + 6 >= tokens.size()) {
                    throw new IllegalArgumentException(
                            "'box' requires 6 integer coordinates: box <minX> <minY> <minZ> <maxX> <maxY> <maxZ>");
                }
                minX = parseInt(tokens.get(idx + 1), "minX");
                minY = parseInt(tokens.get(idx + 2), "minY");
                minZ = parseInt(tokens.get(idx + 3), "minZ");
                maxX = parseInt(tokens.get(idx + 4), "maxX");
                maxY = parseInt(tokens.get(idx + 5), "maxY");
                maxZ = parseInt(tokens.get(idx + 6), "maxZ");
                hasBox = true;
                idx += 7;
            } else {
                throw new IllegalArgumentException("Unexpected argument '" + tokens.get(idx) + "'. " + usage());
            }
        }

        HusbandryRequest req;
        if (hasBox) {
            req = HusbandryRequest.growCowsInBox(targetCount, minX, minY, minZ, maxX, maxY, maxZ);
        } else if (hasSeed) {
            req = HusbandryRequest.growCowsAt(targetCount, seedX, seedY, seedZ);
        } else {
            req = HusbandryRequest.growCows(targetCount);
        }
        return new ParsedCommand(Action.START, req);
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int parseInt(String s, String label) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + label + ": '" + s + "'");
        }
    }

    private static int parsePositiveInt(String s, String label) {
        int val = parseInt(s, label);
        if (val < 1) {
            throw new IllegalArgumentException(label + " must be >= 1, got " + val);
        }
        return val;
    }

    public static String usage() {
        return "Usage: @breed [cows] <targetCount> [at <x> <y> <z> | box <minX> <minY> <minZ> <maxX> <maxY> <maxZ>] | @breed status | @breed stop";
    }
}
