package adris.altoclef.tasks.brewing;

import adris.altoclef.tasksystem.FailureReason;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acceptance-level pure-Java simulation tests covering:
 * <ol>
 *     <li>Full 2-stage brewing of 3 ordinary Potions of Healing from stocked supplies beside the stand</li>
 *     <li>Interruption right after Stage 1 (Awkward Potions complete) and clean resume without consuming Nether Wart twice</li>
 *     <li>Missing fuel -> fails cleanly without consuming unrelated items or claiming completion</li>
 *     <li>Occupied stand (unexpected item / another player's batch) -> leaves stand untouched and reports conflict</li>
 * </ol>
 */
public class BrewingEngineTest {

    private static final BrewRecipe HEALING = BrewRecipe.ORDINARY_HEALING;

    private static List<BrewItemSnapshot> emptySlots(int count) {
        List<BrewItemSnapshot> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(BrewItemSnapshot.EMPTY);
        }
        return list;
    }

    private static BrewItemSnapshot water() {
        return BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:water");
    }

    private static BrewItemSnapshot awkward() {
        return BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:awkward");
    }

    private static BrewItemSnapshot healing() {
        return BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:healing");
    }

    /**
     * Pass case: stocked chest beside stand (3 water bottles, 4 nether wart, 4 melon slices, 2 blaze powder,
     * plus decoy splash/strong healing and diamonds) -> withdraws exact items, brews 2 stages, verifies
     * 3 exact ordinary Potions of Healing and exact consumption (1 wart, 1 melon, 1 blaze powder).
     */
    @Test
    void passCaseBrewsThreeOrdinaryHealingPotionsWithExactConsumption() {
        List<BrewItemSnapshot> chestAndPlayer = emptySlots(27 + 36);
        chestAndPlayer.set(0, water());
        chestAndPlayer.set(1, water());
        chestAndPlayer.set(2, water());
        chestAndPlayer.set(3, BrewItemSnapshot.ofItem("minecraft:nether_wart", 4));
        chestAndPlayer.set(4, BrewItemSnapshot.ofItem("minecraft:glistering_melon_slice", 4));
        chestAndPlayer.set(5, BrewItemSnapshot.ofItem("minecraft:blaze_powder", 2));
        // Decoys that must stay untouched in the chest
        chestAndPlayer.set(6, BrewItemSnapshot.ofPotion("minecraft:splash_potion", "minecraft:healing"));
        chestAndPlayer.set(7, BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:strong_healing"));
        chestAndPlayer.set(8, BrewItemSnapshot.ofItem("minecraft:diamond", 5));

        SupplyContainerSnapshot chest = new SupplyContainerSnapshot(27, chestAndPlayer, BrewItemSnapshot.EMPTY);
        BrewingStandSnapshot stand = BrewingStandSnapshot.emptyStand(emptySlots(36), 0);

        // Initial inspection of empty stand -> requests exact missing supplies.
        BrewingPolicy.StandDecision d0 = BrewingPolicy.planNextStandAction(HEALING, 3, stand, false);
        Assertions.assertEquals(BrewingPolicy.DecisionKind.NEED_SUPPLIES, d0.kind());
        Assertions.assertEquals(3, d0.supplyNeeds().waterBottles());
        Assertions.assertEquals(1, d0.supplyNeeds().stage1Ingredient());
        Assertions.assertEquals(1, d0.supplyNeeds().stage2Ingredient());
        Assertions.assertEquals(1, d0.supplyNeeds().blazePowder());

        // Withdraw from stocked chest using SupplyContainerSnapshot click planner.
        int originSlot = -1;
        for (int step = 0; step < 30; step++) {
            BrewingPolicy.SupplyNeeds liveNeeds = BrewingPolicy.computeSupplyNeeds(
                    HEALING, 3, stand, chest.countPlayerSupplies(HEALING, false));
            SupplyContainerSnapshot.WithdrawalDecision wd = chest.planNextWithdrawal(HEALING, liveNeeds, originSlot);
            if (wd.status() == SupplyContainerSnapshot.WithdrawalStatus.DONE) {
                break;
            }
            Assertions.assertEquals(SupplyContainerSnapshot.WithdrawalStatus.CLICK, wd.status(), wd.detail());
            originSlot = wd.originChestSlot();
            chest = chest.simulateClick(wd.click());
        }
        Assertions.assertTrue(chest.cursorEmpty());
        // Verify chest kept exact remainders and all decoys untouched.
        SupplyContainerSnapshot.SupplyCounts remChest = chest.countContainerSupplies(HEALING);
        Assertions.assertEquals(0, remChest.exactWaterBottles());
        Assertions.assertEquals(3, remChest.stage1Ingredients());
        Assertions.assertEquals(3, remChest.stage2Ingredients());
        Assertions.assertEquals(1, remChest.blazePowder());
        Assertions.assertEquals(BrewItemSnapshot.ofItem("minecraft:diamond", 5), chest.slots().get(8));
        Assertions.assertEquals(BrewItemSnapshot.ofPotion("minecraft:splash_potion", "minecraft:healing"), chest.slots().get(6));
        Assertions.assertEquals(BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:strong_healing"), chest.slots().get(7));

        // Carry player inventory over to the brewing stand.
        List<BrewItemSnapshot> playerSlots = new ArrayList<>(chest.slots().subList(27, 27 + 36));
        stand = BrewingStandSnapshot.emptyStand(playerSlots, 0);

        int stage1Loads = 0;
        int stage2Loads = 0;
        int fuelLoads = 0;
        boolean completed = false;
        for (int step = 0; step < 40; step++) {
            BrewingPolicy.StandDecision dec = BrewingPolicy.planNextStandAction(HEALING, 3, stand, true);
            if (dec.kind() == BrewingPolicy.DecisionKind.COMPLETE) {
                completed = true;
                break;
            }
            if (dec.kind() == BrewingPolicy.DecisionKind.WAIT_BREWING) {
                // Server completes one brewing cycle; policy advances ONLY after observing the transformed bottles.
                stand = stand.simulateCompleteOneBrewCycle(HEALING);
                continue;
            }
            Assertions.assertEquals(BrewingPolicy.DecisionKind.CLICK, dec.kind(), "Unexpected decision: " + dec);
            if (!stand.cursorEmpty()) {
                if (dec.clickRole() == BrewingPolicy.ClickRole.LOAD_FUEL
                        && dec.click().windowSlot() == BrewingStandSnapshot.FUEL_WINDOW_SLOT) fuelLoads++;
                if (dec.clickRole() == BrewingPolicy.ClickRole.LOAD_STAGE1_INGREDIENT
                        && dec.click().windowSlot() == BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT) stage1Loads++;
                if (dec.clickRole() == BrewingPolicy.ClickRole.LOAD_STAGE2_INGREDIENT
                        && dec.click().windowSlot() == BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT) stage2Loads++;
            }
            stand = stand.simulateClick(dec.click(), HEALING);
        }

        Assertions.assertTrue(completed, "Expected brewing loop to reach COMPLETE");
        Assertions.assertTrue(stand.cursorEmpty());
        Assertions.assertEquals(3, stand.countPlayerFinal(HEALING, false));
        Assertions.assertEquals(0, stand.countStandBase(HEALING));
        Assertions.assertEquals(0, stand.countStandStage1(HEALING));
        Assertions.assertEquals(0, stand.countStandFinal(HEALING));
        Assertions.assertEquals(18, stand.fuelCharges()); // 20 - 2 stages
        Assertions.assertEquals(1, fuelLoads);
        Assertions.assertEquals(1, stage1Loads);
        Assertions.assertEquals(1, stage2Loads);
    }

    /**
     * Edge case 1: Interrupt right after Stage 1 completes (3 Awkward Potions sitting in stand,
     * 19 fuel charges remaining, Nether Wart still present in inventory/chest) -> resumes directly
     * at Stage 2, produces 3 exact Healing potions, and NEVER loads or consumes Nether Wart a second time.
     */
    @Test
    void resumeAfterStage1SkipsStage1AndNeverDoubleConsumesNetherWart() {
        List<BrewItemSnapshot> slots = emptySlots(BrewingStandSnapshot.TOTAL_WINDOW_SLOTS);
        slots.set(0, awkward());
        slots.set(1, awkward());
        slots.set(2, awkward());
        // Player still holds leftover Nether Wart AND a Glistering Melon Slice (plus extra Blaze Powder).
        slots.set(5, BrewItemSnapshot.ofItem("minecraft:nether_wart", 3));
        slots.set(6, BrewItemSnapshot.ofItem("minecraft:glistering_melon_slice", 2));
        slots.set(7, BrewItemSnapshot.ofItem("minecraft:blaze_powder", 2));
        BrewingStandSnapshot stand = new BrewingStandSnapshot(slots, BrewItemSnapshot.EMPTY, 19, 0);

        // Supply needs must show 0 water, 0 stage1 (nether wart), 0 stage2 (already in inv), 0 fuel.
        SupplyContainerSnapshot.SupplyCounts invCounts = new SupplyContainerSnapshot.SupplyCounts(
                0, 0, 0, 3, 2, 2);
        BrewingPolicy.SupplyNeeds needs = BrewingPolicy.computeSupplyNeeds(HEALING, 3, stand, invCounts);
        Assertions.assertTrue(needs.isSatisfied());
        Assertions.assertEquals(0, needs.waterBottles());
        Assertions.assertEquals(0, needs.stage1Ingredient());
        Assertions.assertEquals(0, needs.blazePowder());

        int stage1Loads = 0;
        int stage2Loads = 0;
        int fuelLoads = 0;
        boolean completed = false;
        for (int step = 0; step < 30; step++) {
            BrewingPolicy.StandDecision dec = BrewingPolicy.planNextStandAction(HEALING, 3, stand, false);
            if (dec.kind() == BrewingPolicy.DecisionKind.COMPLETE) {
                completed = true;
                break;
            }
            if (dec.kind() == BrewingPolicy.DecisionKind.WAIT_BREWING) {
                stand = stand.simulateCompleteOneBrewCycle(HEALING);
                continue;
            }
            Assertions.assertEquals(BrewingPolicy.DecisionKind.CLICK, dec.kind(), "Unexpected decision: " + dec);
            if (!stand.cursorEmpty()) {
                if (dec.clickRole() == BrewingPolicy.ClickRole.LOAD_FUEL
                        && dec.click().windowSlot() == BrewingStandSnapshot.FUEL_WINDOW_SLOT) fuelLoads++;
                if (dec.clickRole() == BrewingPolicy.ClickRole.LOAD_STAGE1_INGREDIENT
                        && dec.click().windowSlot() == BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT) stage1Loads++;
                if (dec.clickRole() == BrewingPolicy.ClickRole.LOAD_STAGE2_INGREDIENT
                        && dec.click().windowSlot() == BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT) stage2Loads++;
            }
            stand = stand.simulateClick(dec.click(), HEALING);
        }

        Assertions.assertTrue(completed);
        Assertions.assertEquals(3, stand.countPlayerFinal(HEALING, false));
        Assertions.assertEquals(0, stage1Loads, "Must NEVER reload Nether Wart after Stage 1 is complete");
        Assertions.assertEquals(1, stage2Loads, "Must load Glistering Melon Slice exactly once");
        Assertions.assertEquals(0, fuelLoads, "Must NOT reload Blaze Powder when 19 charges remain");
        Assertions.assertEquals(3, stand.countPlayerItem("minecraft:nether_wart", true),
                "All 3 Nether Wart in inventory must remain untouched");
        Assertions.assertEquals(1, stand.countPlayerItem("minecraft:glistering_melon_slice", true),
                "Exactly 1 of 2 Glistering Melon Slices should be consumed");
        Assertions.assertEquals(2, stand.countPlayerItem("minecraft:blaze_powder", true),
                "All 2 Blaze Powder in inventory must remain untouched");
    }

    /**
     * Edge case 2: Missing fuel -> detects missing blaze powder across stand/inventory/chest,
     * withdraws nothing, consumes nothing, reports MISSING_FUEL.
     */
    @Test
    void missingFuelFailsCleanlyWithoutConsumingItems() {
        BrewingStandSnapshot stand = BrewingStandSnapshot.emptyStand(emptySlots(36), 0);
        BrewingPolicy.StandDecision dec = BrewingPolicy.planNextStandAction(HEALING, 3, stand, false);
        Assertions.assertEquals(BrewingPolicy.DecisionKind.NEED_SUPPLIES, dec.kind());
        Assertions.assertTrue(dec.supplyNeeds().needsFuel());
        Assertions.assertEquals(1, dec.supplyNeeds().blazePowder());

        // Nearby chest has water bottles, nether wart, glistering melon slice, diamonds — NO blaze powder.
        SupplyContainerSnapshot.SupplyCounts chestCounts = new SupplyContainerSnapshot.SupplyCounts(
                3, 0, 0, 4, 4, 0);
        BrewingPolicy.SupplyNeeds deficit = BrewingPolicy.subtractAvailableFromNeeds(dec.supplyNeeds(), chestCounts);
        Assertions.assertFalse(deficit.isSatisfied());
        Assertions.assertTrue(deficit.needsFuel());
        Assertions.assertEquals(1, deficit.blazePowder());

        BrewingSession session = new BrewingSession(BrewRequest.ordinaryHealing(3));
        session.markMissingFuel(0, "missing blaze powder");
        Assertions.assertEquals(BrewingOutcome.Status.MISSING_FUEL, session.outcome().status());
        Assertions.assertEquals(FailureReason.RESOURCE_MISSING, session.outcome().failureReason());
        Assertions.assertFalse(session.outcome().isSuccess());
        Assertions.assertEquals(0, session.stage1Loads());
        Assertions.assertEquals(0, session.stage2Loads());
        Assertions.assertEquals(0, session.fuelLoads());
    }

    /**
     * Edge case 3: Occupied stand (unexpected potion, splash/strong variant, or foreign ingredient) ->
     * refuses to click any slot, leaves stand untouched, reports STAND_OCCUPIED.
     */
    @Test
    void occupiedStandDetectsConflictAndLeavesContentsUntouched() {
        // Case A: Another player's Swiftness potion + Splash Healing + Redstone.
        List<BrewItemSnapshot> slotsA = emptySlots(BrewingStandSnapshot.TOTAL_WINDOW_SLOTS);
        slotsA.set(0, BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:swiftness"));
        slotsA.set(1, BrewItemSnapshot.ofPotion("minecraft:splash_potion", "minecraft:healing"));
        slotsA.set(3, BrewItemSnapshot.ofItem("minecraft:redstone", 1));
        BrewingStandSnapshot standA = new BrewingStandSnapshot(slotsA, BrewItemSnapshot.EMPTY, 10, 0);

        Optional<String> conflictA = BrewingPolicy.inspectStandForConflict(HEALING, standA);
        Assertions.assertTrue(conflictA.isPresent());
        BrewingPolicy.StandDecision decA = BrewingPolicy.planNextStandAction(HEALING, 3, standA, false);
        Assertions.assertEquals(BrewingPolicy.DecisionKind.CONFLICT, decA.kind());
        Assertions.assertNull(decA.click());

        // Case B: Strong Healing II in stand (wrong potion tier).
        List<BrewItemSnapshot> slotsB = emptySlots(BrewingStandSnapshot.TOTAL_WINDOW_SLOTS);
        slotsB.set(0, BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:strong_healing"));
        BrewingStandSnapshot standB = new BrewingStandSnapshot(slotsB, BrewItemSnapshot.EMPTY, 5, 0);
        Assertions.assertEquals(BrewingPolicy.DecisionKind.CONFLICT,
                BrewingPolicy.planNextStandAction(HEALING, 3, standB, false).kind());

        // Case C: Mismatched stage ingredient (Glistering Melon Slice with Water Bottles).
        List<BrewItemSnapshot> slotsC = emptySlots(BrewingStandSnapshot.TOTAL_WINDOW_SLOTS);
        slotsC.set(0, water());
        slotsC.set(3, BrewItemSnapshot.ofItem("minecraft:glistering_melon_slice", 1));
        BrewingStandSnapshot standC = new BrewingStandSnapshot(slotsC, BrewItemSnapshot.EMPTY, 10, 0);
        Assertions.assertEquals(BrewingPolicy.DecisionKind.CONFLICT,
                BrewingPolicy.planNextStandAction(HEALING, 3, standC, false).kind());

        // Case D: Unclaimed stand already actively brewing (another player's live batch).
        List<BrewItemSnapshot> slotsD = emptySlots(BrewingStandSnapshot.TOTAL_WINDOW_SLOTS);
        slotsD.set(0, water());
        slotsD.set(3, BrewItemSnapshot.ofItem("minecraft:nether_wart", 1));
        BrewingStandSnapshot standD = new BrewingStandSnapshot(slotsD, BrewItemSnapshot.EMPTY, 10, 250);
        Assertions.assertEquals(BrewingPolicy.DecisionKind.CONFLICT,
                BrewingPolicy.planNextStandAction(HEALING, 3, standD, false).kind());
        // Once claimed by this session, resuming mid-brew waits cleanly instead of conflicting.
        Assertions.assertEquals(BrewingPolicy.DecisionKind.WAIT_BREWING,
                BrewingPolicy.planNextStandAction(HEALING, 3, standD, true).kind());
    }
}
