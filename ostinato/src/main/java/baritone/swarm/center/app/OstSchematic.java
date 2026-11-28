package baritone.swarm.center.app;

import baritone.swarm.rts.BlockStateSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ostinato: the studio {@code ost1} text schematic, with no Minecraft types. Air is omitted. A block id is the
 * name after a {@code minecraft:} prefix ({@code [a-z0-9_]+}), optionally followed by {@code [key=value,...]}
 * explicit block-state properties. Legacy name-only lines have empty properties. The same cell listed twice keeps
 * the later block.
 */
public final class OstSchematic {

    public static final int MAX_EDGE = 512;
    public static final int MAX_CELLS = 20_000;

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
            // Ostinato: 4th element carries world Y for the 3D isometric view; 2D callers read indices 0..2.
            if (oy < -64) {
                continue;
            }
            out.add(new int[]{ox + c.x, oz + c.z, BlockColors.color(c.block), oy + c.y});
        }
        return out;
    }

    public String write() {
        StringBuilder sb = new StringBuilder();
        sb.append("ost1\n").append(sx).append(' ').append(sy).append(' ').append(sz).append('\n');
        for (Cell c : cells) {
            sb.append(c.x).append(' ').append(c.y).append(' ').append(c.z).append(' ').append(c.spec()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Build from local, non-air cells (coordinates >= 0). Size is the tight volume around them,
     * or 1x1x1 when empty.
     */
    public static OstSchematic of(List<Cell> in) {
        if (in == null || in.isEmpty()) {
            return new OstSchematic(1, 1, 1, List.of());
        }
        Map<Long, Cell> at = new LinkedHashMap<>();
        int sx = 1, sy = 1, sz = 1;
        for (Cell raw : in) {
            if (raw == null) {
                continue;
            }
            int x = raw.x(), y = raw.y(), z = raw.z();
            if (x < 0 || y < 0 || z < 0) {
                throw new IllegalArgumentException("block outside the volume: " + x + " " + y + " " + z);
            }
            Cell norm = new Cell(x, y, z, raw.block(), raw.properties());
            at.put(key(x, y, z), norm);
            if (at.size() > MAX_CELLS) {
                throw new IllegalArgumentException("schematic has more than " + MAX_CELLS + " blocks");
            }
            sx = Math.max(sx, x + 1);
            sy = Math.max(sy, y + 1);
            sz = Math.max(sz, z + 1);
        }
        if (sx > MAX_EDGE || sy > MAX_EDGE || sz > MAX_EDGE) {
            throw new IllegalArgumentException("schematic size out of range");
        }
        return new OstSchematic(sx, sy, sz, new ArrayList<>(at.values()));
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
            BlockStateSpec.Parsed spec = BlockStateSpec.parse(p[3]);
            at.put(key(x, y, z), new Cell(x, y, z, spec.block(), spec.properties()));
            if (at.size() > MAX_CELLS) {
                throw new IllegalArgumentException("schematic has more than " + MAX_CELLS + " blocks");
            }
        }
        return new OstSchematic(sx, sy, sz, new ArrayList<>(at.values()));
    }

    static String blockName(String raw) {
        return BlockStateSpec.parse(raw).block();
    }

    private static long key(int x, int y, int z) {
        return (((long) x) << 42) | (((long) y) << 21) | z;
    }
}
