package adris.altoclef.tasks.brewing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure-Java immutable snapshot of an open nearby supply container (chest, barrel, shulker box)
 * with potion-component-aware counting and exact-count withdrawal click planning.
 */
public final class SupplyContainerSnapshot {

    public record SupplyCounts(int exactWaterBottles,
                               int exactAwkwardPotions,
                               int exactHealingPotions,
                               int stage1Ingredients,
                               int stage2Ingredients,
                               int blazePowder) {
        public static final SupplyCounts ZERO = new SupplyCounts(0, 0, 0, 0, 0, 0);

        public SupplyCounts plus(SupplyCounts other) {
            if (other == null) return this;
            return new SupplyCounts(
                    exactWaterBottles + other.exactWaterBottles,
                    exactAwkwardPotions + other.exactAwkwardPotions,
                    exactHealingPotions + other.exactHealingPotions,
                    stage1Ingredients + other.stage1Ingredients,
                    stage2Ingredients + other.stage2Ingredients,
                    blazePowder + other.blazePowder
            );
        }
    }

    public enum WithdrawalStatus {
        DONE,
        CLICK,
        NOTHING_TO_TAKE,
        INVENTORY_FULL
    }

    public record WithdrawalDecision(WithdrawalStatus status,
                                     BrewingStandSnapshot.ClickAction click,
                                     int originChestSlot,
                                     String detail) {
    }

    private final int containerSlotCount;
    private final List<BrewItemSnapshot> slots;
    private final BrewItemSnapshot cursor;

    public SupplyContainerSnapshot(int containerSlotCount,
                                   List<BrewItemSnapshot> slots,
                                   BrewItemSnapshot cursor) {
        if (containerSlotCount < 0) {
            throw new IllegalArgumentException("containerSlotCount must be >= 0");
        }
        Objects.requireNonNull(slots, "slots");
        this.containerSlotCount = containerSlotCount;
        List<BrewItemSnapshot> copy = new ArrayList<>(slots.size());
        for (BrewItemSnapshot s : slots) {
            copy.add(s == null ? BrewItemSnapshot.EMPTY : s);
        }
        this.slots = Collections.unmodifiableList(copy);
        this.cursor = cursor == null ? BrewItemSnapshot.EMPTY : cursor;
    }

    public int containerSlotCount() {
        return containerSlotCount;
    }

    public List<BrewItemSnapshot> slots() {
        return slots;
    }

    public BrewItemSnapshot cursor() {
        return cursor;
    }

    public boolean cursorEmpty() {
        return cursor.isEmpty();
    }

    public SupplyCounts countContainerSupplies(BrewRecipe recipe) {
        int water = 0, awkward = 0, healing = 0, s1 = 0, s2 = 0, fuel = 0;
        int limit = Math.min(containerSlotCount, slots.size());
        for (int i = 0; i < limit; i++) {
            BrewItemSnapshot s = slots.get(i);
            if (s.isEmpty()) continue;
            if (recipe.isExactBaseBottle(s)) water += s.count();
            else if (recipe.isExactStage1Output(s)) awkward += s.count();
            else if (recipe.isExactFinalOutput(s)) healing += s.count();
            else if (recipe.isStage1Ingredient(s)) s1 += s.count();
            else if (recipe.isStage2Ingredient(s)) s2 += s.count();
            else if (recipe.isFuel(s)) fuel += s.count();
        }
        return new SupplyCounts(water, awkward, healing, s1, s2, fuel);
    }

    public SupplyCounts countPlayerSupplies(BrewRecipe recipe, boolean includeCursor) {
        int water = 0, awkward = 0, healing = 0, s1 = 0, s2 = 0, fuel = 0;
        for (int i = containerSlotCount; i < slots.size(); i++) {
            BrewItemSnapshot s = slots.get(i);
            if (s.isEmpty()) continue;
            if (recipe.isExactBaseBottle(s)) water += s.count();
            else if (recipe.isExactStage1Output(s)) awkward += s.count();
            else if (recipe.isExactFinalOutput(s)) healing += s.count();
            else if (recipe.isStage1Ingredient(s)) s1 += s.count();
            else if (recipe.isStage2Ingredient(s)) s2 += s.count();
            else if (recipe.isFuel(s)) fuel += s.count();
        }
        if (includeCursor && !cursor.isEmpty()) {
            if (recipe.isExactBaseBottle(cursor)) water += cursor.count();
            else if (recipe.isExactStage1Output(cursor)) awkward += cursor.count();
            else if (recipe.isExactFinalOutput(cursor)) healing += cursor.count();
            else if (recipe.isStage1Ingredient(cursor)) s1 += cursor.count();
            else if (recipe.isStage2Ingredient(cursor)) s2 += cursor.count();
            else if (recipe.isFuel(cursor)) fuel += cursor.count();
        }
        return new SupplyCounts(water, awkward, healing, s1, s2, fuel);
    }

