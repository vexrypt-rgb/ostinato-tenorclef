package adris.altoclef.tasks.trading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Stateless decision logic for evaluating villager offers and rechecking an offer immediately before committing.
 * Pure Java (no Minecraft runtime dependency).
 */
public final class VillagerTradePolicy {

    private VillagerTradePolicy() {
    }

    public static final class OfferEvaluation {
        public enum Decision {
            SELECT_OFFER,
            OVER_BUDGET,
            SOLD_OUT,
            OFFER_UNAVAILABLE,
            MISSING_INPUTS,
            INVENTORY_FULL
        }

        private final Decision decision;
        private final TradeOfferSnapshot selectedOffer;
        private final int cheapestSeenEmeraldCost;
        private final String detail;

        public OfferEvaluation(Decision decision,
                               TradeOfferSnapshot selectedOffer,
                               int cheapestSeenEmeraldCost,
                               String detail) {
            this.decision = decision;
            this.selectedOffer = selectedOffer;
            this.cheapestSeenEmeraldCost = cheapestSeenEmeraldCost;
            this.detail = detail == null ? "" : detail;
        }

        public Decision decision() {
            return decision;
        }

        public TradeOfferSnapshot selectedOffer() {
            return selectedOffer;
        }

        public int cheapestSeenEmeraldCost() {
            return cheapestSeenEmeraldCost;
        }

        public String detail() {
            return detail;
        }
    }

    public static final class PreCommitCheck {
        public enum Decision {
            COMMIT,
            ALREADY_COMPLETE,
            WAIT_SYNC,
            RE_EVALUATE,
            OVER_BUDGET,
            SOLD_OUT,
            INVENTORY_FULL
        }

        private final Decision decision;
        private final String detail;

        public PreCommitCheck(Decision decision, String detail) {
            this.decision = decision;
            this.detail = detail == null ? "" : detail;
        }

        public Decision decision() {
            return decision;
        }

        public String detail() {
            return detail;
        }
    }

    /**
     * Inspects actual merchant offers and chooses the cheapest valid, in-stock, within-budget,
     * affordable offer matching the exact target item and components.
     */
    public static OfferEvaluation evaluateOffers(VillagerTradeRequest request,
                                                 List<TradeOfferSnapshot> offers,
                                                 TradeInventorySnapshot inventory,
                                                 int emeraldsSpentSoFar) {
        if (offers == null || offers.isEmpty()) {
            return new OfferEvaluation(
                    OfferEvaluation.Decision.OFFER_UNAVAILABLE,
                    null,
                    -1,
                    "offer-unavailable: merchant has no offers matching " + request.describeTarget());
        }

        List<TradeOfferSnapshot> matching = new ArrayList<>();
        for (TradeOfferSnapshot offer : offers) {
            if (offer == null) continue;
            if (matchesTargetOutput(request, offer.outputItemId(), offer.outputCount(), offer.outputEnchantments())) {
                matching.add(offer);
            }
        }
        if (matching.isEmpty()) {
            return new OfferEvaluation(
                    OfferEvaluation.Decision.OFFER_UNAVAILABLE,
                    null,
                    -1,
                    "offer-unavailable: merchant has no offer matching " + request.describeTarget());
        }

        List<TradeOfferSnapshot> inStock = new ArrayList<>();
        for (TradeOfferSnapshot offer : matching) {
            if (offer.hasRemainingUses()) {
                inStock.add(offer);
            }
        }
        if (inStock.isEmpty()) {
            int cheapestSoldOut = matching.stream().mapToInt(TradeOfferSnapshot::emeraldCost).min().orElse(-1);
            return new OfferEvaluation(
                    OfferEvaluation.Decision.SOLD_OUT,
                    null,
                    cheapestSoldOut,
                    "sold-out: " + matching.size() + " matching offer(s) for " + request.describeTarget()
                            + " have 0 uses remaining");
        }

        inStock.sort(Comparator.comparingInt(TradeOfferSnapshot::emeraldCost)
                .thenComparingInt(TradeOfferSnapshot::offerIndex));
        int cheapestInStock = inStock.get(0).emeraldCost();

        List<TradeOfferSnapshot> withinBudget = new ArrayList<>();
        int remainingBudget = request.hasEmeraldCap()
                ? Math.max(0, request.maxEmeralds() - Math.max(0, emeraldsSpentSoFar))
                : Integer.MAX_VALUE;
        for (TradeOfferSnapshot offer : inStock) {
            if (!request.hasEmeraldCap() || offer.emeraldCost() <= remainingBudget) {
                withinBudget.add(offer);
            }
        }
        if (withinBudget.isEmpty()) {
            return new OfferEvaluation(
                    OfferEvaluation.Decision.OVER_BUDGET,
                    null,
                    cheapestInStock,
                    "over-budget: cheapest matching offer costs " + cheapestInStock
                            + " emeralds (cap=" + request.maxEmeralds()
                            + ", spent=" + emeraldsSpentSoFar
                            + ", remaining=" + remainingBudget + ")");
        }

        String firstMissingReason = null;
        for (TradeOfferSnapshot offer : withinBudget) {
            String missing = validateRequiredInputs(offer, inventory);
            if (missing == null) {
                if (!inventory.hasInventorySpaceForOutput()) {
                    return new OfferEvaluation(
                            OfferEvaluation.Decision.INVENTORY_FULL,
                            null,
                            offer.emeraldCost(),
                            "inventory-full: no free inventory slot for " + request.describeTarget());
                }
                return new OfferEvaluation(
                        OfferEvaluation.Decision.SELECT_OFFER,
                        offer,
                        offer.emeraldCost(),
                        "selected " + offer);
            }
            if (firstMissingReason == null) {
                firstMissingReason = missing;
            }
        }

        return new OfferEvaluation(
                OfferEvaluation.Decision.MISSING_INPUTS,
                null,
                withinBudget.get(0).emeraldCost(),
                "missing-inputs: " + firstMissingReason);
    }

