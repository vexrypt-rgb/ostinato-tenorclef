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

package baritone.swarm.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato: the note a crash record keeps, and how it is read back. No Minecraft types: a {@link View} supplies the
 * block and the inventory, so the rules can be tested without a client.
 *
 * <p>A mine note lists the matching blocks that were visible when the command started. Done means none of those
 * positions still is that block. A place note names the cell and the block. A transfer note is an item count and
 * which way it had to move (pickup up, drop down).
 */
public final class EffectNote {

    /** How far from the feet a mine looks for the block it is about to break. The live ore sits next to the bot. */
    public static final int SCAN_RADIUS = 8;

    private EffectNote() {}

    /** What the bot can see right now. A block the client does not hold is null, never air. */
    public interface View {
        String blockAt(int x, int y, int z);

        /** Count of {@code itemId} (empty means every item). Negative when the inventory cannot be read. */
        int itemCount(String itemId);

        /** Feet, or null when there is no player. */
        int[] feet();
    }

    public static String capture(String name, String args, View view) {
        String n = norm(name);
        return switch (n) {
            case "mine" -> captureMine(args, view);
            case "place" -> capturePlace(args);
            case "pickup" -> captureCount(args, view, "gt");
            case "drop", "stash", "deposit", "give" -> captureCount(args, view, "lt");
            case "craft", "use" -> captureCount(args, view, "ne");
            default -> "";
        };
    }

    public static EffectCheck.Verdict evaluate(String name, String args, String note, View view) {
        if (note == null || note.isEmpty() || view == null) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        try {
            if (note.startsWith("m:")) {
                return evalMine(note, view);
            }
            if (note.startsWith("p:")) {
                return evalPlace(note, view);
            }
            if (note.startsWith("c:")) {
                return evalCount(note, view);
            }
        } catch (RuntimeException e) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        return EffectCheck.Verdict.UNKNOWN;
    }

    /** Namespace-insensitive: {@code stone} and {@code minecraft:stone} are the same block. */
    public static boolean sameId(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return tail(a).equals(tail(b));
    }

    private static String captureMine(String args, View view) {
        List<String> blocks = blockTokens(args);
        if (blocks.isEmpty() || view == null) {
            return "";
        }
        int[] feet = view.feet();
        StringBuilder sb = new StringBuilder("m:");
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(blocks.get(i));
        }
        sb.append(':');
        if (feet == null) {
            return sb.toString();
        }
        boolean any = false;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    int x = feet[0] + dx;
                    int y = feet[1] + dy;
                    int z = feet[2] + dz;
                    String found = view.blockAt(x, y, z);
                    if (found == null || !matchesAny(found, blocks)) {
                        continue;
                    }
                    if (any) {
                        sb.append(';');
                    }
                    any = true;
                    sb.append(x).append(',').append(y).append(',').append(z);
                }
            }
        }
        return sb.toString();
    }

    private static String capturePlace(String args) {
        int[] pos = xyz(args);
        String block = firstToken(nonInts(args));
        if (pos == null || block == null) {
            return "";
        }
        return "p:" + pos[0] + "," + pos[1] + "," + pos[2] + "," + block;
    }

    private static String captureCount(String args, View view, String op) {
        if (view == null) {
            return "";
        }
        String item = firstToken(args);
        if (item == null) {
            item = "";
        }
        int n = view.itemCount(item);
        if (n < 0) {
            return "";
        }
        return "c:" + op + ":" + item + ":" + n;
    }

    private static EffectCheck.Verdict evalMine(String note, View view) {
        int colon = note.indexOf(':', 2);
        if (colon < 0) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        List<String> blocks = splitComma(note.substring(2, colon));
        String rest = note.substring(colon + 1);
        if (rest.isEmpty()) {
            // Nothing was visible when it started, so nothing has been broken yet.
            return EffectCheck.Verdict.NOT_DONE;
        }
        boolean left = false;
        boolean unknown = false;
        for (String cell : rest.split(";")) {
            int[] pos = three(cell);
            if (pos == null) {
                unknown = true;
                continue;
            }
            String found = view.blockAt(pos[0], pos[1], pos[2]);
            if (found == null) {
                unknown = true;
            } else if (matchesAny(found, blocks)) {
                left = true;
            }
        }
        if (left) {
            return EffectCheck.Verdict.NOT_DONE;
        }
        if (unknown) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        return EffectCheck.Verdict.DONE;
    }

    private static EffectCheck.Verdict evalPlace(String note, View view) {
        String[] f = note.substring(2).split(",", -1);
        if (f.length != 4) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        Integer x = intOrNull(f[0]);
        Integer y = intOrNull(f[1]);
        Integer z = intOrNull(f[2]);
        if (x == null || y == null || z == null || f[3].isEmpty()) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        String found = view.blockAt(x, y, z);
        if (found == null) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        return sameId(found, f[3]) ? EffectCheck.Verdict.DONE : EffectCheck.Verdict.NOT_DONE;
    }

    private static EffectCheck.Verdict evalCount(String note, View view) {
        String[] f = note.split(":", -1);
        if (f.length != 4) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        Integer snap = intOrNull(f[3]);
        if (snap == null) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        int now = view.itemCount(f[2]);
        if (now < 0) {
            return EffectCheck.Verdict.UNKNOWN;
        }
        return switch (f[1]) {
            case "gt" -> now > snap ? EffectCheck.Verdict.DONE : EffectCheck.Verdict.NOT_DONE;
            case "lt" -> now < snap ? EffectCheck.Verdict.DONE : EffectCheck.Verdict.NOT_DONE;
            case "ne" -> now != snap ? EffectCheck.Verdict.DONE : EffectCheck.Verdict.NOT_DONE;
            default -> EffectCheck.Verdict.UNKNOWN;
        };
    }

    private static boolean matchesAny(String found, List<String> blocks) {
        for (String b : blocks) {
            if (sameId(found, b)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> blockTokens(String args) {
        return nonInts(args);
    }

    private static List<String> nonInts(String args) {
        List<String> out = new ArrayList<>();
        if (args == null) {
            return out;
        }
        for (String t : args.trim().split("\\s+")) {
            if (t.isEmpty() || intOrNull(t) != null) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private static int[] xyz(String args) {
        if (args == null) {
            return null;
        }
        String[] t = args.trim().split("\\s+");
        for (int i = 0; i + 2 < t.length; i++) {
            Integer x = intOrNull(t[i]);
            Integer y = intOrNull(t[i + 1]);
            Integer z = intOrNull(t[i + 2]);
            if (x != null && y != null && z != null) {
                return new int[]{x, y, z};
            }
        }
        return null;
    }

    private static int[] three(String cell) {
        String[] f = cell.split(",", -1);
        if (f.length != 3) {
            return null;
        }
        Integer x = intOrNull(f[0]);
        Integer y = intOrNull(f[1]);
        Integer z = intOrNull(f[2]);
        if (x == null || y == null || z == null) {
            return null;
        }
        return new int[]{x, y, z};
    }

    private static List<String> splitComma(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String p : text.split(",")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static String firstToken(String args) {
        if (args == null || args.isBlank()) {
            return null;
        }
        String t = args.trim().split("\\s+")[0];
        return t.isEmpty() ? null : t;
    }

    private static String firstToken(List<String> tokens) {
        return tokens.isEmpty() ? null : tokens.get(0);
    }

    private static Integer intOrNull(String t) {
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String tail(String s) {
        String n = s.trim().toLowerCase(Locale.ROOT);
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    private static String norm(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
