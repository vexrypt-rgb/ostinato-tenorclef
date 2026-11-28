package baritone.swarm.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: RTS storage organizer. Pure: which chest each kind of item belongs in, from what the bot has seen in
 * the chests. Each category (ores, stone, wood, farm, mob drops, gear, other) gets its own chest: categories pick in
 * order of how much of them is stored, each taking the free chest that already holds the most of it, so a sorted
 * room stays put and an unsorted one moves the least. With more categories than chests the extra ones share the
 * chest holding most of them. The bot then carries every stack whose chest is not its home to its home.
 */
public final class StoragePlan {

    public enum Category { ORES, STONE, WOOD, FARM, MOB, GEAR, OTHER }

    /** Seen contents: chest index to item id to count. */
    private final Map<Integer, Map<String, Integer>> seen = new HashMap<>();
    private final int chests;

    public StoragePlan(int chests) {
        if (chests < 2) {
            throw new IllegalArgumentException("storage needs at least 2 chests");
        }
        this.chests = chests;
    }

    public int chests() {
        return chests;
    }

    /** Replaces what chest {@code i} holds (read when its screen is open). */
    public void saw(int chest, Map<String, Integer> contents) {
        seen.put(chest, new HashMap<>(contents));
    }

    public boolean surveyed() {
        return seen.size() == chests;
    }

    public static Category category(String id) {
        if (id == null) {
            return Category.OTHER;
        }
        if (RoleItems.isTool(id) || RoleItems.isArmor(id)) {
            return Category.GEAR;
        }
        if (RoleItems.isOre(id) || id.endsWith("_ingot") || id.endsWith("_nugget") || id.startsWith("raw_")) {
            return Category.ORES;
        }
        if (id.endsWith("_log") || id.endsWith("_planks") || id.endsWith("_wood") || id.equals("stick")
                || id.endsWith("_sapling")) {
            return Category.WOOD;
        }
        if (RoleItems.isFood(id) || id.endsWith("_seeds") || id.equals("wheat") || id.equals("sugar_cane")
                || id.equals("pumpkin") || id.equals("melon_slice") || id.equals("bone_meal")) {
            return Category.FARM;
        }
        if (id.equals("string") || id.equals("bone") || id.equals("rotten_flesh") || id.equals("gunpowder")
                || id.equals("spider_eye") || id.equals("leather") || id.equals("feather") || id.equals("ender_pearl")
                || id.equals("slime_ball")) {
            return Category.MOB;
        }
        if (id.equals("cobblestone") || id.equals("stone") || id.equals("cobbled_deepslate") || id.equals("deepslate")
                || id.equals("dirt") || id.equals("gravel") || id.equals("sand") || id.equals("andesite")
                || id.equals("diorite") || id.equals("granite") || id.equals("tuff") || id.equals("flint")) {
            return Category.STONE;
        }
        return Category.OTHER;
    }

    /** Category to chest index, from everything seen so far. Categories nobody has seen are absent. */
    public Map<Category, Integer> homes() {
        Map<Category, int[]> perChest = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> e : seen.entrySet()) {
            for (Map.Entry<String, Integer> it : e.getValue().entrySet()) {
                perChest.computeIfAbsent(category(it.getKey()), k -> new int[chests])[e.getKey()] += it.getValue();
            }
        }
        List<Category> order = new ArrayList<>(perChest.keySet());
        order.sort((a, b) -> {
            int c = Integer.compare(sum(perChest.get(b)), sum(perChest.get(a)));
            return c != 0 ? c : Integer.compare(a.ordinal(), b.ordinal());
        });
        boolean[] taken = new boolean[chests];
        Map<Category, Integer> homes = new LinkedHashMap<>();
        for (Category c : order) {
            int[] counts = perChest.get(c);
            int best = pick(counts, taken, true);
            if (best < 0) {
                best = pick(counts, taken, false);
            }
            taken[best] = true;
            homes.put(c, best);
        }
        return homes;
    }

    /** The chest {@code id} belongs in, or -1 when its category is unknown. */
    public int home(String id) {
        Integer h = homes().get(category(id));
        return h == null ? -1 : h;
    }

    /** True when a stack of {@code id} in chest {@code chest} should be carried elsewhere. */
    public boolean misplaced(int chest, String id) {
        int h = home(id);
        return h >= 0 && h != chest;
    }

    /** Stacks out of place across every seen chest (0 means sorted). */
    public int misplacedStacks(Map<Integer, List<String>> stacks) {
        int n = 0;
        for (Map.Entry<Integer, List<String>> e : stacks.entrySet()) {
            for (String id : e.getValue()) {
                if (misplaced(e.getKey(), id)) {
                    n++;
                }
            }
        }
        return n;
    }

    private int pick(int[] counts, boolean[] taken, boolean freeOnly) {
        int best = -1;
        for (int i = 0; i < chests; i++) {
            if (freeOnly && taken[i]) {
                continue;
            }
            if (best < 0 || counts[i] > counts[best]) {
                best = i;
            }
        }
        return best;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) {
            s += v;
        }
        return s;
    }
}