    public String stateSignature() {
        StringBuilder sb = new StringBuilder("c=").append(cursor.describe());
        for (int i = 0; i < slots.size(); i++) {
            BrewItemSnapshot s = slots.get(i);
            if (!s.isEmpty()) {
                sb.append('|').append(i).append(':').append(s.describe());
            }
        }
        return sb.toString();
    }

    /**
     * Plans the next single vanilla click to withdraw ONLY the exact missing supply items
     * from this open container into the player's inventory without touching any unrelated items.
     */
    public WithdrawalDecision planNextWithdrawal(BrewRecipe recipe,
                                                 BrewingPolicy.SupplyNeeds remainingNeeds,
                                                 int lastOriginChestSlot) {
        // 1. If cursor is holding an item, either place 1 into player (if still needed) or return remainder to container.
        if (!cursor.isEmpty()) {
            int needForCursor = remainingNeedForStack(recipe, remainingNeeds, cursor);
            if (needForCursor > 0) {
                int playerSlot = findPlayerDepositSlot(cursor);
                if (playerSlot < 0) {
                    // Inventory full: return cursor back to container so nothing is stranded on cursor.
                    int backSlot = findContainerDepositSlot(cursor, lastOriginChestSlot);
                    if (backSlot >= 0) {
                        return new WithdrawalDecision(WithdrawalStatus.CLICK,
                                BrewingStandSnapshot.ClickAction.leftClick(backSlot, "return cursor to chest (inventory full)"),
                                -1, "returning cursor to container because inventory is full");
                    }
                    return new WithdrawalDecision(WithdrawalStatus.INVENTORY_FULL, null, -1,
                            "player inventory full while withdrawing " + cursor.describe());
                }
                if (cursor.count() <= needForCursor) {
                    return new WithdrawalDecision(WithdrawalStatus.CLICK,
                            BrewingStandSnapshot.ClickAction.leftClick(playerSlot, "deposit needed stack " + cursor.describe()),
                            lastOriginChestSlot, "depositing needed " + cursor.describe());
                }
                return new WithdrawalDecision(WithdrawalStatus.CLICK,
                        BrewingStandSnapshot.ClickAction.rightClick(playerSlot, "place 1 of " + cursor.describe() + " into player"),
                        lastOriginChestSlot, "placing 1 of " + cursor.describe() + " into player inventory");
            } else {
                // Return surplus cursor stack back into container (preferring its origin slot).
                int backSlot = findContainerDepositSlot(cursor, lastOriginChestSlot);
                if (backSlot >= 0) {
                    return new WithdrawalDecision(WithdrawalStatus.CLICK,
                            BrewingStandSnapshot.ClickAction.leftClick(backSlot, "return surplus " + cursor.describe() + " to chest"),
                            -1, "returning surplus " + cursor.describe() + " to supply container");
                }
                int playerSlot = findPlayerDepositSlot(cursor);
                if (playerSlot >= 0) {
                    return new WithdrawalDecision(WithdrawalStatus.CLICK,
                            BrewingStandSnapshot.ClickAction.leftClick(playerSlot, "deposit leftover cursor into player"),
                            -1, "depositing leftover cursor into player");
                }
                return new WithdrawalDecision(WithdrawalStatus.INVENTORY_FULL, null, -1,
                        "no slot to clear cursor " + cursor.describe());
            }
        }

        if (remainingNeeds.isSatisfied()) {
            return new WithdrawalDecision(WithdrawalStatus.DONE, null, -1, "all needed supplies collected");
        }

        // 2. Priority order for withdrawal: fuel -> awkward potions -> water bottles -> stage1 -> stage2.
        // Fuel first ensures we never pull bottles if fuel is somehow gone.
        WithdrawalDecision d;
        if (remainingNeeds.blazePowder() > 0) {
            d = pickFromContainer(s -> recipe.isFuel(s), remainingNeeds.blazePowder(), "blaze powder");
            if (d != null) return d;
        }
        if (remainingNeeds.awkwardPotions() > 0) {
            d = pickFromContainer(s -> recipe.isExactStage1Output(s), remainingNeeds.awkwardPotions(), "awkward potion");
            if (d != null) return d;
        }
        if (remainingNeeds.waterBottles() > 0) {
            d = pickFromContainer(s -> recipe.isExactBaseBottle(s), remainingNeeds.waterBottles(), "water bottle");
            if (d != null) return d;
        }
        if (remainingNeeds.stage1Ingredient() > 0) {
            d = pickFromContainer(s -> recipe.isStage1Ingredient(s), remainingNeeds.stage1Ingredient(), "stage1 ingredient");
            if (d != null) return d;
        }
        if (remainingNeeds.stage2Ingredient() > 0) {
            d = pickFromContainer(s -> recipe.isStage2Ingredient(s), remainingNeeds.stage2Ingredient(), "stage2 ingredient");
            if (d != null) return d;
        }

        return new WithdrawalDecision(WithdrawalStatus.NOTHING_TO_TAKE, null, -1,
                "no further needed supplies in this container");
    }

