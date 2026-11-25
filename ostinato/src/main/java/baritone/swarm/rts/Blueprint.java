package baritone.swarm.rts;

import baritone.swarm.center.app.OstSchematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: RTS mode live build. A world-anchored blueprint being edited: block names at world positions. It saves as
 * the same {@code ost1} text schematic the area bar and the later 3D editor use, with the world origin and a
 * version in {@code #} header lines that {@link OstSchematic#read} skips. Pure, shared by the window and the bot.
 */
public final class Blueprint {

    public record Cell(int x, int y, int z, String block) {}

    public static final int MAX_CELLS = OstSchematic.MAX_CELLS;

    private final String name;
    private final Map<Long, Cell> cells = new LinkedHashMap<>();
    private long version;

    public Blueprint(String name) {
        this.name = safeName(name);
    }

    public String name() {
        return name;
    }

    public long version() {
        return version;
    }

    public int size() {
        return cells.size();
    }

    public List<Cell> cells() {
        return Collections.unmodifiableList(new ArrayList<>(cells.values()));
    }

    /** True when the cell changed. */
    public boolean paint(int x, int y, int z, String block) {
        String id = block(block);
        Cell c = new Cell(x, y, z, id);
        Cell old = cells.get(key(x, y, z));
        if (c.equals(old)) {
            return false;
        }
        if (old == null && cells.size() >= MAX_CELLS) {
            throw new IllegalArgumentException("blueprint has more than " + MAX_CELLS + " blocks");
        }
        cells.put(key(x, y, z), c);
        if (span() > OstSchematic.MAX_EDGE) {
            if (old == null) {
                cells.remove(key(x, y, z));
            } else {
                cells.put(key(x, y, z), old);
            }
            throw new IllegalArgumentException("blueprint edge over " + OstSchematic.MAX_EDGE);
        }
        version++;
        return true;
    }

    public boolean erase(int x, int y, int z) {
        if (cells.remove(key(x, y, z)) == null) {
            return false;
        }
        version++;
        return true;
    }

    public Cell at(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    /** Lowest corner, or null when empty. */
    public int[] origin() {
        if (cells.isEmpty()) {
            return null;
        }
        int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
        for (Cell c : cells.values()) {
            x = Math.min(x, c.x);
            y = Math.min(y, c.y);
            z = Math.min(z, c.z);
        }
        return new int[]{x, y, z};
    }

    private int span() {
        int[] o = origin();
        int mx = 0;
        for (Cell c : cells.values()) {
            mx = Math.max(mx, Math.max(c.x - o[0], Math.max(c.y - o[1], c.z - o[2])) + 1);
        }
        return mx;
    }

    /** {@code ost1} text: header comments with the world origin and version, then local cells. */
    public String write() {
        StringBuilder sb = new StringBuilder("ost1\n");
        int[] o = origin();
        if (o == null) {
            sb.append("1 1 1\n");
            sb.append("# blueprint ").append(name).append(" origin 0 0 0 version ").append(version).append('\n');
            return sb.toString();
        }
        int sx = 1, sy = 1, sz = 1;
        for (Cell c : cells.values()) {
            sx = Math.max(sx, c.x - o[0] + 1);
            sy = Math.max(sy, c.y - o[1] + 1);
            sz = Math.max(sz, c.z - o[2] + 1);
        }
        // the header comment goes after the size line: every ost1 reader skips '#' lines in the body
        sb.append(sx).append(' ').append(sy).append(' ').append(sz).append('\n');
        sb.append("# blueprint ").append(name).append(" origin ").append(o[0]).append(' ').append(o[1]).append(' ')
                .append(o[2]).append(" version ").append(version).append('\n');
        for (Cell c : cells.values()) {
            sb.append(c.x - o[0]).append(' ').append(c.y - o[1]).append(' ').append(c.z - o[2]).append(' ')
                    .append(c.block).append('\n');
        }
        return sb.toString();
    }

    /** Reads {@link #write} output. Throws {@link IllegalArgumentException} on anything else. */
    public static Blueprint read(String fallbackName, String text) {
        OstSchematic s = OstSchematic.read(text);
        String name = fallbackName;
        int[] o = {0, 0, 0};
        long version = 0;
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("# blueprint ")) {
                String[] w = t.substring(2).trim().split("\\s+");
                // blueprint <name> origin x y z version n
                if (w.length >= 6 && w[2].equals("origin")) {
                    name = w[1];
                    o = new int[]{Integer.parseInt(w[3]), Integer.parseInt(w[4]), Integer.parseInt(w[5])};
                }
                if (w.length >= 8 && w[6].equals("version")) {
                    version = Long.parseLong(w[7]);
                }
                break;
            }
        }
        Blueprint b = new Blueprint(name);
        for (OstSchematic.Cell c : s.cells()) {
            b.cells.put(key(o[0] + c.x(), o[1] + c.y(), o[2] + c.z()), new Cell(o[0] + c.x(), o[1] + c.y(), o[2] + c.z(), c.block()));
        }
        b.version = version;
        return b;
    }

    public static String safeName(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.length() > 32) {
            throw new IllegalArgumentException("blueprint name must be 1..32 letters, digits, - or _");
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                throw new IllegalArgumentException("blueprint name must be 1..32 letters, digits, - or _");
            }
        }
        return n;
    }

    static String block(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (id.isEmpty() || id.equals("air")) {
            throw new IllegalArgumentException("pick a block to paint");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                throw new IllegalArgumentException("bad block name " + raw);
            }
        }
        return id;
    }

    static long key(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << 38) | (((long) (y & 0xFFF)) << 26) | (z & 0x3FFFFFF);
    }

    /** File name in {@code <swarmCenterDir>/blueprints/}. */
    public static String fileName(String name) {
        return safeName(name) + ".ost";
    }

    /** Status file the building bot writes next to the blueprint. */
    public static String statusName(String name) {
        return safeName(name) + ".status";
    }

    /** {@code version placed total} then one {@code x y z} line per placed cell. */
    public static String writeStatus(long version, List<int[]> placed, int total, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append(version).append(' ').append(placed.size()).append(' ').append(total).append(' ')
                .append(note == null || note.isBlank() ? "-" : note.replace('\n', ' ')).append('\n');
        for (int[] p : placed) {
            sb.append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append('\n');
        }
        return sb.toString();
    }

    public record Status(long version, int placed, int total, String note, List<int[]> cells) {}

    public static Status readStatus(String text) {
        String[] lines = text.split("\\R");
        String[] h = lines[0].trim().split(" ", 4);
        List<int[]> cells = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] w = lines[i].trim().split(" ");
            if (w.length == 3) {
                cells.add(new int[]{Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2])});
            }
        }
        return new Status(Long.parseLong(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                h.length > 3 ? h[3] : "", cells);
    }
}
