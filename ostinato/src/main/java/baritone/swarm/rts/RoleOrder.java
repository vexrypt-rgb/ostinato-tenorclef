package baritone.swarm.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: RTS mode. One role assignment as it travels: {@code role <name> [key=value ...]}. Parsed the same way
 * on the bot and in the window so the window can show the exact line before it sends it.
 *
 * <p>Keys: {@code at=x,y,z} post (farm center), {@code r=n} radius, {@code chest=x,y,z} storage chest,
 * {@code chests=x,y,z;x,y,z} chests a storage organizer sorts, {@code ore=a,b} blocks a miner mines,
 * {@code batch=n} items before a deposit, {@code keep=n} building blocks a miner keeps, {@code set=iron|copper|leather}.
 */
public final class RoleOrder {

    public static final int MAX_RADIUS = 64;
    public static final int MAX_CHESTS = 16;
    public static final int DEFAULT_RADIUS = 16;
    public static final int DEFAULT_BATCH = 64;

    public final Role role;
    public final int[] at;
    public final int radius;
    public final int[] chest;
    public final List<int[]> chests;
    public final List<String> ores;
    public final int batch;
    public final int keep;
    public final String set;
    /** Ostinato: x1,y1,z1,x2,y2,z2 (min then max): the farm or mine is confined to this box; null = none. */
    public final int[] region;

    private RoleOrder(Role role, int[] at, int radius, int[] chest, List<int[]> chests, List<String> ores, int batch,
                      int keep, String set, int[] region) {
        this.role = role;
        this.at = at;
        this.radius = radius;
        this.chest = chest;
        this.chests = Collections.unmodifiableList(chests);
        this.ores = Collections.unmodifiableList(ores);
        this.batch = batch;
        this.keep = keep;
        this.set = set;
        this.region = region;
    }

    public static RoleOrder idle() {
        return new RoleOrder(Role.IDLE, null, 0, null, List.of(), List.of(), 0, 0, "", null);
    }

