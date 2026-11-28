package adris.altoclef.tasks.brewing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure-Java immutable snapshot of an open vanilla Brewing Stand screen ({@code BrewingStandMenu}):
 * <ul>
 *     <li>Window slots 0, 1, 2: bottle slots (left, middle, right; max stack 1)</li>
 *     <li>Window slot 3: ingredient slot</li>
 *     <li>Window slot 4: fuel slot</li>
 *     <li>Window slots 5..40: player main inventory (5..31) + hotbar (32..40)</li>
 *     <li>Cursor stack, synced {@code fuelCharges} (0..20), and synced {@code brewTicks}</li>
 * </ul>
 * Includes a vanilla-accurate click & brew-cycle simulator for exhaustive unit testing.
 */
public final class BrewingStandSnapshot {

    public static final int BOTTLE_COUNT = 3;
    public static final int INGREDIENT_WINDOW_SLOT = 3;
    public static final int FUEL_WINDOW_SLOT = 4;
    public static final int PLAYER_WINDOW_START = 5;
    public static final int TOTAL_WINDOW_SLOTS = 41;

    public enum ClickKind {
        PICKUP,
        QUICK_MOVE
    }

    public record ClickAction(int windowSlot, int button, ClickKind kind, String reason) {
        public static ClickAction leftClick(int windowSlot, String reason) {
            return new ClickAction(windowSlot, 0, ClickKind.PICKUP, reason);
        }

        public static ClickAction rightClick(int windowSlot, String reason) {
            return new ClickAction(windowSlot, 1, ClickKind.PICKUP, reason);
        }

        public static ClickAction quickMove(int windowSlot, String reason) {
            return new ClickAction(windowSlot, 0, ClickKind.QUICK_MOVE, reason);
        }
    }

    private final List<BrewItemSnapshot> slots;
    private final BrewItemSnapshot cursor;
    private final int fuelCharges;
    private final int brewTicks;

    public BrewingStandSnapshot(List<BrewItemSnapshot> slots,
                                BrewItemSnapshot cursor,
                                int fuelCharges,
                                int brewTicks) {
        Objects.requireNonNull(slots, "slots");
        if (slots.size() < PLAYER_WINDOW_START) {
            throw new IllegalArgumentException("BrewingStandSnapshot requires at least 5 stand slots, got " + slots.size());
        }
        List<BrewItemSnapshot> copy = new ArrayList<>(slots.size());
        for (BrewItemSnapshot s : slots) {
            copy.add(s == null ? BrewItemSnapshot.EMPTY : s);
        }
        this.slots = Collections.unmodifiableList(copy);
        this.cursor = cursor == null ? BrewItemSnapshot.EMPTY : cursor;
        this.fuelCharges = Math.max(0, fuelCharges);
        this.brewTicks = Math.max(0, brewTicks);
    }

    public static BrewingStandSnapshot emptyStand(List<BrewItemSnapshot> playerSlots36, int fuelCharges) {
        List<BrewItemSnapshot> all = new ArrayList<>(TOTAL_WINDOW_SLOTS);
        for (int i = 0; i < PLAYER_WINDOW_START; i++) {
            all.add(BrewItemSnapshot.EMPTY);
        }
        for (int i = 0; i < 36; i++) {
            all.add(playerSlots36 != null && i < playerSlots36.size() && playerSlots36.get(i) != null
                    ? playerSlots36.get(i) : BrewItemSnapshot.EMPTY);
        }
        return new BrewingStandSnapshot(all, BrewItemSnapshot.EMPTY, fuelCharges, 0);
    }

    public int totalSlots() {
        return slots.size();
    }

