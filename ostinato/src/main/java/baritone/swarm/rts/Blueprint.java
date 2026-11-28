package baritone.swarm.rts;

import baritone.swarm.center.app.OstSchematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: RTS mode live build. A world-anchored blueprint being edited: block specs (name + optional explicit
 * block-state properties) at world positions. It saves as the same {@code ost1} text schematic the area bar and
 * the 3D editor use, with the world origin and a version in {@code #} header lines that {@link OstSchematic#read}
 * skips. Pure, shared by the window and the bot.
 */
public final class Blueprint {

    public record Cell(int x, int y, int z, String block, Map<String, String> properties) {
        public Cell {
            BlockStateSpec.Parsed parsed = BlockStateSpec.of(block, properties);
            block = parsed.block();
            properties = parsed.properties();
        }

        public Cell(int x, int y, int z, String block) {
            this(x, y, z, block, Map.of());
        }

        public boolean hasExplicitProperties() {
            return !properties.isEmpty();
        }

        public String spec() {
            return BlockStateSpec.format(block, properties);
        }
    }

    public enum DiscrepancyKind {
        WRONG_STATE,
        WRONG_BLOCK,
        MISSING,
        UNSUPPORTED,
        UNLOADED,
        REPAIR_FAILED
    }

    public record Discrepancy(int x, int y, int z, DiscrepancyKind kind, String expected, String actual, String reason) {
        public Discrepancy {
            if (kind == null) {
                throw new IllegalArgumentException("discrepancy kind is required");
            }
            expected = expected == null ? "" : expected;
            actual = actual == null ? "-" : actual;
            reason = reason == null ? "" : reason;
        }
    }

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

    /** True when the cell changed. Accepts legacy {@code oak_log} or explicit {@code oak_log[axis=x]}. */
    public boolean paint(int x, int y, int z, String block) {
        BlockStateSpec.Parsed parsed = parsePaint(block);
        return paint(x, y, z, parsed.block(), parsed.properties());
    }

    /** True when the cell changed. */
    public boolean paint(int x, int y, int z, String block, Map<String, String> properties) {
        BlockStateSpec.Parsed parsed = parsePaint(block, properties);
        Cell c = new Cell(x, y, z, parsed.block(), parsed.properties());
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
                    .append(c.spec()).append('\n');
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
            b.cells.put(key(o[0] + c.x(), o[1] + c.y(), o[2] + c.z()),
                    new Cell(o[0] + c.x(), o[1] + c.y(), o[2] + c.z(), c.block(), c.properties()));
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
        return parsePaint(raw).block();
    }

    private static BlockStateSpec.Parsed parsePaint(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.equals("air") || id.equals("minecraft:air")) {
            throw new IllegalArgumentException("pick a block to paint");
        }
        try {
            return BlockStateSpec.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad block name " + raw);
        }
    }

    private static BlockStateSpec.Parsed parsePaint(String raw, Map<String, String> properties) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.equals("air") || id.equals("minecraft:air")) {
            throw new IllegalArgumentException("pick a block to paint");
        }
        try {
            return BlockStateSpec.of(raw, properties);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad block name " + raw);
        }
    }

    static long key(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << 38) | (((long) (y & 0xFFF)) << 26) | (z & 0x3FFFFFF);
    }

    /** Public alias of {@link #key} for the command-center editor and the map. */
    public static long cellKey(int x, int y, int z) {
        return key(x, y, z);
    }

    /** File name in {@code <swarmCenterDir>/blueprints/}. */
    public static String fileName(String name) {
        return safeName(name) + ".ost";
    }

    /** Status file the building bot writes next to the blueprint. */
    public static String statusName(String name) {
        return safeName(name) + ".status";
    }

    /** Ostinato: status file of one bot building one part ({@code <name>@<bot>.status}); the map merges them all. */
    public static String partStatusName(String name, String bot) {
        String b = bot == null ? "" : bot.replaceAll("[^A-Za-z0-9_]", "");
        return safeName(name) + "@" + (b.isEmpty() ? "bot" : b) + ".status";
    }

    /** Ostinato: true for {@code <name>.status} and every {@code <name>@<bot>.status}. */
    public static boolean isStatusOf(String name, String fileName) {
        String n = safeName(name);
        return fileName.equals(n + ".status") || (fileName.startsWith(n + "@") && fileName.endsWith(".status"));
    }

    /**
     * Ostinato: the cells inside the columns of {@code box} (x1,y1,z1,x2,y2,z2; only x and z are compared), same name
     * and version. One bot's share of a split live build.
     */
    public Blueprint part(int[] box) {
        Blueprint b = new Blueprint(name);
        for (Map.Entry<Long, Cell> e : cells.entrySet()) {
            Cell c = e.getValue();
            if (c.x >= box[0] && c.x <= box[3] && c.z >= box[2] && c.z <= box[5]) {
                b.cells.put(e.getKey(), c);
            }
        }
        b.version = version;
        return b;
    }

    /** Ostinato: x1,y1,z1,x2,y2,z2 around every cell, or null when empty. */
    public int[] bounds() {
        if (cells.isEmpty()) {
            return null;
        }
        int[] r = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells.values()) {
            r[0] = Math.min(r[0], c.x);
            r[1] = Math.min(r[1], c.y);
            r[2] = Math.min(r[2], c.z);
            r[3] = Math.max(r[3], c.x);
            r[4] = Math.max(r[4], c.y);
            r[5] = Math.max(r[5], c.z);
        }
        return r;
    }

    /** {@code version placed total note} then one {@code x y z} line per placed cell. */
    public static String writeStatus(long version, List<int[]> placed, int total, String note) {
        return writeStatus(version, placed, total, note, List.of());
    }

    /**
     * {@code version placed total note}, then {@code x y z} lines for placed/matched cells, then
     * {@code ! <kind> <x> <y> <z> <expected> <actual> <reason>} lines for discrepancies.
     */
    public static String writeStatus(long version, List<int[]> placed, int total, String note,
                                     List<Discrepancy> discrepancies) {
        StringBuilder sb = new StringBuilder();
        sb.append(version).append(' ').append(placed.size()).append(' ').append(total).append(' ')
                .append(note == null || note.isBlank() ? "-" : note.replace('\n', ' ')).append('\n');
        for (int[] p : placed) {
            sb.append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append('\n');
        }
        if (discrepancies != null) {
            for (Discrepancy d : discrepancies) {
                if (d == null) {
                    continue;
                }
                String exp = sanitizeToken(d.expected());
                String act = sanitizeToken(d.actual());
                String rsn = d.reason().replace('\r', ' ').replace('\n', ' ').trim();
                sb.append("! ").append(d.kind().name()).append(' ')
                        .append(d.x()).append(' ').append(d.y()).append(' ').append(d.z()).append(' ')
                        .append(exp).append(' ').append(act);
                if (!rsn.isEmpty()) {
                    sb.append(' ').append(rsn);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String sanitizeToken(String s) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.trim().replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    public record Status(long version, int placed, int total, String note, List<int[]> cells,
                         List<Discrepancy> discrepancies) {
        public Status {
            cells = cells == null ? List.of() : List.copyOf(cells);
            discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
        }

        public Status(long version, int placed, int total, String note, List<int[]> cells) {
            this(version, placed, total, note, cells, List.of());
        }
    }

    public static Status readStatus(String text) {
        String[] lines = text.split("\\R");
        String[] h = lines[0].trim().split(" ", 4);
        List<int[]> cells = new ArrayList<>();
        List<Discrepancy> discrepancies = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("! ")) {
                String[] w = trimmed.split(" ", 8);
                if (w.length >= 7) {
                    try {
                        DiscrepancyKind kind = DiscrepancyKind.valueOf(w[1]);
                        int x = Integer.parseInt(w[2]);
                        int y = Integer.parseInt(w[3]);
                        int z = Integer.parseInt(w[4]);
                        String exp = w[5];
                        String act = w[6];
                        String rsn = w.length > 7 ? w[7] : "";
                        discrepancies.add(new Discrepancy(x, y, z, kind, exp, act, rsn));
                    } catch (IllegalArgumentException ignored) {
                        // ignore malformed discrepancy line
                    }
                }
                continue;
            }
            String[] w = trimmed.split(" ");
            if (w.length == 3) {
                cells.add(new int[]{Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2])});
            }
        }
        return new Status(Long.parseLong(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                h.length > 3 ? h[3] : "", cells, discrepancies);
    }
}
