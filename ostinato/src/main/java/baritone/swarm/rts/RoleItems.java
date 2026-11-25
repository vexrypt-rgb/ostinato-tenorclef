package baritone.swarm.rts;

import java.util.Set;

/**
 * Ostinato: RTS mode. Which item ids a role hands to storage and which it keeps. Ids are plain names without the
 * {@code minecraft:} prefix. Pure, so the window's economy line and the bot agree on what counts as output.
 */
public final class RoleItems {

    private RoleItems() {}

    public static final Set<String> CROPS = Set.of("wheat", "carrot", "potato", "poisonous_potato", "beetroot",
            "melon_slice", "melon", "pumpkin", "sugar_cane", "cactus", "nether_wart", "cocoa_beans", "sweet_berries");
    public static final Set<String> SEEDS = Set.of("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds",
            "carrot", "potato", "nether_wart");
    public static final Set<String> MINED = Set.of("cobblestone", "cobbled_deepslate", "stone", "deepslate", "dirt",
            "gravel", "sand", "andesite", "diorite", "granite", "tuff", "calcite", "flint", "coal", "raw_iron",
            "raw_copper", "raw_gold", "diamond", "emerald", "lapis_lazuli", "redstone", "quartz", "amethyst_shard");
    /** Seeds a farmer keeps back for replanting. */
    public static final int SEED_KEEP = 16;

    public static boolean isTool(String id) {
        return id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_hoe")
                || id.endsWith("_sword") || id.equals("shears") || id.equals("bow") || id.equals("crossbow")
                || id.equals("shield") || id.equals("flint_and_steel") || id.equals("bucket") || id.equals("water_bucket");
    }

    public static boolean isArmor(String id) {
        return id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots");
    }

    public static boolean isFood(String id) {
        return Set.of("bread", "baked_potato", "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton",
                "cooked_cod", "cooked_salmon", "golden_carrot", "apple", "golden_apple", "carrot", "cookie",
                "pumpkin_pie", "beetroot_soup", "mushroom_stew", "rabbit_stew", "melon_slice", "sweet_berries")
                .contains(id);
    }

    public static boolean isOre(String id) {
        return id.endsWith("_ore") || id.startsWith("raw_") || id.equals("coal") || id.equals("diamond")
                || id.equals("emerald") || id.equals("lapis_lazuli") || id.equals("redstone") || id.equals("quartz")
                || id.equals("ancient_debris");
    }

    /** True when a role's output: the items a deposit moves. */
    public static boolean isOutput(Role role, String id) {
        if (id == null || id.isEmpty() || isTool(id) || isArmor(id)) {
            return false;
        }
        switch (role) {
            case FARMER:
                return CROPS.contains(id) || SEEDS.contains(id);
            case MINER:
                return MINED.contains(id) || isOre(id) || id.endsWith("_ore");
            case STORAGE:
            case ARMORER:
                return !isFood(id) && !id.equals("torch") && !id.equals("crafting_table");
            default:
                return false;
        }
    }

    /**
     * How many of {@code id} the bot keeps when it deposits. A farmer keeps a few seeds to replant, a miner keeps
     * {@code keep} building blocks to pillar and bridge with, everyone keeps food they are not farming.
     */
    public static int keep(Role role, String id, RoleOrder order) {
        if (role == Role.FARMER && SEEDS.contains(id)) {
            return SEED_KEEP;
        }
        if (role == Role.MINER && (id.equals("cobblestone") || id.equals("cobbled_deepslate") || id.equals("dirt"))) {
            return order == null ? 0 : order.keep;
        }
        return 0;
    }
}
