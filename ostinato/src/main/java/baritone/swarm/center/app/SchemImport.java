package baritone.swarm.center.app;

import baritone.swarm.rts.Blueprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: JDK-only import of {@code .litematic} and Sponge {@code .schem} (v1/v2) into {@link OstSchematic}.
 * Block-state properties are dropped; ids are ost1 names ({@code cobblestone}, no {@code minecraft:} prefix).
 * Air is omitted. Classic MCEdit {@code .schematic} (numeric ids) is refused.
 */
public final class SchemImport {

    private SchemImport() {}

    public static OstSchematic fromFile(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("schematic file not found");
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".schematic")) {
            throw new IllegalArgumentException("classic .schematic (numeric ids) is not imported; use .litematic or .schem");
        }
        return fromBytes(Files.readAllBytes(file));
    }

    public static OstSchematic fromBytes(byte[] data) {
        Nbt.Compound root = Nbt.read(data);
        if (root.has("Regions")) {
            return litematic(root);
        }
        if (root.has("Palette") && root.has("BlockData") && root.has("Width")) {
            return sponge(root);
        }
        if (root.has("palette") && root.has("blocks") && root.has("size")) {
            return structureNbt(root);
        }
        if (root.has("Blocks") && root.has("Width")) {
            throw new IllegalArgumentException("classic .schematic (numeric ids) is not imported; use .litematic, .schem or .nbt");
        }
        throw new IllegalArgumentException("not a .litematic, Sponge .schem or structure .nbt");
    }

    /** Ostinato: import vanilla Minecraft structure-block {@code .nbt} (palette + blocks + size). */
    static OstSchematic structureNbt(Nbt.Compound root) {
        List<String> palette = new ArrayList<>();
        for (Nbt.Tag t : root.list("palette")) {
            palette.add(paletteEntry(t));
        }
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("structure .nbt has no palette");
        }
        List<OstSchematic.Cell> cells = new ArrayList<>();
        for (Nbt.Tag t : root.list("blocks")) {
            if (!(t instanceof Nbt.Compound b)) {
                continue;
            }
            int state = b.i("state", -1);
            if (state < 0 || state >= palette.size()) {
                throw new IllegalArgumentException("structure .nbt state index " + state);
            }
            String id = palette.get(state);
            if (skip(id)) {
                continue;
            }
            List<Nbt.Tag> pos = b.list("pos");
            if (pos.size() != 3) {
                continue;
            }
            int x = intTag(pos.get(0));
            int y = intTag(pos.get(1));
            int z = intTag(pos.get(2));
            try {
                cells.add(new OstSchematic.Cell(x, y, z, OstSchematic.blockName(id)));
            } catch (IllegalArgumentException ignored) {
                // drop unknown block id characters
            }
        }
        return OstSchematic.of(cells);
    }

    private static int intTag(Nbt.Tag t) {
        if (t instanceof Nbt.IntTag n) return n.v();
        if (t instanceof Nbt.ShortTag n) return n.v();
        if (t instanceof Nbt.ByteTag n) return n.v();
        return 0;
    }

    /** World-anchored blueprint at {@code origin} (or 0,0,0). */
    public static Blueprint toBlueprint(String name, OstSchematic s, int[] origin) {
        Blueprint b = new Blueprint(name);
        int ox = origin == null ? 0 : origin[0];
        int oy = origin == null ? 0 : origin[1];
        int oz = origin == null ? 0 : origin[2];
        for (OstSchematic.Cell c : s.cells()) {
            b.paint(ox + c.x(), oy + c.y(), oz + c.z(), c.block());
        }
        return b;
    }

    public static String blockId(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        int bracket = id.indexOf('[');
        if (bracket >= 0) {
            id = id.substring(0, bracket);
        }
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        return id;
    }

    public static boolean skip(String id) {
        String n = blockId(id);
        return n.isEmpty() || n.equals("air") || n.equals("cave_air") || n.equals("void_air");
    }

    static OstSchematic litematic(Nbt.Compound root) {
        Nbt.Compound regions = root.child("Regions");
        if (regions.map.isEmpty()) {
            throw new IllegalArgumentException("litematic has no regions");
        }
        int ox = Integer.MAX_VALUE, oy = Integer.MAX_VALUE, oz = Integer.MAX_VALUE;
        List<Region> built = new ArrayList<>();
        for (Nbt.Tag t : regions.map.values()) {
            if (!(t instanceof Nbt.Compound sub)) {
                continue;
            }
            Region r = readLitematicRegion(sub);
            built.add(r);
            ox = Math.min(ox, r.x0);
            oy = Math.min(oy, r.y0);
            oz = Math.min(oz, r.z0);
        }
        if (built.isEmpty()) {
            throw new IllegalArgumentException("litematic has no regions");
        }
        List<OstSchematic.Cell> cells = new ArrayList<>();
        for (Region r : built) {
            for (OstSchematic.Cell c : r.cells) {
                cells.add(new OstSchematic.Cell(c.x() + r.x0 - ox, c.y() + r.y0 - oy, c.z() + r.z0 - oz, c.block()));
            }
        }
        return OstSchematic.of(cells);
    }

    private static Region readLitematicRegion(Nbt.Compound sub) {
        int px = axis(sub.child("Position"), "x");
        int py = axis(sub.child("Position"), "y");
        int pz = axis(sub.child("Position"), "z");
        int sx = axis(sub.child("Size"), "x");
        int sy = axis(sub.child("Size"), "y");
        int sz = axis(sub.child("Size"), "z");
        // Ostinato: same min as Baritone's LitematicaSchematic: Math.min(pos, pos+size+1)
        int x0 = Math.min(px, px + sx + 1);
        int y0 = Math.min(py, py + sy + 1);
        int z0 = Math.min(pz, pz + sz + 1);
        int sizeX = Math.abs(sx);
        int sizeY = Math.abs(sy);
        int sizeZ = Math.abs(sz);
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new IllegalArgumentException("litematic region has no volume");
        }
        List<String> palette = new ArrayList<>();
        for (Nbt.Tag t : sub.list("BlockStatePalette")) {
            palette.add(paletteEntry(t));
        }
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("litematic region has no palette");
        }
        int bits = Math.max(2, (int) Math.ceil(Math.log(palette.size()) / Math.log(2)));
        long volume = (long) sizeX * sizeY * sizeZ;
        long[] packed = sub.longs("BlockStates");
        List<OstSchematic.Cell> cells = new ArrayList<>();
        int index = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int pal = litematicAt(packed, bits, volume, index++);
                    if (pal < 0 || pal >= palette.size()) {
                        throw new IllegalArgumentException("litematic palette index " + pal);
                    }
                    String id = palette.get(pal);
                    if (!skip(id)) {
                        try {
                            cells.add(new OstSchematic.Cell(x, y, z, OstSchematic.blockName(id)));
                        } catch (IllegalArgumentException ignored) {
                            // unknown id characters: drop the cell
                        }
                    }
                }
            }
        }
        return new Region(x0, y0, z0, cells);
    }

    private static OstSchematic sponge(Nbt.Compound root) {
        int sx = root.i("Width", 0);
        int sy = root.i("Height", 0);
        int sz = root.i("Length", 0);
        if (sx < 1 || sy < 1 || sz < 1) {
            throw new IllegalArgumentException("schem size out of range");
        }
        Map<Integer, String> palette = new LinkedHashMap<>();
        Nbt.Compound pal = root.child("Palette");
        for (Map.Entry<String, Nbt.Tag> e : pal.map.entrySet()) {
            String id = blockId(e.getKey());
            int index = (e.getValue() instanceof Nbt.IntTag n) ? n.v() : 0;
            palette.put(index, id);
        }
        byte[] raw = root.bytes("BlockData");
        int volume = sx * sy * sz;
        List<OstSchematic.Cell> cells = new ArrayList<>();
        int offset = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (offset >= raw.length) {
                        throw new IllegalArgumentException("schem BlockData is short");
                    }
                    int[] var = varInt(raw, offset);
                    offset = var[1];
                    String id = palette.get(var[0]);
                    if (id == null) {
                        throw new IllegalArgumentException("schem palette index " + var[0]);
                    }
                    if (!skip(id)) {
                        try {
                            cells.add(new OstSchematic.Cell(x, y, z, OstSchematic.blockName(id)));
                        } catch (IllegalArgumentException ignored) {
                            // drop
                        }
                    }
                }
            }
        }
        if (cells.size() > volume) {
            throw new IllegalArgumentException("schem has more blocks than its volume");
        }
        return OstSchematic.of(cells);
    }

    static String paletteEntry(Nbt.Tag t) {
        if (t instanceof Nbt.StringTag s) {
            return blockId(s.v());
        }
        if (t instanceof Nbt.Compound c) {
            String name = c.s("Name");
            if (name.isEmpty()) {
                name = c.s("name");
            }
            return blockId(name);
        }
        return "";
    }

    static int axis(Nbt.Compound c, String lower) {
        String u = lower.toUpperCase(Locale.ROOT);
        if (c.has(lower)) {
            return c.i(lower, 0);
        }
        return c.i(u, 0);
    }

    /** VarInt at {@code offset}: {@code {value, nextOffset}}. */
    static int[] varInt(byte[] data, int offset) {
        int n = 0;
        int shift = 0;
        int i = offset;
        while (i < data.length) {
            int b = data[i++] & 0xff;
            n |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{n, i};
            }
            shift += 7;
            if (shift > 35) {
                throw new IllegalArgumentException("schem varint too long");
            }
        }
        throw new IllegalArgumentException("schem BlockData is short");
    }

    static byte[] writeVarInt(int value) {
        int v = value;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(5);
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        return out.toByteArray();
    }

    static int litematicAt(long[] arr, int bits, long size, long index) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("litematic index " + index);
        }
        if (arr == null || arr.length == 0) {
            return 0;
        }
        long mask = (1L << bits) - 1L;
        long startOffset = index * (long) bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) (((index + 1L) * (long) bits - 1L) >> 6);
        int startBit = (int) (startOffset & 0x3F);
        if (startArr >= arr.length) {
            return 0;
        }
        if (startArr == endArr) {
            return (int) (arr[startArr] >>> startBit & mask);
        }
        int endOffset = 64 - startBit;
        long hi = endArr < arr.length ? arr[endArr] : 0;
        return (int) ((arr[startArr] >>> startBit | hi << endOffset) & mask);
    }

    static void litematicPut(long[] arr, int bits, long index, int value) {
        long mask = (1L << bits) - 1L;
        long v = value & mask;
        long startOffset = index * (long) bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) (((index + 1L) * (long) bits - 1L) >> 6);
        int startBit = (int) (startOffset & 0x3F);
        if (startArr == endArr) {
            arr[startArr] = arr[startArr] & ~(mask << startBit) | (v << startBit);
        } else {
            int endOffset = 64 - startBit;
            arr[startArr] = arr[startArr] & ((1L << startBit) - 1) | (v << startBit);
            arr[endArr] = arr[endArr] & ~((1L << (bits - endOffset)) - 1) | (v >>> endOffset);
        }
    }

    static int litematicLongCount(int bits, long volume) {
        long bitsTotal = volume * (long) bits;
        return (int) ((bitsTotal + 63) / 64);
    }

    /** A tiny Sponge v2 file, for tests. {@code cells} are local and non-air. */
    public static byte[] writeSchem(int sx, int sy, int sz, List<OstSchematic.Cell> cells) {
        Map<String, Integer> pal = new LinkedHashMap<>();
        pal.put("minecraft:air", 0);
        for (OstSchematic.Cell c : cells) {
            pal.putIfAbsent("minecraft:" + c.block(), pal.size());
        }
        int[] index = new int[sx * sy * sz]; // air
        for (OstSchematic.Cell c : cells) {
            index[(c.y() * sz + c.z()) * sx + c.x()] = pal.get("minecraft:" + c.block());
        }
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        for (int v : index) {
            try {
                data.write(writeVarInt(v));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        Nbt.Compound palette = new Nbt.Compound();
        for (Map.Entry<String, Integer> e : pal.entrySet()) {
            palette.put(e.getKey(), e.getValue());
        }
        Nbt.Compound root = new Nbt.Compound();
        root.put("Version", 2);
        root.put("Width", sx);
        root.put("Height", sy);
        root.put("Length", sz);
        root.put("Palette", palette);
        root.put("BlockData", data.toByteArray());
        return Nbt.writeGzip(root, "Schematic");
    }

    /** A tiny single-region litematic, for tests. {@code cells} are local and non-air. */
    public static byte[] writeLitematic(int sx, int sy, int sz, List<OstSchematic.Cell> cells) {
        List<String> pal = new ArrayList<>();
        pal.add("minecraft:air");
        Map<String, Integer> at = new LinkedHashMap<>();
        at.put("air", 0);
        int[] index = new int[sx * sy * sz];
        for (OstSchematic.Cell c : cells) {
            int i = at.computeIfAbsent(c.block(), k -> {
                pal.add("minecraft:" + k);
                return pal.size() - 1;
            });
            index[(c.y() * sz + c.z()) * sx + c.x()] = i;
        }
        int bits = Math.max(2, (int) Math.ceil(Math.log(pal.size()) / Math.log(2)));
        long volume = (long) sx * sy * sz;
        long[] packed = new long[litematicLongCount(bits, volume)];
        for (int i = 0; i < index.length; i++) {
            litematicPut(packed, bits, i, index[i]);
        }
        List<Nbt.Tag> palette = new ArrayList<>();
        for (String name : pal) {
            Nbt.Compound e = new Nbt.Compound();
            e.put("Name", name);
            palette.add(e);
        }
        Nbt.Compound pos = new Nbt.Compound();
        pos.put("x", 0);
        pos.put("y", 0);
        pos.put("z", 0);
        Nbt.Compound size = new Nbt.Compound();
        size.put("x", sx);
        size.put("y", sy);
        size.put("z", sz);
        Nbt.Compound region = new Nbt.Compound();
        region.put("Position", pos);
        region.put("Size", size);
        region.put("BlockStatePalette", new Nbt.ListTag(Nbt.COMPOUND, palette));
        region.put("BlockStates", packed);
        Nbt.Compound regions = new Nbt.Compound();
        regions.put("main", region);
        Nbt.Compound root = new Nbt.Compound();
        root.put("Version", 6);
        root.put("MinecraftDataVersion", 3700);
        root.put("Regions", regions);
        return Nbt.writeGzip(root, "");
    }

    /** Ostinato: write a vanilla Minecraft structure-block {@code .nbt} file (gzipped). */
    public static byte[] writeStructureNbt(int sx, int sy, int sz, List<OstSchematic.Cell> cells) {
        List<Nbt.Tag> palTags = new ArrayList<>();
        Map<String, Integer> palIdx = new LinkedHashMap<>();
        List<Nbt.Tag> blockTags = new ArrayList<>();
        for (OstSchematic.Cell c : cells) {
            int state = palIdx.computeIfAbsent(c.block(), k -> {
                Nbt.Compound entry = new Nbt.Compound();
                entry.put("Name", "minecraft:" + k);
                palTags.add(entry);
                return palTags.size() - 1;
            });
            Nbt.Compound b = new Nbt.Compound();
            b.put("state", state);
            b.put("pos", new Nbt.ListTag(Nbt.INT, List.of(new Nbt.IntTag(c.x()), new Nbt.IntTag(c.y()), new Nbt.IntTag(c.z()))));
            blockTags.add(b);
        }
        Nbt.Compound root = new Nbt.Compound();
        root.put("DataVersion", 3700);
        root.put("size", new Nbt.ListTag(Nbt.INT, List.of(new Nbt.IntTag(sx), new Nbt.IntTag(sy), new Nbt.IntTag(sz))));
        root.put("palette", new Nbt.ListTag(Nbt.COMPOUND, palTags));
        root.put("blocks", new Nbt.ListTag(Nbt.COMPOUND, blockTags));
        root.put("entities", new Nbt.ListTag(Nbt.COMPOUND, List.of()));
        return Nbt.writeGzip(root, "");
    }

    /**
     * Ostinato: export a {@link Blueprint} to {@code .ost}, Sponge v2 {@code .schem}, {@code .litematic},
     * or vanilla structure {@code .nbt} based on the file extension.
     */
    public static void exportBlueprint(Blueprint blueprint, Path target) throws IOException {
        if (blueprint == null || blueprint.size() == 0) {
            throw new IllegalArgumentException("blueprint is empty");
        }
        if (target == null) {
            throw new IllegalArgumentException("export path is required");
        }
        int[] b = blueprint.bounds();
        int ox = b[0], oy = b[1], oz = b[2];
        int sx = b[3] - ox + 1;
        int sy = b[4] - oy + 1;
        int sz = b[5] - oz + 1;
        List<OstSchematic.Cell> local = new ArrayList<>(blueprint.size());
        for (Blueprint.Cell c : blueprint.cells()) {
            local.add(new OstSchematic.Cell(c.x() - ox, c.y() - oy, c.z() - oz, c.block()));
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".schem")) {
            Files.write(target, writeSchem(sx, sy, sz, local));
        } else if (name.endsWith(".litematic")) {
            Files.write(target, writeLitematic(sx, sy, sz, local));
        } else if (name.endsWith(".nbt")) {
            Files.write(target, writeStructureNbt(sx, sy, sz, local));
        } else if (name.endsWith(".ost") || name.endsWith(".ostinato")) {
            Files.writeString(target, blueprint.write());
        } else {
            throw new IllegalArgumentException("unsupported export format (use .schem, .litematic, .nbt or .ost)");
        }
    }

    private record Region(int x0, int y0, int z0, List<OstSchematic.Cell> cells) {}
}