    private WithdrawalDecision pickFromContainer(java.util.function.Predicate<BrewItemSnapshot> pred,
                                                 int needCount,
                                                 String label) {
        int limit = Math.min(containerSlotCount, slots.size());
        for (int i = 0; i < limit; i++) {
            BrewItemSnapshot s = slots.get(i);
            if (!pred.test(s)) continue;
            if (findPlayerDepositSlot(s) < 0) {
                return new WithdrawalDecision(WithdrawalStatus.INVENTORY_FULL, null, -1,
                        "player inventory full before withdrawing " + label);
            }
            if (s.count() <= needCount) {
                return new WithdrawalDecision(WithdrawalStatus.CLICK,
                        BrewingStandSnapshot.ClickAction.quickMove(i, "quick-move " + label + " (" + s.describe() + ")"),
                        i, "quick-moving " + s.describe());
            }
            // Stack has more than we need: pick up with left-click, then right-click exact count into player and return rest.
            return new WithdrawalDecision(WithdrawalStatus.CLICK,
                    BrewingStandSnapshot.ClickAction.leftClick(i, "pick up stack to split " + label),
                    i, "picking up " + s.describe() + " to take exactly " + needCount);
        }
        return null;
    }

    private int remainingNeedForStack(BrewRecipe recipe, BrewingPolicy.SupplyNeeds needs, BrewItemSnapshot stack) {
        if (recipe.isFuel(stack)) return needs.blazePowder();
        if (recipe.isExactStage1Output(stack)) return needs.awkwardPotions();
        if (recipe.isExactBaseBottle(stack)) return needs.waterBottles();
        if (recipe.isStage1Ingredient(stack)) return needs.stage1Ingredient();
        if (recipe.isStage2Ingredient(stack)) return needs.stage2Ingredient();
        return 0;
    }

    private int findPlayerDepositSlot(BrewItemSnapshot stack) {
        if (stack == null || stack.isEmpty()) return -1;
        for (int i = containerSlotCount; i < slots.size(); i++) {
            BrewItemSnapshot cur = slots.get(i);
            if (!cur.isEmpty() && cur.canStackWith(stack) && cur.count() < cur.maxStackSize()) {
                return i;
            }
        }
        for (int i = containerSlotCount; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int findContainerDepositSlot(BrewItemSnapshot stack, int preferredSlot) {
        int limit = Math.min(containerSlotCount, slots.size());
        if (preferredSlot >= 0 && preferredSlot < limit) {
            BrewItemSnapshot pref = slots.get(preferredSlot);
            if (pref.isEmpty() || (pref.canStackWith(stack) && pref.count() < pref.maxStackSize())) {
                return preferredSlot;
            }
        }
        for (int i = 0; i < limit; i++) {
            BrewItemSnapshot cur = slots.get(i);
            if (!cur.isEmpty() && cur.canStackWith(stack) && cur.count() < cur.maxStackSize()) {
                return i;
            }
        }
        for (int i = 0; i < limit; i++) {
            if (slots.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Vanilla click simulation on a supply container screen for unit testing. */
    public SupplyContainerSnapshot simulateClick(BrewingStandSnapshot.ClickAction action) {
        if (action == null) return this;
        List<BrewItemSnapshot> next = new ArrayList<>(slots);
        BrewItemSnapshot cur = this.cursor;
        int w = action.windowSlot();
        if (w < 0 || w >= next.size()) {
            throw new IllegalArgumentException("Invalid windowSlot " + w);
        }
        BrewItemSnapshot target = next.get(w);
        if (action.kind() == BrewingStandSnapshot.ClickKind.QUICK_MOVE) {
            if (!target.isEmpty() && w < containerSlotCount) {
                int rem = target.count();
                for (int pass = 0; pass < 2 && rem > 0; pass++) {
                    for (int i = next.size() - 1; i >= containerSlotCount && rem > 0; i--) {
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
            if (cur.isEmpty()) {
                if (!target.isEmpty()) {
                    cur = target;
                    next.set(w, BrewItemSnapshot.EMPTY);
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
        } else if (action.button() == 1) {
            if (cur.isEmpty()) {
                if (!target.isEmpty()) {
                    int take = (target.count() + 1) / 2;
                    cur = target.withCount(take);
                    next.set(w, target.count() - take > 0 ? target.withCount(target.count() - take) : BrewItemSnapshot.EMPTY);
                }
            } else if (target.isEmpty()) {
                next.set(w, cur.withCount(1));
                cur = cur.count() > 1 ? cur.withCount(cur.count() - 1) : BrewItemSnapshot.EMPTY;
            } else if (target.canStackWith(cur) && target.count() < target.maxStackSize()) {
                next.set(w, target.withCount(target.count() + 1));
                cur = cur.count() > 1 ? cur.withCount(cur.count() - 1) : BrewItemSnapshot.EMPTY;
            }
        }
        return new SupplyContainerSnapshot(containerSlotCount, next, cur);
    }
}