    /**
     * Rechecks the live offer, displayed output slot, input slots, and remaining emerald budget
     * immediately before committing a single trade.
     */
    public static PreCommitCheck recheckBeforeCommit(VillagerTradeRequest request,
                                                     TradeOfferSnapshot plannedOffer,
                                                     TradeOfferSnapshot liveOfferAtIndex,
                                                     String displayedOutputItemId,
                                                     int displayedOutputCount,
                                                     Map<String, Integer> displayedOutputEnchants,
                                                     boolean paymentSlotsSatisfyOffer,
                                                     TradeInventorySnapshot inventory,
                                                     int targetTotalCount,
                                                     int emeraldsSpentSoFar) {
        if (inventory.matchingTargetInInventory() >= targetTotalCount) {
            return new PreCommitCheck(PreCommitCheck.Decision.ALREADY_COMPLETE,
                    "target already met in inventory before commit");
        }
        if (liveOfferAtIndex == null) {
            return new PreCommitCheck(PreCommitCheck.Decision.RE_EVALUATE,
                    "selected offer index no longer present");
        }
        if (!matchesTargetOutput(request, liveOfferAtIndex.outputItemId(), liveOfferAtIndex.outputCount(),
                liveOfferAtIndex.outputEnchantments())) {
            return new PreCommitCheck(PreCommitCheck.Decision.RE_EVALUATE,
                    "live offer at index " + liveOfferAtIndex.offerIndex() + " no longer matches target");
        }
        if (!liveOfferAtIndex.hasRemainingUses()) {
            return new PreCommitCheck(PreCommitCheck.Decision.SOLD_OUT,
                    "sold-out: offer became out of stock before commit");
        }
        int liveEmeraldCost = liveOfferAtIndex.emeraldCost();
        if (request.hasEmeraldCap() && (emeraldsSpentSoFar + liveEmeraldCost > request.maxEmeralds())) {
            int remaining = Math.max(0, request.maxEmeralds() - Math.max(0, emeraldsSpentSoFar));
            return new PreCommitCheck(PreCommitCheck.Decision.OVER_BUDGET,
                    "over-budget: live offer costs " + liveEmeraldCost
                            + " emeralds (cap=" + request.maxEmeralds()
                            + ", spent=" + emeraldsSpentSoFar
                            + ", remaining=" + remaining + ")");
        }
        if (plannedOffer != null && liveEmeraldCost != plannedOffer.emeraldCost()) {
            return new PreCommitCheck(PreCommitCheck.Decision.RE_EVALUATE,
                    "live emerald price changed from " + plannedOffer.emeraldCost() + " to " + liveEmeraldCost);
        }
        if (!inventory.hasInventorySpaceForOutput()) {
            return new PreCommitCheck(PreCommitCheck.Decision.INVENTORY_FULL,
                    "inventory-full: no free inventory slot before commit");
        }
        if (!paymentSlotsSatisfyOffer || displayedOutputItemId == null || displayedOutputItemId.isBlank()
                || displayedOutputCount <= 0) {
            return new PreCommitCheck(PreCommitCheck.Decision.WAIT_SYNC,
                    "waiting for payment slots and output slot sync");
        }
        if (!matchesTargetOutput(request, displayedOutputItemId, displayedOutputCount, displayedOutputEnchants)) {
            return new PreCommitCheck(PreCommitCheck.Decision.RE_EVALUATE,
                    "displayed output slot (" + displayedOutputItemId + " " + displayedOutputEnchants
                            + ") does not match requested " + request.describeTarget());
        }
        return new PreCommitCheck(PreCommitCheck.Decision.COMMIT, "verified live offer and budget before commit");
    }

    public static boolean matchesTargetOutput(VillagerTradeRequest request,
                                              String itemId,
                                              int count,
                                              Map<String, Integer> enchantments) {
        if (itemId == null || itemId.isBlank() || count <= 0) {
            return false;
        }
        String normalized = VillagerTradeRequest.normalizeItemId(itemId);
        if (!request.outputItemId().equals(normalized)) {
            return false;
        }
        return request.componentSpec().matchesEnchantments(enchantments);
    }

    /** Returns null when every required input (costA and optional costB) is available, else a descriptive message. */
    private static String validateRequiredInputs(TradeOfferSnapshot offer, TradeInventorySnapshot inventory) {
        if (offer.costACount() > 0) {
            if (!offer.costAComponentsSatisfied()) {
                return "missing matching input A (" + offer.costAItemId() + " x" + offer.costACount() + ")";
            }
            int requiredA = offer.costACount();
            if (offer.hasCostB() && offer.costAItemId().equals(offer.costBItemId())) {
                requiredA += offer.costBCount();
            }
            int haveA = inventory.countOwnedItem(offer.costAItemId());
            if (haveA < requiredA) {
                return "need " + offer.costAItemId() + " x" + requiredA + ", have " + haveA;
            }
        }
        if (offer.hasCostB()) {
            if (!offer.costBComponentsSatisfied()) {
                return "missing matching input B (" + offer.costBItemId() + " x" + offer.costBCount() + ")";
            }
            if (!offer.costAItemId().equals(offer.costBItemId())) {
                int haveB = inventory.countOwnedItem(offer.costBItemId());
                if (haveB < offer.costBCount()) {
                    return "need " + offer.costBItemId() + " x" + offer.costBCount() + ", have " + haveB;
                }
            }
        }
        return null;
    }
}
