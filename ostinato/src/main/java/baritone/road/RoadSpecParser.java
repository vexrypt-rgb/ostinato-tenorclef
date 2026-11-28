/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.road;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure parser for {@code #road plan|build <from> <to> [options]} arguments.
 * Resolves {@code x,y,z}, {@code here}, or named waypoints via a caller-supplied resolver so unit tests stay free of MC classes.
 */
public final class RoadSpecParser {

    @FunctionalInterface
    public interface EndpointResolver {
        /** Returns {@code {x, y, z}} feet coordinates for a label (such as "here" or a waypoint name), or null if unknown. */
        int[] resolve(String token);
    }

    @FunctionalInterface
    public interface SelectionResolver {
        /** Returns current selection boxes as protected zones (empty if none). */
        List<ProtectedZone> resolveSelection();
    }

    private RoadSpecParser() {}

    /**
     * Parses tokens such as:
     * <pre>
     * hall village protect=12,5:22,18 budget=48 width=2 block=cobblestone margin=24
     * 0,64,0 50,64,0 protect=20,63,-5:30,70,5 budget=48
     * </pre>
     */
    public static RoadSpec parse(
            List<String> tokens,
            EndpointResolver endpoints,
            SelectionResolver selections) {
        if (tokens == null || tokens.size() < 2) {
            throw new IllegalArgumentException(
                    "Usage: <from> <to> [protect=x1,z1:x2,z2 | protect=x1,y1,z1:x2,y2,z2 | protect=name:r | protect=sel] "
                            + "[budget=48] [width=2] [headroom=2] [margin=24] [block=cobblestone]");
        }

        List<String> positional = new ArrayList<>();
        List<ProtectedZone> protectedZones = new ArrayList<>();
        int width = RoadSpec.DEFAULT_WIDTH;
        int headroom = RoadSpec.DEFAULT_HEADROOM;
        int budget = RoadSpec.DEFAULT_MAX_PLACED_BLOCKS;
        int margin = RoadSpec.DEFAULT_CORRIDOR_MARGIN;
        String blockId = RoadSpec.DEFAULT_DECK_BLOCK_ID;

        for (String raw : tokens) {
            if (raw == null || raw.isBlank()) continue;
            String tok = raw.trim();
            String low = tok.toLowerCase(Locale.ROOT);
            if (low.startsWith("protect=")) {
                parseProtected(tok.substring("protect=".length()), endpoints, selections, protectedZones);
            } else if (low.startsWith("budget=") || low.startsWith("maxblocks=")) {
                budget = parseIntFlag(tok);
            } else if (low.startsWith("width=")) {
                width = parseIntFlag(tok);
            } else if (low.startsWith("headroom=")) {
                headroom = parseIntFlag(tok);
            } else if (low.startsWith("margin=")) {
                margin = parseIntFlag(tok);
            } else if (low.startsWith("block=") || low.startsWith("material=")) {
                int eq = tok.indexOf('=');
                blockId = tok.substring(eq + 1).trim();
            } else {
                positional.add(tok);
            }
        }

        if (positional.size() != 2) {
            throw new IllegalArgumentException(
                    "Expected exactly 2 endpoints (<from> <to>), got " + positional.size() + ": " + positional);
        }

        String fromTok = positional.get(0);
        String toTok = positional.get(1);
        int[] start = resolveEndpoint(fromTok, endpoints);
        int[] end = resolveEndpoint(toTok, endpoints);

        return new RoadSpec(
                fromTok, start[0], start[1], start[2],
                toTok, end[0], end[1], end[2],
                width, headroom, budget, margin, blockId, protectedZones);
    }

    public static List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.trim().split("\\s+");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static int[] resolveEndpoint(String token, EndpointResolver resolver) {
        int[] coords = tryParseCoords3(token);
        if (coords != null) {
            return coords;
        }
        if (resolver != null) {
            int[] resolved = resolver.resolve(token);
            if (resolved != null && resolved.length == 3) {
                return resolved;
            }
        }
        throw new IllegalArgumentException("Unknown endpoint '" + token + "' (use x,y,z, 'here', or a saved waypoint name)");
    }

    private static void parseProtected(
            String raw,
            EndpointResolver endpoints,
            SelectionResolver selections,
            List<ProtectedZone> out) {
        if (raw.isBlank()) {
            throw new IllegalArgumentException("protect= requires coordinates, 'sel', or waypoint:radius");
        }
        // Allow multiple semicolon-separated zones in one protect= flag.
        for (String entry : raw.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String low = trimmed.toLowerCase(Locale.ROOT);
            if ("sel".equals(low) || "selection".equals(low)) {
                if (selections == null) {
                    throw new IllegalArgumentException("Selection resolver unavailable for protect=sel");
                }
                List<ProtectedZone> selZones = selections.resolveSelection();
                if (selZones.isEmpty()) {
                    throw new IllegalArgumentException("protect=sel requested, but no Baritone selection is set");
                }
                out.addAll(selZones);
                continue;
            }
            String zoneName = "field";
            String body = trimmed;
            int at = trimmed.indexOf('@');
            if (at > 0) {
                zoneName = trimmed.substring(0, at).trim();
                body = trimmed.substring(at + 1).trim();
            }
            int colon = body.indexOf(':');
            if (colon <= 0 || colon == body.length() - 1) {
                throw new IllegalArgumentException("Invalid protect= spec '" + trimmed
                        + "' (use x1,z1:x2,z2, x1,y1,z1:x2,y2,z2, name:radius, or sel)");
            }
            String left = body.substring(0, colon).trim();
            String right = body.substring(colon + 1).trim();
            int[] l3 = tryParseCoords3(left);
            int[] r3 = tryParseCoords3(right);
            if (l3 != null && r3 != null) {
                out.add(ProtectedZone.box(zoneName, l3[0], l3[1], l3[2], r3[0], r3[1], r3[2]));
                continue;
            }
            int[] l2 = tryParseCoords2(left);
            int[] r2 = tryParseCoords2(right);
            if (l2 != null && r2 != null) {
                out.add(ProtectedZone.columns(zoneName, l2[0], l2[1], r2[0], r2[1]));
                continue;
            }
            // Fallback: waypointName:radius
            int radius;
            try {
                radius = Integer.parseInt(right);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid protect= spec '" + trimmed + "'");
            }
            if (radius < 0) {
                throw new IllegalArgumentException("Protected zone radius must be >= 0: " + trimmed);
            }
            int[] center = resolveEndpoint(left, endpoints);
            out.add(ProtectedZone.columns(left, center[0] - radius, center[2] - radius,
                    center[0] + radius, center[2] + radius));
        }
    }

    private static int parseIntFlag(String tok) {
        int eq = tok.indexOf('=');
        String val = tok.substring(eq + 1).trim();
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer flag: " + tok);
        }
    }

    private static int[] tryParseCoords3(String token) {
        String[] parts = token.split(",");
        if (parts.length != 3) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int[] tryParseCoords2(String token) {
        String[] parts = token.split(",");
        if (parts.length != 2) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim())
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
