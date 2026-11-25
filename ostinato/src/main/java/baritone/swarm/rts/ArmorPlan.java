package baritone.swarm.rts;

import java.util.List;
import java.util.Set;

/**
 * Ostinato: what the RTS armor maker does next ({@code role armorer set=copper at=table chests=supply chest=deliver}).
 * Pure: the caller reads what is worn and carried; {@code RoleBehavior} carries the step out with controls only (walk,
 * use key on the table and chests, clicks on the open vanilla screen).
 *
 * <p>Order per piece: equip what it carries, else craft the next missing piece when it has the ingots, else gather
 * ingots from the supply chests. Once all four are worn it crafts one more set for the deliver chest, if one is given.
 */
public final class ArmorPlan {

    /** One armor piece: its slot name, the ingots it takes and the 3x3 grid cells (0..8, row-major) they go in. */
    public enum Piece {
        HELMET("helmet", 0, 1, 2, 3, 5),
        CHESTPLATE("chestplate", 0, 2, 3, 4, 5, 6, 7, 8),
        LEGGINGS("leggings", 0, 1, 2, 3, 5, 6, 8),
        BOOTS("boots", 3, 5, 6, 8);

        public final String suffix;
        public final int[] grid;

        Piece(String suffix, int... grid) {
            this.suffix = suffix;
            this.grid = grid;
        }

        public int ingots() {
            return grid.length;
        }

        public String id(String set) {
            return set + "_" + suffix;
        }
    }

    public enum Kind { EQUIP, DELIVER, CRAFT, GATHER, WAIT, DONE }

    /** The next step: {@code piece} for EQUIP/DELIVER/CRAFT, {@code need} ingots for GATHER/WAIT. */
    public record Step(Kind kind, Piece piece, int need, String note) {}

    private ArmorPlan() {}

    /** The material a set is crafted from. */
    public static String ingot(String set) {
        return switch (set) {
            case "leather" -> "leather";
            case "iron" -> "iron_ingot";
            default -> "copper_ingot";
        };
    }

    /** CraftingMenu slot of a grid cell (slot 0 is the result, 1..9 the grid). */
    public static int craftSlot(int cell) {
        return 1 + cell;
    }

    /**
     * @param set       copper, iron or leather
     * @param worn      ids worn now (any order)
     * @param carried   armor ids in the inventory (not worn)
     * @param ingots    the set's material carried
     * @param deliver   a deliver chest was given
     * @param delivered pieces already put in it this assignment
     * @param supplyLeft whether a supply chest has not been tried since the ingots last ran short
     */
    public static Step next(String set, Set<String> worn, List<String> carried, int ingots, boolean deliver,
                            int delivered, boolean supplyLeft) {
        for (Piece p : Piece.values()) {
            if (!worn.contains(p.id(set)) && carried.contains(p.id(set))) {
                return new Step(Kind.EQUIP, p, 0, "equip " + p.id(set));
            }
        }
        for (Piece p : Piece.values()) {
            if (deliver && worn.contains(p.id(set)) && carried.contains(p.id(set))) {
                return new Step(Kind.DELIVER, p, 0, "deliver " + p.id(set));
            }
        }
        Piece want = null;
        for (Piece p : Piece.values()) {
            if (!worn.contains(p.id(set))) {
                want = p;
                break;
            }
        }
        if (want == null && deliver && delivered < Piece.values().length) {
            want = Piece.values()[delivered];
        }
        if (want == null) {
            return new Step(Kind.DONE, null, 0, deliver ? "set worn and one delivered" : "set worn");
        }
        if (ingots >= want.ingots()) {
            return new Step(Kind.CRAFT, want, 0, "craft " + want.id(set));
        }
        int need = want.ingots() - ingots;
        if (supplyLeft) {
            return new Step(Kind.GATHER, want, need, "gather " + need + " " + ingot(set) + " for " + want.id(set));
        }
        return new Step(Kind.WAIT, want, need, "waiting for " + need + " " + ingot(set) + " for " + want.id(set));
    }
}
