package baritone.swarm.center.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ostinato: the studio {@code ost1} text schematic, with no Minecraft types. Air is omitted. A block id is the
 * name after a {@code minecraft:} prefix, and only {@code [a-z0-9_]+}. The same cell listed twice keeps the later
 * block.
 */
public final class OstSchematic {

    public static final int MAX_EDGE = 512;
    public static final int MAX_CELLS = 20_000;

    public record Cell(int x, int y, int z, String block) {}

    private final int sx;
    private final int sy;
    private final int sz;
    private final List<Cell> cells;

    private OstSchematic(int sx, int sy, int sz, List<Cell> cells) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = List.copyOf(cells);
    }

    public int width() { return sx; }
    public int height() { return sy; }
    public int length() { return sz; }
    public List<Cell> cells() { return cells; }

    public Map<String, Integer> materials() {
        Map<String, Integer> out = new TreeMap<>();
        for (Cell c : cells) {
            out.merge(c.block, 1, Integer::sum);
        }
        return out;
    }

    /**
     * One mark per non-air cell, in world XZ, placed at {@code (ox, oy, oz)}. {@code layer} is a schematic-local Y;
     * null draws every layer. Each mark is {@code {worldX, worldZ, rgb}} and rgb 0 means the block has no map color.
     */
    public List<int[]> ghost(int ox, int oy, int oz, Integer layer) {
        List<int[]> out = new ArrayList<>();
        for (Cell c : cells) {
            if (layer != null && c.y != layer) {
                continue;
            }
            // oy is the placement height. The map is top-down, so a mark is the cell's XZ only.
            if (oy < -64) {
                continue;
            }
            out.add(new int[]{ox + c.x, oz + c.z, BlockColors.color(c.block)});
        }
        return out;
    }

    public String write() {
        StringBuilder sb = new StringBuilder();
        sb.append("ost1\n").append(sx).append(' ').append(sy).append(' ').append(sz).append('\n');
        for (Cell c : cells) {
            sb.append(c.x).append(' ').append(c.y).append(' ').append(c.z).append(' ').append(c.block).append('\n');
        }
        return sb.toString();
    }

    public static OstSchematic read(String text) {
        if (text == null) {
            throw new IllegalArgumentException("not an ostinato schematic");
        }
        String[] lines = text.split("\\R");
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].trim().equals("ost1")) {
            throw new IllegalArgumentException("not an ostinato schematic");
        }
        i++;
        if (i >= lines.length) {
            throw new IllegalArgumentException("missing size");
        }
        String[] dim = lines[i++].trim().split(" ");
        if (dim.length != 3) {
            throw new IllegalArgumentException("size wants three numbers");
        }
        int sx = Integer.parseInt(dim[0]);
        int sy = Integer.parseInt(dim[1]);
        int sz = Integer.parseInt(dim[2]);
        if (sx < 1 || sy < 1 || sz < 1 || sx > MAX_EDGE || sy > MAX_EDGE || sz > MAX_EDGE) {
            throw new IllegalArgumentException("schematic size out of range");
        }
        Map<Long, Cell> at = new LinkedHashMap<>();
        for (; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split(" ");
            if (p.length != 4) {
                throw new IllegalArgumentException("bad block line: " + line);
            }
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                throw new IllegalArgumentException("block outside the volume: " + line);
            }
            String block = blockName(p[3]);
            at.put(key(x, y, z), new Cell(x, y, z, block));
            if (at.size() > MAX_CELLS) {
                throw new IllegalArgumentException("schematic has more than " + MAX_CELLS + " blocks");
            }
        }
        return new OstSchematic(sx, sy, sz, new ArrayList<>(at.values()));
    }

    static String blockName(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("unknown block");
        }
        String id = raw.startsWith("minecraft:") ? raw.substring("minecraft:".length()) : raw;
        if (id.isEmpty() || "air".equals(id)) {
            throw new IllegalArgumentException("unknown block " + raw);
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                throw new IllegalArgumentException("unknown block " + raw);
            }
        }
        return id;
    }

    private static long key(int x, int y, int z) {
        return (((long) x) << 42) | (((long) y) << 21) | z;
    }
}
