package baritone.swarm.center.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Ostinato: a JDK-only NBT reader/writer for schematic import in the command center. No Minecraft types.
 * Compounds keep insertion order. Lists are homogeneous. {@link #read} accepts gzip or uncompressed bytes.
 */
public final class Nbt {

    public static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    public sealed interface Tag permits ByteTag, ShortTag, IntTag, LongTag, FloatTag, DoubleTag, StringTag,
            ByteArrayTag, IntArrayTag, LongArrayTag, ListTag, Compound {}

    public record ByteTag(byte v) implements Tag {}
    public record ShortTag(short v) implements Tag {}
    public record IntTag(int v) implements Tag {}
    public record LongTag(long v) implements Tag {}
    public record FloatTag(float v) implements Tag {}
    public record DoubleTag(double v) implements Tag {}
    public record StringTag(String v) implements Tag {}
    public record ByteArrayTag(byte[] v) implements Tag {}
    public record IntArrayTag(int[] v) implements Tag {}
    public record LongArrayTag(long[] v) implements Tag {}
    public record ListTag(int type, List<Tag> v) implements Tag {}

    public static final class Compound implements Tag {
        public final Map<String, Tag> map = new LinkedHashMap<>();

        public Compound put(String k, Tag t) {
            map.put(k, t);
            return this;
        }

        public Compound put(String k, int v) { return put(k, new IntTag(v)); }
        public Compound put(String k, long v) { return put(k, new LongTag(v)); }
        public Compound put(String k, String v) { return put(k, new StringTag(v == null ? "" : v)); }
        public Compound put(String k, byte[] v) { return put(k, new ByteArrayTag(v)); }
        public Compound put(String k, int[] v) { return put(k, new IntArrayTag(v)); }
        public Compound put(String k, long[] v) { return put(k, new LongArrayTag(v)); }
        public Compound put(String k, Compound v) { return put(k, (Tag) v); }

        public Tag get(String k) { return map.get(k); }

        public Compound child(String k) {
            Tag t = map.get(k);
            return t instanceof Compound c ? c : new Compound();
        }

        public boolean has(String k) { return map.containsKey(k); }

        public int i(String k, int fallback) {
            Tag t = map.get(k);
            if (t instanceof IntTag n) {
                return n.v();
            }
            if (t instanceof ShortTag n) {
                return n.v();
            }
            if (t instanceof ByteTag n) {
                return n.v();
            }
            // litematic Position/Size often use capital X/Y/Z; callers pass the exact key
            return fallback;
        }

        public String s(String k) {
            Tag t = map.get(k);
            return t instanceof StringTag n ? n.v() : "";
        }

        public byte[] bytes(String k) {
            Tag t = map.get(k);
            return t instanceof ByteArrayTag n ? n.v() : new byte[0];
        }

        public long[] longs(String k) {
            Tag t = map.get(k);
            return t instanceof LongArrayTag n ? n.v() : new long[0];
        }

        public List<Tag> list(String k) {
            Tag t = map.get(k);
            return t instanceof ListTag n ? n.v() : List.of();
        }
    }

    private Nbt() {}

    public static Compound read(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("empty nbt");
        }
        try {
            return readStream(unwrap(data));
        } catch (IOException e) {
            throw new IllegalArgumentException("not an nbt file: " + e.getMessage());
        }
    }

    public static byte[] writeGzip(Compound root, String name) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(raw); DataOutputStream out = new DataOutputStream(gz)) {
                out.writeByte(COMPOUND);
                writeString(out, name == null ? "" : name);
                writeCompoundBody(out, root);
            }
            return raw.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DataInputStream unwrap(byte[] data) throws IOException {
        if (data.length >= 2 && (data[0] == (byte) 0x1f && data[1] == (byte) 0x8b)) {
            return new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)));
        }
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    private static Compound readStream(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type != COMPOUND) {
            throw new IOException("root is not a compound");
        }
        readString(in); // root name, ignored
        return readCompound(in);
    }

    private static Tag readTag(int type, DataInputStream in) throws IOException {
        return switch (type) {
            case BYTE -> new ByteTag(in.readByte());
            case SHORT -> new ShortTag(in.readShort());
            case INT -> new IntTag(in.readInt());
            case LONG -> new LongTag(in.readLong());
            case FLOAT -> new FloatTag(in.readFloat());
            case DOUBLE -> new DoubleTag(in.readDouble());
            case BYTE_ARRAY -> {
                int n = in.readInt();
                byte[] a = in.readNBytes(n);
                if (a.length != n) {
                    throw new EOFException();
                }
                yield new ByteArrayTag(a);
            }
            case STRING -> new StringTag(readString(in));
            case LIST -> {
                int ct = in.readUnsignedByte();
                int n = in.readInt();
                List<Tag> v = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    v.add(readTag(ct, in));
                }
                yield new ListTag(ct, v);
            }
            case COMPOUND -> readCompound(in);
            case INT_ARRAY -> {
                int n = in.readInt();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readInt();
                }
                yield new IntArrayTag(a);
            }
            case LONG_ARRAY -> {
                int n = in.readInt();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readLong();
                }
                yield new LongArrayTag(a);
            }
            default -> throw new IOException("bad nbt type " + type);
        };
    }

    private static Compound readCompound(DataInputStream in) throws IOException {
        Compound c = new Compound();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == END) {
                return c;
            }
            String name = readString(in);
            c.put(name, readTag(type, in));
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        byte[] a = in.readNBytes(n);
        if (a.length != n) {
            throw new EOFException();
        }
        return new String(a, StandardCharsets.UTF_8);
    }

    private static void writeCompoundBody(DataOutputStream out, Compound c) throws IOException {
        for (Map.Entry<String, Tag> e : c.map.entrySet()) {
            Tag t = e.getValue();
            out.writeByte(typeOf(t));
            writeString(out, e.getKey());
            writeTag(out, t);
        }
        out.writeByte(END);
    }

    private static void writeTag(DataOutputStream out, Tag t) throws IOException {
        switch (t) {
            case ByteTag n -> out.writeByte(n.v());
            case ShortTag n -> out.writeShort(n.v());
            case IntTag n -> out.writeInt(n.v());
            case LongTag n -> out.writeLong(n.v());
            case FloatTag n -> out.writeFloat(n.v());
            case DoubleTag n -> out.writeDouble(n.v());
            case StringTag n -> writeString(out, n.v());
            case ByteArrayTag n -> {
                out.writeInt(n.v().length);
                out.write(n.v());
            }
            case IntArrayTag n -> {
                out.writeInt(n.v().length);
                for (int v : n.v()) {
                    out.writeInt(v);
                }
            }
            case LongArrayTag n -> {
                out.writeInt(n.v().length);
                for (long v : n.v()) {
                    out.writeLong(v);
                }
            }
            case ListTag n -> {
                out.writeByte(n.type());
                out.writeInt(n.v().size());
                for (Tag c : n.v()) {
                    writeTag(out, c);
                }
            }
            case Compound n -> writeCompoundBody(out, n);
        }
    }

    private static int typeOf(Tag t) {
        return switch (t) {
            case ByteTag n -> BYTE;
            case ShortTag n -> SHORT;
            case IntTag n -> INT;
            case LongTag n -> LONG;
            case FloatTag n -> FLOAT;
            case DoubleTag n -> DOUBLE;
            case StringTag n -> STRING;
            case ByteArrayTag n -> BYTE_ARRAY;
            case IntArrayTag n -> INT_ARRAY;
            case LongArrayTag n -> LONG_ARRAY;
            case ListTag n -> LIST;
            case Compound n -> COMPOUND;
        };
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] a = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(a.length);
        out.write(a);
    }
}