    /** {@code tokens[0]} is the role name. Throws {@link IllegalArgumentException} with a short reason. */
    public static RoleOrder parse(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException("missing role (farmer, miner, storage, armorer, idle)");
        }
        Role role = Role.parse(tokens.get(0));
        if (role == null || !role.assignable()) {
            throw new IllegalArgumentException("unknown role " + tokens.get(0) + " (farmer, miner, storage, armorer, idle)");
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            int eq = t.indexOf('=');
            if (eq <= 0 || eq == t.length() - 1) {
                throw new IllegalArgumentException("expected key=value, got " + t);
            }
            String k = t.substring(0, eq).toLowerCase(Locale.ROOT);
            if (kv.put(k, t.substring(eq + 1)) != null) {
                throw new IllegalArgumentException("duplicate " + k);
            }
        }
        if (role == Role.IDLE) {
            if (!kv.isEmpty()) {
                throw new IllegalArgumentException("idle takes no options");
            }
            return idle();
        }
        int[] at = kv.containsKey("at") ? pos(kv.remove("at"), "at") : null;
        int radius = kv.containsKey("r") ? bounded(kv.remove("r"), "r", 1, MAX_RADIUS) : DEFAULT_RADIUS;
        int[] chest = kv.containsKey("chest") ? pos(kv.remove("chest"), "chest") : null;
        List<int[]> chests = new ArrayList<>();
        if (kv.containsKey("chests")) {
            for (String p : kv.remove("chests").split(";")) {
                if (!p.isBlank()) {
                    chests.add(pos(p, "chests"));
                }
            }
            if (chests.size() > MAX_CHESTS) {
                throw new IllegalArgumentException("at most " + MAX_CHESTS + " chests");
            }
        }
        List<String> ores = new ArrayList<>();
        if (kv.containsKey("ore")) {
            for (String o : kv.remove("ore").split(",")) {
                String id = blockId(o);
                if (!ores.contains(id)) {
                    ores.add(id);
                }
            }
        }
        int batch = kv.containsKey("batch") ? bounded(kv.remove("batch"), "batch", 1, 36 * 64) : DEFAULT_BATCH;
        int keep = kv.containsKey("keep") ? bounded(kv.remove("keep"), "keep", 0, 36 * 64) : 0;
        String set = kv.containsKey("set") ? kv.remove("set").toLowerCase(Locale.ROOT) : "";
        int[] region = kv.containsKey("region") ? box(kv.remove("region")) : null;
        if (region != null && role != Role.FARMER && role != Role.MINER) {
            throw new IllegalArgumentException("region is for farmers and miners");
        }
        if (!kv.isEmpty()) {
            throw new IllegalArgumentException("unknown option " + kv.keySet().iterator().next());
        }
        switch (role) {
            case MINER -> {
                if (ores.isEmpty()) {
                    throw new IllegalArgumentException("miner needs ore=<block>[,<block>]");
                }
            }
            case STORAGE -> {
                if (chests.isEmpty() && chest == null) {
                    throw new IllegalArgumentException("storage needs chests=x,y,z;x,y,z");
                }
                if (chests.isEmpty()) {
                    chests.add(chest);
                }
            }
            case ARMORER -> {
                if (!set.isEmpty() && !List.of("iron", "copper", "leather").contains(set)) {
                    throw new IllegalArgumentException("set is iron, copper or leather");
                }
                if (set.isEmpty()) {
                    set = "copper";
                }
            }
            default -> {
            }
        }
        if (!ores.isEmpty() && role != Role.MINER) {
            throw new IllegalArgumentException("ore is for miners");
        }
        return new RoleOrder(role, at, radius, chest, chests, ores, batch, keep, set, region);
    }

    public static RoleOrder parse(String line) {
        List<String> tokens = new ArrayList<>();
        for (String t : (line == null ? "" : line.trim()).split("\\s+")) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        if (!tokens.isEmpty() && tokens.get(0).equalsIgnoreCase("role")) {
            tokens.remove(0);
        }
        return parse(tokens);
    }

    /** The canonical {@code role ...} line (keys in a fixed order, defaults left out). */
    public String line() {
        StringBuilder sb = new StringBuilder("role ").append(role.id);
        if (role == Role.IDLE) {
            return sb.toString();
        }
        if (at != null) {
            sb.append(" at=").append(xyz(at));
        }
        if (radius != DEFAULT_RADIUS && role == Role.FARMER) {
            sb.append(" r=").append(radius);
        }
        if (chest != null && !(role == Role.STORAGE && chests.size() == 1 && chests.get(0) == chest)) {
            sb.append(" chest=").append(xyz(chest));
        }
        if (role == Role.STORAGE) {
            List<String> ps = new ArrayList<>();
            for (int[] c : chests) {
                ps.add(xyz(c));
            }
            sb.append(" chests=").append(String.join(";", ps));
        }
        if (!ores.isEmpty()) {
            sb.append(" ore=").append(String.join(",", ores));
        }
        if (batch != DEFAULT_BATCH) {
            sb.append(" batch=").append(batch);
        }
        if (keep != 0) {
            sb.append(" keep=").append(keep);
        }
        if (role == Role.ARMORER) {
            sb.append(" set=").append(set);
        }
        if (region != null) {
            sb.append(" region=").append(boxText(region));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return line();
    }

    public static String xyz(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }

    static int[] pos(String raw, String key) {
        String[] p = raw.split(",");
        if (p.length != 3) {
            throw new IllegalArgumentException(key + " wants x,y,z");
        }
        try {
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " wants whole numbers x,y,z");
        }
    }

    /** Largest region edge: the same 64 as a region lease key. */
    public static final int MAX_REGION_EDGE = 64;

    /** {@code x1,y1,z1:x2,y2,z2}, corners in any order, returned min then max. */
    public static int[] box(String raw) {
        String[] c = raw.split(":");
        if (c.length != 2) {
            throw new IllegalArgumentException("region wants x1,y1,z1:x2,y2,z2");
        }
        int[] a = pos(c[0], "region"), b = pos(c[1], "region");
        int[] r = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
        if (r[3] - r[0] >= MAX_REGION_EDGE || r[4] - r[1] >= MAX_REGION_EDGE || r[5] - r[2] >= MAX_REGION_EDGE) {
            throw new IllegalArgumentException("region edge over " + MAX_REGION_EDGE);
        }
        return r;
    }

    public static String boxText(int[] r) {
        return r[0] + "," + r[1] + "," + r[2] + ":" + r[3] + "," + r[4] + "," + r[5];
    }

    /** The region lease key for this order's box in dimension {@code dim} ({@code r:o:x1,y1,z1:x2,y2,z2}), or null. */
    public String regionKey(char dim) {
        return region == null ? null : "r:" + dim + ":" + boxText(region);
    }

    /** The chest lease key for this order's chest ({@code c:o:x,y,z}), or null. */
    public String chestKey(char dim) {
        return chest == null ? null : "c:" + dim + ":" + xyz(chest);
    }

    public boolean inRegion(int x, int y, int z) {
        return region == null || (x >= region[0] && x <= region[3] && y >= region[1] && y <= region[4]
                && z >= region[2] && z <= region[5]);
    }

    static int bounded(String raw, String key, int min, int max) {
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " wants a whole number");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        }
        return v;
    }

    static String blockId(String raw) {
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException("empty ore name");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                throw new IllegalArgumentException("bad block name " + raw);
            }
        }
        return id;
    }
}
