package adris.altoclef.tasks.brewing;

import java.util.Optional;

/**
 * Pure-Java brewing policy engine.
 * <p>
 * Responsibilities:
 * <ol>
 *     <li>Detect unexpected items or another player's batch in the brewing stand and refuse to touch it.</li>
 *     <li>Compute exact remaining supply needs from observed stand + player inventory contents,
 *         naturally skipping Stage 1 (water + nether wart) when Awkward Potions are already brewed.</li>
 *     <li>Plan the next single vanilla slot click or wait decision strictly from observed live components
 *         — never relying on timers or click success alone, and never double-charging ingredients.</li>
 * </ol>
 */
public final class BrewingPolicy {

    public record SupplyNeeds(int waterBottles,
                              int awkwardPotions,
                              int stage1Ingredient,
                              int stage2Ingredient,
                              int blazePowder,
                              int fuelChargesNeeded) {
        public static final SupplyNeeds NONE = new SupplyNeeds(0, 0, 0, 0, 0, 0);

        public boolean isSatisfied() {
            return waterBottles <= 0
                    && awkwardPotions <= 0
                    && stage1Ingredient <= 0
                    && stage2Ingredient <= 0
                    && blazePowder <= 0;
        }

        public boolean needsFuel() {
            return blazePowder > 0;
        }

        public String describeMissing() {
            StringBuilder sb = new StringBuilder();
            if (blazePowder > 0) appendPart(sb, "blaze_powder x" + blazePowder);
            if (waterBottles > 0) appendPart(sb, "water_bottle x" + waterBottles);
            if (awkwardPotions > 0) appendPart(sb, "awkward_potion x" + awkwardPotions);
            if (stage1Ingredient > 0) appendPart(sb, "nether_wart x" + stage1Ingredient);
            if (stage2Ingredient > 0) appendPart(sb, "glistering_melon_slice x" + stage2Ingredient);
            return sb.isEmpty() ? "none" : sb.toString();
        }

        private static void appendPart(StringBuilder sb, String part) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(part);
        }
    }

    public enum DecisionKind {
        CONFLICT,
        COMPLETE,
        CLICK,
        WAIT_BREWING,
        WAIT_FUEL_CHARGE,
        NEED_SUPPLIES,
        INVENTORY_FULL
    }

    public enum ClickRole {
        NONE,
        DEPOSIT_CURSOR,
        EXTRACT_OUTPUT,
        LOAD_BOTTLE,
        LOAD_FUEL,
        LOAD_STAGE1_INGREDIENT,
        LOAD_STAGE2_INGREDIENT
    }

    public record StandDecision(DecisionKind kind,
                                ClickRole clickRole,
                                BrewingStandSnapshot.ClickAction click,
                                SupplyNeeds supplyNeeds,
                                String detail) {
        public static StandDecision conflict(String detail) {
            return new StandDecision(DecisionKind.CONFLICT, ClickRole.NONE, null, SupplyNeeds.NONE, detail);
        }

        public static StandDecision complete(String detail) {
            return new StandDecision(DecisionKind.COMPLETE, ClickRole.NONE, null, SupplyNeeds.NONE, detail);
        }

        public static StandDecision click(ClickRole role, BrewingStandSnapshot.ClickAction action, String detail) {
            return new StandDecision(DecisionKind.CLICK, role, action, SupplyNeeds.NONE, detail);
        }

        public static StandDecision waitBrewing(String detail) {
            return new StandDecision(DecisionKind.WAIT_BREWING, ClickRole.NONE, null, SupplyNeeds.NONE, detail);
        }

        public static StandDecision waitFuelCharge(String detail) {
            return new StandDecision(DecisionKind.WAIT_FUEL_CHARGE, ClickRole.NONE, null, SupplyNeeds.NONE, detail);
        }

        public static StandDecision needSupplies(SupplyNeeds needs, String detail) {
            return new StandDecision(DecisionKind.NEED_SUPPLIES, ClickRole.NONE, null, needs, detail);
        }

        public static StandDecision inventoryFull(String detail) {
            return new StandDecision(DecisionKind.INVENTORY_FULL, ClickRole.NONE, null, SupplyNeeds.NONE, detail);
        }
    }

    private BrewingPolicy() {
    }

    /**
     * Checks whether the brewing stand currently holds any unexpected item or incompatible batch.
     * If non-empty, callers MUST leave all stand slots untouched and report the conflict.
     */
    public static Optional<String> inspectStandForConflict(BrewRecipe recipe, BrewingStandSnapshot stand) {
        BrewItemSnapshot fuel = stand.fuelSlot();
        if (!fuel.isEmpty() && !recipe.isFuel(fuel)) {
            return Optional.of("fuel slot 4 occupied by unexpected item " + fuel.describe());
        }

        int standWater = 0;
        int standAwkward = 0;
        int standFinal = 0;
        for (int i = 0; i < BrewingStandSnapshot.BOTTLE_COUNT; i++) {
            BrewItemSnapshot bottle = stand.bottle(i);
            if (bottle.isEmpty()) continue;
            int stage = recipe.bottleStage(bottle);
            if (stage < 0) {
                return Optional.of("bottle slot " + i + " occupied by unexpected item " + bottle.describe());
            }
            if (stage == 0) standWater++;
            else if (stage == 1) standAwkward++;
            else standFinal++;
        }

        BrewItemSnapshot ing = stand.ingredientSlot();
        if (!ing.isEmpty()) {
            if (recipe.isStage1Ingredient(ing)) {
                // Stage 1 ingredient is only valid when at least one base water bottle is in the stand
                // (and not a finished-only or empty stand).
                if (standWater == 0) {
                    return Optional.of("ingredient slot 3 occupied by " + ing.describe()
                            + " with no base water bottles to brew");
                }
                if (standFinal > 0) {
                    return Optional.of("ingredient slot 3 occupied by " + ing.describe()
                            + " alongside already-finished potions");
                }
            } else if (recipe.isStage2Ingredient(ing)) {
                // Stage 2 ingredient is only valid when awkward potions are present and no base water bottles remain.
                if (standAwkward == 0 || standWater > 0) {
                    return Optional.of("ingredient slot 3 occupied by " + ing.describe()
                            + " incompatible with current bottles (water=" + standWater + ", awkward=" + standAwkward + ")");
                }
            } else {
                return Optional.of("ingredient slot 3 occupied by unexpected item " + ing.describe());
            }
        } else if (stand.isBrewing()) {
            return Optional.of("brewing stand is actively brewing an unidentified batch (brewTicks=" + stand.brewTicks() + ")");
        }

        return Optional.empty();
    }

    /**
     * Computes exact missing supplies needed beyond what is currently observed in the brewing stand
     * and the player's inventory (including cursor).
     */
    public static SupplyNeeds computeSupplyNeeds(BrewRecipe recipe,
                                                 int targetCount,
                                                 BrewingStandSnapshot stand,
                                                 SupplyContainerSnapshot.SupplyCounts playerInv) {
        int healingInStand = stand.countStandFinal(recipe);
        int healingInInv = playerInv.exactHealingPotions();
        int remainingToMake = Math.max(0, targetCount - (healingInStand + healingInInv));
        if (remainingToMake == 0) {
            return SupplyNeeds.NONE;
        }

        int awkwardInStand = stand.countStandStage1(recipe);
        int awkwardInInv = playerInv.exactAwkwardPotions();
        int awkwardUsable = Math.min(remainingToMake, awkwardInStand + awkwardInInv);
        int needFromWater = Math.max(0, remainingToMake - awkwardUsable);

        int waterInStand = stand.countStandBase(recipe);
        int waterInInv = playerInv.exactWaterBottles();
        int missingWater = Math.max(0, needFromWater - (waterInStand + waterInInv));

        // Stage 1 batches needed for water -> awkward.
        int stage1Batches = needFromWater == 0 ? 0 : ceilDiv(needFromWater, BrewRecipe.MAX_BOTTLES_PER_BATCH);
        boolean stage1Loaded = recipe.isStage1Ingredient(stand.ingredientSlot()) && waterInStand > 0;
        boolean stage1Brewing = stage1Loaded && stand.isBrewing();
        int stage1NeededIng = Math.max(0, stage1Batches - (stage1Loaded ? 1 : 0));
        int missingStage1 = Math.max(0, stage1NeededIng - playerInv.stage1Ingredients());

        // Stage 2 batches needed for awkward -> healing.
        int stage2Batches = ceilDiv(remainingToMake, BrewRecipe.MAX_BOTTLES_PER_BATCH);
        boolean stage2Loaded = recipe.isStage2Ingredient(stand.ingredientSlot()) && awkwardInStand > 0;
        boolean stage2Brewing = stage2Loaded && stand.isBrewing();
        int stage2NeededIng = Math.max(0, stage2Batches - (stage2Loaded ? 1 : 0));
        int missingStage2 = Math.max(0, stage2NeededIng - playerInv.stage2Ingredients());

        // Fuel charges needed (an actively ticking brew already consumed its 1 fuel charge).
        int fuelChargesNeeded = Math.max(0, stage1Batches - (stage1Brewing ? 1 : 0))
                + Math.max(0, stage2Batches - (stage2Brewing ? 1 : 0));
        int standFuelCharges = stand.fuelCharges()
                + (recipe.isFuel(stand.fuelSlot()) ? stand.fuelSlot().count() * BrewRecipe.FUEL_USES_PER_BLAZE_POWDER : 0);
        int fuelChargeDeficit = Math.max(0, fuelChargesNeeded - standFuelCharges);
        int blazePowderNeededTotal = fuelChargeDeficit == 0 ? 0
                : ceilDiv(fuelChargeDeficit, BrewRecipe.FUEL_USES_PER_BLAZE_POWDER);
        int missingBlazePowder = Math.max(0, blazePowderNeededTotal - playerInv.blazePowder());

        return new SupplyNeeds(missingWater, 0, missingStage1, missingStage2, missingBlazePowder, fuelChargesNeeded);
    }

    /**
     * Checks whether adding supplies available across known nearby containers can satisfy
     * {@code needs} (including exact awkward potions substituting for water + stage1 if present).
     */
    public static SupplyNeeds subtractAvailableFromNeeds(SupplyNeeds needs,
                                                         SupplyContainerSnapshot.SupplyCounts availableInChests) {
        if (needs.isSatisfied()) {
            return SupplyNeeds.NONE;
        }
        int remFuel = Math.max(0, needs.blazePowder() - availableInChests.blazePowder());
        int remWater = Math.max(0, needs.waterBottles() - availableInChests.exactWaterBottles());
        int remAwkward = Math.max(0, needs.awkwardPotions() - availableInChests.exactAwkwardPotions());
        int remStage1 = Math.max(0, needs.stage1Ingredient() - availableInChests.stage1Ingredients());
        int remStage2 = Math.max(0, needs.stage2Ingredient() - availableInChests.stage2Ingredients());
        return new SupplyNeeds(remWater, remAwkward, remStage1, remStage2, remFuel, needs.fuelChargesNeeded());
    }

    /**
     * Plans the next single action on an open Brewing Stand screen strictly from live observed state.
     */
    public static StandDecision planNextStandAction(BrewRecipe recipe,
                                                    int targetCount,
                                                    BrewingStandSnapshot stand,
                                                    boolean standClaimed) {
        // Step 0: Conflict check FIRST — never touch an occupied/foreign stand!
        Optional<String> conflict = inspectStandForConflict(recipe, stand);
        if (conflict.isPresent()) {
            return StandDecision.conflict(conflict.get());
        }
        if (!standClaimed && stand.isBrewing()) {
            return StandDecision.conflict("another batch is already brewing in this stand (brewTicks="
                    + stand.brewTicks() + ", ingredient=" + stand.ingredientSlot().describe() + ")");
        }

        int healingInInvNoCursor = stand.countPlayerFinal(recipe, false);
        int healingInStand = stand.countStandFinal(recipe);
        int standWater = stand.countStandBase(recipe);
        int standAwkward = stand.countStandStage1(recipe);
        int standEmpty = stand.countStandEmptyBottles();
        int stillNeedToDeliver = Math.max(0, targetCount - healingInInvNoCursor);

        // Step 1: Handle any item currently held on cursor safely.
        if (!stand.cursorEmpty()) {
            BrewItemSnapshot cur = stand.cursor();
            // Place 1 fuel if stand has 0 fuel & empty fuel slot and we picked up blaze powder.
            if (recipe.isFuel(cur) && stand.fuelCharges() <= 0 && stand.fuelSlot().isEmpty()) {
                BrewingStandSnapshot.ClickAction act = cur.count() > 1
                        ? BrewingStandSnapshot.ClickAction.rightClick(BrewingStandSnapshot.FUEL_WINDOW_SLOT, "place 1 blaze powder fuel")
                        : BrewingStandSnapshot.ClickAction.leftClick(BrewingStandSnapshot.FUEL_WINDOW_SLOT, "place blaze powder fuel");
                return StandDecision.click(ClickRole.LOAD_FUEL, act, "placing 1 blaze powder into fuel slot");
            }
            // Place 1 bottle if cursor holds a needed bottle for an empty bottle slot and not brewing.
            int emptyBottleIdx = stand.firstEmptyBottleSlot();
            if (!stand.isBrewing() && stand.ingredientSlot().isEmpty() && emptyBottleIdx >= 0 && stillNeedToDeliver > healingInStand) {
                int batchTarget = Math.min(BrewRecipe.MAX_BOTTLES_PER_BATCH, Math.max(0, stillNeedToDeliver - healingInStand));
                int activeBottles = standWater + standAwkward;
                if (activeBottles < batchTarget) {
                    if (recipe.isExactStage1Output(cur) && standWater == 0) {
                        return StandDecision.click(ClickRole.LOAD_BOTTLE,
                                BrewingStandSnapshot.ClickAction.leftClick(emptyBottleIdx, "place awkward potion into bottle slot " + emptyBottleIdx),
                                "loading awkward potion into slot " + emptyBottleIdx);
                    }
                    if (recipe.isExactBaseBottle(cur)) {
                        return StandDecision.click(ClickRole.LOAD_BOTTLE,
                                BrewingStandSnapshot.ClickAction.leftClick(emptyBottleIdx, "place water bottle into bottle slot " + emptyBottleIdx),
                                "loading water bottle into slot " + emptyBottleIdx);
                    }
                }
            }
            // Place 1 stage-1 ingredient ONLY when water bottles are ready, fuel is charged, slot 3 empty, not brewing.
            if (recipe.isStage1Ingredient(cur)
                    && standWater > 0
                    && healingInStand == 0
                    && stand.fuelCharges() >= 1
                    && stand.ingredientSlot().isEmpty()
                    && !stand.isBrewing()) {
                BrewingStandSnapshot.ClickAction act = cur.count() > 1
                        ? BrewingStandSnapshot.ClickAction.rightClick(BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT, "place 1 nether wart")
                        : BrewingStandSnapshot.ClickAction.leftClick(BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT, "place nether wart");
                return StandDecision.click(ClickRole.LOAD_STAGE1_INGREDIENT, act, "loading 1 nether wart into ingredient slot");
            }
            // Place 1 stage-2 ingredient ONLY when awkward bottles are observed, NO water bottles remain, fuel charged, slot 3 empty.
            if (recipe.isStage2Ingredient(cur)
                    && standAwkward > 0
                    && standWater == 0
                    && healingInStand == 0
                    && stand.fuelCharges() >= 1
                    && stand.ingredientSlot().isEmpty()
                    && !stand.isBrewing()) {
                BrewingStandSnapshot.ClickAction act = cur.count() > 1
                        ? BrewingStandSnapshot.ClickAction.rightClick(BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT, "place 1 glistering melon slice")
                        : BrewingStandSnapshot.ClickAction.leftClick(BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT, "place glistering melon slice");
                return StandDecision.click(ClickRole.LOAD_STAGE2_INGREDIENT, act, "loading 1 glistering melon slice into ingredient slot");
            }
            // Otherwise return cursor stack to player inventory (e.g. leftover stack after placing 1 item).
            int depositSlot = stand.findPlayerDepositSlot(cur);
            if (depositSlot < 0) {
                return StandDecision.inventoryFull("player inventory full while clearing cursor " + cur.describe());
            }
            return StandDecision.click(ClickRole.DEPOSIT_CURSOR,
                    BrewingStandSnapshot.ClickAction.leftClick(depositSlot, "return cursor " + cur.describe() + " to inventory"),
                    "returning cursor stack " + cur.describe() + " to inventory");
        }

        // Step 2: Goal already verified in player inventory with empty cursor!
        if (healingInInvNoCursor >= targetCount) {
            return StandDecision.complete("verified " + healingInInvNoCursor + "/" + targetCount + " " + recipe.displayName());
        }

        // Step 3: If stand is actively brewing our valid stage, wait for actual observed transformation.
        if (stand.isBrewing()) {
            return StandDecision.waitBrewing("brewing in progress (ticks=" + stand.brewTicks()
                    + ", ingredient=" + stand.ingredientSlot().describe() + ")");
        }

        // Step 4: Extract any verified exact final potions from bottle slots into player inventory.
        if (healingInStand > 0) {
            for (int i = 0; i < BrewingStandSnapshot.BOTTLE_COUNT; i++) {
                if (recipe.isExactFinalOutput(stand.bottle(i))) {
                    if (stand.countEmptyPlayerSlots() <= 0) {
                        return StandDecision.inventoryFull("no empty inventory slot to extract " + recipe.displayName());
                    }
                    return StandDecision.click(ClickRole.EXTRACT_OUTPUT,
                            BrewingStandSnapshot.ClickAction.quickMove(i, "extract verified " + recipe.displayName() + " from slot " + i),
                            "extracting verified " + recipe.displayName() + " from slot " + i);
                }
            }
        }

        // Step 5: Compute current player inventory counts and exact remaining supply needs.
        SupplyContainerSnapshot.SupplyCounts invCounts = new SupplyContainerSnapshot.SupplyCounts(
                stand.countPlayerBase(recipe, false),
                stand.countPlayerStage1(recipe, false),
                healingInInvNoCursor,
                stand.countPlayerItem(recipe.stage1IngredientId(), false),
                stand.countPlayerItem(recipe.stage2IngredientId(), false),
                stand.countPlayerItem(recipe.fuelItemId(), false)
        );
        SupplyNeeds needs = computeSupplyNeeds(recipe, targetCount, stand, invCounts);

        // Step 6: If a valid recipe ingredient is ALREADY loaded in slot 3, make sure stand has fuel and wait for brewing!
        if (!stand.ingredientSlot().isEmpty()) {
            if (stand.fuelCharges() <= 0) {
                if (recipe.isFuel(stand.fuelSlot())) {
                    return StandDecision.waitFuelCharge("waiting for fuel slot blaze powder to charge stand");
                }
                if (invCounts.blazePowder() > 0) {
                    int fuelInvSlot = stand.findPlayerSlotMatching(recipe::isFuel);
                    return StandDecision.click(ClickRole.LOAD_FUEL,
                            BrewingStandSnapshot.ClickAction.leftClick(fuelInvSlot, "pick up blaze powder for fuel"),
                            "picking up blaze powder to fuel stand");
                }
                return StandDecision.needSupplies(needs, "need blaze powder fuel for loaded ingredient");
            }
            return StandDecision.waitBrewing("ingredient " + stand.ingredientSlot().describe() + " loaded; waiting for brew start/completion");
        }

        // Step 7: Before loading any fuel or ingredient, ensure we have all supplies for at least the current batch
        // (and fuel!) — if any supply is missing from stand+playerInv, fetch from nearby supply containers FIRST!
        // This guarantees missing-fuel fails cleanly without stranding bottles or ingredients in the stand.
        if (!needs.isSatisfied()) {
            return StandDecision.needSupplies(needs, "missing supplies: " + needs.describeMissing());
        }

        // Step 8: All required supplies for the remaining potions are in stand + player inventory.
        // Determine batch size up to 3 bottles.
        int remainingToBrew = Math.max(0, targetCount - healingInInvNoCursor);
        int desiredBatch = Math.min(BrewRecipe.MAX_BOTTLES_PER_BATCH, remainingToBrew);
        int currentStandBottles = standWater + standAwkward;

        // 8a. Load bottles FIRST before loading fuel or ingredient so all 3 bottles brew together in 1 cycle.
        if (currentStandBottles < desiredBatch && standEmpty > 0) {
            // Prefer loading Awkward Potions if stand has no water bottles (skips stage 1).
            if (standWater == 0 && invCounts.exactAwkwardPotions() > 0) {
                int slotIdx = stand.findPlayerSlotMatching(recipe::isExactStage1Output);
                return StandDecision.click(ClickRole.LOAD_BOTTLE,
                        BrewingStandSnapshot.ClickAction.leftClick(slotIdx, "pick up awkward potion"),
                        "picking up awkward potion to load into stand");
            }
            if (invCounts.exactWaterBottles() > 0) {
                int slotIdx = stand.findPlayerSlotMatching(recipe::isExactBaseBottle);
                return StandDecision.click(ClickRole.LOAD_BOTTLE,
                        BrewingStandSnapshot.ClickAction.leftClick(slotIdx, "pick up water bottle"),
                        "picking up water bottle to load into stand");
            }
        }

        // 8b. Ensure stand has observed fuel charges >= 1 before placing any ingredient.
        if (stand.fuelCharges() <= 0) {
            if (recipe.isFuel(stand.fuelSlot())) {
                return StandDecision.waitFuelCharge("waiting for blaze powder to convert into stand fuel charges");
            }
            int fuelInvSlot = stand.findPlayerSlotMatching(recipe::isFuel);
            if (fuelInvSlot < 0) {
                return StandDecision.needSupplies(needs, "missing blaze powder fuel");
            }
            return StandDecision.click(ClickRole.LOAD_FUEL,
                    BrewingStandSnapshot.ClickAction.leftClick(fuelInvSlot, "pick up blaze powder for stand fuel"),
                    "picking up blaze powder to charge stand");
        }

        // 8c. Load the stage-appropriate ingredient ONLY after observing the bottles and charged fuel.
        if (standWater > 0) {
            int s1Slot = stand.findPlayerSlotMatching(recipe::isStage1Ingredient);
            if (s1Slot < 0) {
                return StandDecision.needSupplies(needs, "missing " + recipe.stage1IngredientId());
            }
            return StandDecision.click(ClickRole.LOAD_STAGE1_INGREDIENT,
                    BrewingStandSnapshot.ClickAction.leftClick(s1Slot, "pick up nether wart for stage 1"),
                    "picking up nether wart for stage 1 (" + standWater + " water bottle(s))");
        }
        if (standAwkward > 0) {
            int s2Slot = stand.findPlayerSlotMatching(recipe::isStage2Ingredient);
            if (s2Slot < 0) {
                return StandDecision.needSupplies(needs, "missing " + recipe.stage2IngredientId());
            }
            return StandDecision.click(ClickRole.LOAD_STAGE2_INGREDIENT,
                    BrewingStandSnapshot.ClickAction.leftClick(s2Slot, "pick up glistering melon slice for stage 2"),
                    "picking up glistering melon slice for stage 2 (" + standAwkward + " awkward potion(s))");
        }

        return StandDecision.needSupplies(needs, "stand empty and no loadable bottles in inventory");
    }

    private static int ceilDiv(int num, int den) {
        return (num + den - 1) / den;
    }
}