    public BrewItemSnapshot slot(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= slots.size()) {
            return BrewItemSnapshot.EMPTY;
        }
        return slots.get(windowSlot);
    }

    public BrewItemSnapshot bottle(int index) {
        if (index < 0 || index >= BOTTLE_COUNT) {
            throw new IndexOutOfBoundsException("Bottle slot index must be 0..2, got " + index);
        }
        return slots.get(index);
    }

    public BrewItemSnapshot ingredientSlot() {
        return slots.get(INGREDIENT_WINDOW_SLOT);
    }

    public BrewItemSnapshot fuelSlot() {
        return slots.get(FUEL_WINDOW_SLOT);
    }

    public BrewItemSnapshot cursor() {
        return cursor;
    }

    public boolean cursorEmpty() {
        return cursor.isEmpty();
    }

    public int fuelCharges() {
        return fuelCharges;
    }

    public int brewTicks() {
        return brewTicks;
    }

    public boolean isBrewing() {
        return brewTicks > 0;
    }

    public int countStandBase(BrewRecipe recipe) {
        int c = 0;
        for (int i = 0; i < BOTTLE_COUNT; i++) {
            if (recipe.isExactBaseBottle(slots.get(i))) c++;
        }
        return c;
    }

    public int countStandStage1(BrewRecipe recipe) {
        int c = 0;
        for (int i = 0; i < BOTTLE_COUNT; i++) {
            if (recipe.isExactStage1Output(slots.get(i))) c++;
        }
        return c;
    }

    public int countStandFinal(BrewRecipe recipe) {
        int c = 0;
        for (int i = 0; i < BOTTLE_COUNT; i++) {
            if (recipe.isExactFinalOutput(slots.get(i))) c++;
        }
        return c;
    }

    public int countStandEmptyBottles() {
        int c = 0;
        for (int i = 0; i < BOTTLE_COUNT; i++) {
            if (slots.get(i).isEmpty()) c++;
        }
        return c;
    }

    public int firstEmptyBottleSlot() {
        for (int i = 0; i < BOTTLE_COUNT; i++) {
            if (slots.get(i).isEmpty()) return i;
        }
        return -1;
    }

    public int countPlayerBase(BrewRecipe recipe, boolean includeCursor) {
        int c = includeCursor && recipe.isExactBaseBottle(cursor) ? cursor.count() : 0;
        for (int i = PLAYER_WINDOW_START; i < slots.size(); i++) {
            if (recipe.isExactBaseBottle(slots.get(i))) c += slots.get(i).count();
        }
        return c;
    }

    public int countPlayerStage1(BrewRecipe recipe, boolean includeCursor) {
        int c = includeCursor && recipe.isExactStage1Output(cursor) ? cursor.count() : 0;
        for (int i = PLAYER_WINDOW_START; i < slots.size(); i++) {
            if (recipe.isExactStage1Output(slots.get(i))) c += slots.get(i).count();
        }
        return c;
    }

    public int countPlayerFinal(BrewRecipe recipe, boolean includeCursor) {
        int c = includeCursor && recipe.isExactFinalOutput(cursor) ? cursor.count() : 0;
        for (int i = PLAYER_WINDOW_START; i < slots.size(); i++) {
            if (recipe.isExactFinalOutput(slots.get(i))) c += slots.get(i).count();
        }
        return c;
    }

    public int countPlayerItem(String itemId, boolean includeCursor) {
        String norm = BrewItemSnapshot.normalizeIdOrEmpty(itemId);
        int c = includeCursor && cursor.isItem(norm) ? cursor.count() : 0;
        for (int i = PLAYER_WINDOW_START; i < slots.size(); i++) {
            if (slots.get(i).isItem(norm)) c += slots.get(i).count();
        }
        return c;
    }

    public int countEmptyPlayerSlots() {
        int c = 0;
        for (int i = PLAYER_WINDOW_START; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) c++;
        }
        return c;
    }

    public int findPlayerSlotMatching(java.util.function.Predicate<BrewItemSnapshot> pred) {
        for (int i = PLAYER_WINDOW_START; i < slots.size(); i++) {
            if (pred.test(slots.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Finds a player window slot that can accept the given stack (matching partial stack first, then empty). */
    public int findPlayerDepositSlot(BrewItemSnapshot stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        for (int i = PLAYER_WINDOW_START; i < slots.size(); i++) {
            BrewItemSnapshot cur = slots.get(i);
            if (!cur.isEmpty() && cur.canStackWith(stack) && cur.count() < cur.maxStackSize()) {
                return i;
            }
        }
        for (int i = PLAYER_WINDOW_START; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Returns a compact signature for detecting whether a click changed observable screen state. */
    public String stateSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append("f=").append(fuelCharges).append(",t=").append(brewTicks)
                .append(",c=").append(cursor.describe());
        for (int i = 0; i < slots.size(); i++) {
            BrewItemSnapshot s = slots.get(i);
            if (!s.isEmpty()) {
                sb.append('|').append(i).append(':').append(s.describe());
            }
        }
        return sb.toString();
    }

    /**
     * Simulates a vanilla container click on the brewing stand screen for pure-Java unit testing.
     * Enforces vanilla slot constraints (bottle slots max 1, fuel slot blaze powder only) and
     * automatically converts fuel in slot 4 into 20 fuel charges when {@code fuelCharges == 0}.
     */
    public BrewingStandSnapshot simulateClick(ClickAction action, BrewRecipe recipe) {
        if (action == null) {
            return this;
        }
        List<BrewItemSnapshot> next = new ArrayList<>(slots);
        BrewItemSnapshot cur = this.cursor;
        int w = action.windowSlot();
        if (w < 0 || w >= next.size()) {
            throw new IllegalArgumentException("Invalid windowSlot " + w);
        }
        BrewItemSnapshot target = next.get(w);

        if (action.kind() == ClickKind.QUICK_MOVE) {
            if (!target.isEmpty() && w < PLAYER_WINDOW_START) {
                int rem = target.count();
                for (int pass = 0; pass < 2 && rem > 0; pass++) {
                    for (int i = next.size() - 1; i >= PLAYER_WINDOW_START && rem > 0; i--) {
                        BrewItemSnapshot dest = next.get(i);
                        if (pass == 0) {
                            if (!dest.isEmpty() && dest.canStackWith(target) && dest.count() < dest.maxStackSize()) {
                                int add = Math.min(rem, dest.maxStackSize() - dest.count());
                                next.set(i, dest.withCount(dest.count() + add));
                                rem -= add;
                            }
                        } else if (dest.isEmpty()) {
                            int add = Math.min(rem, target.maxStackSize());
                            next.set(i, target.withCount(add));
                            rem -= add;
                        }
                    }
                }
                next.set(w, rem > 0 ? target.withCount(rem) : BrewItemSnapshot.EMPTY);
            }
        } else if (action.button() == 0) {
            // Left-click PICKUP
            if (cur.isEmpty()) {
                if (!target.isEmpty()) {
                    cur = target;
                    next.set(w, BrewItemSnapshot.EMPTY);
                }
            } else {
                if (w < BOTTLE_COUNT) {
                    if (target.isEmpty() && cur.count() == 1) {
                        next.set(w, cur);
                        cur = BrewItemSnapshot.EMPTY;
                    }
                } else if (target.isEmpty()) {
                    next.set(w, cur);
                    cur = BrewItemSnapshot.EMPTY;
                } else if (target.canStackWith(cur)) {
                    int space = Math.max(0, target.maxStackSize() - target.count());
                    int add = Math.min(cur.count(), space);
                    next.set(w, target.withCount(target.count() + add));
                    cur = cur.count() > add ? cur.withCount(cur.count() - add) : BrewItemSnapshot.EMPTY;
                } else {
                    next.set(w, cur);
                    cur = target;
                }
            }
        } else if (action.button() == 1) {
            // Right-click PICKUP (split or place 1)
            if (cur.isEmpty()) {
                if (!target.isEmpty()) {
                    int take = (target.count() + 1) / 2;
                    int leave = target.count() - take;
                    cur = target.withCount(take);
                    next.set(w, leave > 0 ? target.withCount(leave) : BrewItemSnapshot.EMPTY);
                }
            } else {
                if (w < BOTTLE_COUNT) {
                    if (target.isEmpty()) {
                        next.set(w, cur.withCount(1));
                        cur = cur.count() > 1 ? cur.withCount(cur.count() - 1) : BrewItemSnapshot.EMPTY;
                    }
                } else if (target.isEmpty()) {
                    next.set(w, cur.withCount(1));
                    cur = cur.count() > 1 ? cur.withCount(cur.count() - 1) : BrewItemSnapshot.EMPTY;
                } else if (target.canStackWith(cur) && target.count() < target.maxStackSize()) {
                    next.set(w, target.withCount(target.count() + 1));
                    cur = cur.count() > 1 ? cur.withCount(cur.count() - 1) : BrewItemSnapshot.EMPTY;
                }
            }
        }

        int nextFuel = this.fuelCharges;
        int nextBrew = this.brewTicks;
        // Vanilla serverTick auto-refuels from slot 4 when fuelCharges == 0.
        if (nextFuel <= 0 && recipe.isFuel(next.get(FUEL_WINDOW_SLOT))) {
            BrewItemSnapshot f = next.get(FUEL_WINDOW_SLOT);
            nextFuel = BrewRecipe.FUEL_USES_PER_BLAZE_POWDER;
            next.set(FUEL_WINDOW_SLOT, f.count() > 1 ? f.withCount(f.count() - 1) : BrewItemSnapshot.EMPTY);
        }
        return new BrewingStandSnapshot(next, cur, nextFuel, nextBrew);
    }

    /**
     * Simulates vanilla server brewing completion for one cycle when fuel >= 1 and a valid
     * recipe ingredient is present in slot 3.
     */
    public BrewingStandSnapshot simulateCompleteOneBrewCycle(BrewRecipe recipe) {
        List<BrewItemSnapshot> next = new ArrayList<>(slots);
        int nextFuel = this.fuelCharges;
        if (nextFuel <= 0 && recipe.isFuel(next.get(FUEL_WINDOW_SLOT))) {
            BrewItemSnapshot f = next.get(FUEL_WINDOW_SLOT);
            nextFuel = BrewRecipe.FUEL_USES_PER_BLAZE_POWDER;
            next.set(FUEL_WINDOW_SLOT, f.count() > 1 ? f.withCount(f.count() - 1) : BrewItemSnapshot.EMPTY);
        }
        BrewItemSnapshot ing = next.get(INGREDIENT_WINDOW_SLOT);
        if (nextFuel <= 0 || ing.isEmpty()) {
            return new BrewingStandSnapshot(next, cursor, nextFuel, 0);
        }

        boolean brewedAny = false;
        if (recipe.isStage1Ingredient(ing)) {
            for (int i = 0; i < BOTTLE_COUNT; i++) {
                if (recipe.isExactBaseBottle(next.get(i))) {
                    next.set(i, BrewItemSnapshot.ofPotion(recipe.bottleItemId(), recipe.stage1PotionId()));
                    brewedAny = true;
                }
            }
        } else if (recipe.isStage2Ingredient(ing)) {
            for (int i = 0; i < BOTTLE_COUNT; i++) {
                if (recipe.isExactStage1Output(next.get(i))) {
                    next.set(i, BrewItemSnapshot.ofPotion(recipe.bottleItemId(), recipe.finalPotionId()));
                    brewedAny = true;
                }
            }
        }
        if (brewedAny) {
            nextFuel = Math.max(0, nextFuel - 1);
            next.set(INGREDIENT_WINDOW_SLOT, ing.count() > 1 ? ing.withCount(ing.count() - 1) : BrewItemSnapshot.EMPTY);
        }
        return new BrewingStandSnapshot(next, cursor, nextFuel, 0);
    }
}
