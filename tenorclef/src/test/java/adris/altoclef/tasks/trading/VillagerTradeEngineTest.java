package adris.altoclef.tasks.trading;

import adris.altoclef.tasksystem.FailureReason;
import adris.altoclef.tasksystem.TaskResult;
import adris.altoclef.tasksystem.TaskResultMapper;
import adris.altoclef.util.ItemComponentSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Acceptance-level unit tests for villager trading policy, exact component validation,
 * budget enforcement, pre-commit recheck, and post-interruption inventory reconciliation.
 */
public class VillagerTradeEngineTest {

    private static VillagerTradeRequest mendingBookRequest(int count, int maxEmeralds) {
        return new VillagerTradeRequest(
                "minecraft:enchanted_book",
                ItemComponentSpec.ofEnchantment("minecraft:mending"),
                count,
                maxEmeralds,
                null);
    }

    private static TradeOfferSnapshot bookOffer(int index,
                                                int emeraldCost,
                                                String enchantId,
                                                int enchantLevel,
                                                int uses,
                                                int maxUses,
                                                boolean outOfStock) {
        return new TradeOfferSnapshot(
                index,
                "minecraft:emerald", emeraldCost, true,
                "minecraft:book", 1, true,
                "minecraft:enchanted_book", 1,
                Map.of(enchantId, enchantLevel),
                uses, maxUses, outOfStock);
    }

    private static TradeInventorySnapshot inv(int matchingInv,
                                              int matchingCursor,
                                              int emeralds,
                                              int plainBooks,
                                              boolean hasSpace,
                                              boolean droppedNearby) {
        return new TradeInventorySnapshot(
                matchingInv,
                matchingCursor,
                emeralds,
                Map.of("minecraft:emerald", emeralds, "minecraft:book", plainBooks),
                hasSpace,
                droppedNearby);
    }

    /**
     * Acceptance pass case:
     * Librarian with two look-alike enchanted book offers (Unbreaking III + Mending),
     * player starts with 0 Mending books, 20 emeralds, 1 book, cap=20 emeralds.
     */
    @Test
    void passCaseSelectsExactMendingOfferAndConfirmsInventoryDebit() {
        VillagerTradeRequest req = mendingBookRequest(1, 20);
        VillagerTradeSession session = new VillagerTradeSession(req);

        List<TradeOfferSnapshot> offers = List.of(
                bookOffer(0, 10, "minecraft:unbreaking", 3, 0, 12, false),
                bookOffer(1, 14, "minecraft:mending", 1, 0, 12, false)
        );
        TradeInventorySnapshot startInv = inv(0, 0, 20, 1, true, false);

        // Initial reconcile -> proceed
        VillagerTradeSession.ReconcileResult r0 = session.reconcile(startInv, 100L);
        Assertions.assertEquals(VillagerTradeSession.ReconcileResult.Action.PROCEED, r0.action());

        // Policy ignores look-alike Unbreaking III and selects Offer #1 (Mending, 14 emeralds)
        VillagerTradePolicy.OfferEvaluation eval = VillagerTradePolicy.evaluateOffers(req, offers, startInv, 0);
        Assertions.assertEquals(VillagerTradePolicy.OfferEvaluation.Decision.SELECT_OFFER, eval.decision());
        Assertions.assertNotNull(eval.selectedOffer());
        Assertions.assertEquals(1, eval.selectedOffer().offerIndex());
        Assertions.assertEquals(14, eval.selectedOffer().emeraldCost());

        // Pre-commit recheck rejects if displayed output slot somehow shows the look-alike book
        VillagerTradePolicy.PreCommitCheck wrongSlotCheck = VillagerTradePolicy.recheckBeforeCommit(
                req, eval.selectedOffer(), offers.get(1),
                "minecraft:enchanted_book", 1, Map.of("minecraft:unbreaking", 3),
                true, startInv, session.targetTotalCount(), session.emeraldsSpent());
        Assertions.assertEquals(VillagerTradePolicy.PreCommitCheck.Decision.RE_EVALUATE, wrongSlotCheck.decision());

        // Pre-commit recheck passes when live offer & displayed slot match Mending within budget
        VillagerTradePolicy.PreCommitCheck okCheck = VillagerTradePolicy.recheckBeforeCommit(
                req, eval.selectedOffer(), offers.get(1),
                "minecraft:enchanted_book", 1, Map.of("minecraft:mending", 1),
                true, startInv, session.targetTotalCount(), session.emeraldsSpent());
        Assertions.assertEquals(VillagerTradePolicy.PreCommitCheck.Decision.COMMIT, okCheck.decision());

        // Commit starts -> output moves to cursor -> deposit -> inventory confirms +1 Mending & -14 emeralds
        session.recordCommitStarted(startInv, eval.selectedOffer(), 105L);
        TradeInventorySnapshot onCursor = inv(0, 1, 6, 0, true, false);
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.DEPOSIT_CURSOR,
                session.reconcile(onCursor, 106L).action());

        TradeInventorySnapshot afterDeposit = inv(1, 0, 6, 0, true, false);
        VillagerTradeSession.ReconcileResult finalRec = session.reconcile(afterDeposit, 107L);
        Assertions.assertEquals(VillagerTradeSession.ReconcileResult.Action.ALREADY_COMPLETE, finalRec.action());
        Assertions.assertTrue(session.outcome().isSuccess());
        Assertions.assertEquals(1, session.outcome().targetItemsReceived());
        Assertions.assertEquals(14, session.outcome().emeraldsSpent());
        Assertions.assertTrue(session.outcome().emeraldsSpent() <= 20);
    }

    /**
     * Acceptance failure case 1: Over-budget offer -> spends 0 emeralds, reports over-budget.
     */
    @Test
    void overBudgetOfferSpendsZeroEmeraldsAndReportsOverBudget() {
        VillagerTradeRequest req = mendingBookRequest(1, 20);
        VillagerTradeSession session = new VillagerTradeSession(req);
        TradeInventorySnapshot startInv = inv(0, 0, 30, 1, true, false);

        List<TradeOfferSnapshot> offers = List.of(
                bookOffer(0, 8, "minecraft:unbreaking", 3, 0, 12, false),
                bookOffer(1, 24, "minecraft:mending", 1, 0, 12, false)
        );

        session.initBaselineIfNeeded(startInv);
        VillagerTradePolicy.OfferEvaluation eval = VillagerTradePolicy.evaluateOffers(req, offers, startInv, 0);
        Assertions.assertEquals(VillagerTradePolicy.OfferEvaluation.Decision.OVER_BUDGET, eval.decision());
        Assertions.assertEquals(24, eval.cheapestSeenEmeraldCost());

        session.failFromEvaluation(eval, startInv);
        VillagerTradeOutcome out = session.outcome();
        Assertions.assertEquals(VillagerTradeOutcome.Status.OVER_BUDGET, out.status());
        Assertions.assertEquals(FailureReason.PRECONDITION_FAILED, out.failureReason());
        Assertions.assertEquals(0, out.emeraldsSpent());
        Assertions.assertEquals(0, out.targetItemsReceived());
        Assertions.assertTrue(out.detail().contains("over-budget"));
        Assertions.assertEquals(TaskResult.FAILURE, TaskResultMapper.resolve(TaskResult.FAILURE, true, false, true));
    }

    /**
     * Pre-commit budget guard: price rises above cap between selection and commit -> spends 0 emeralds.
     */
    @Test
    void priceSpikeBeforeCommitAbortsOverBudget() {
        VillagerTradeRequest req = mendingBookRequest(1, 20);
        TradeOfferSnapshot planned = bookOffer(1, 18, "minecraft:mending", 1, 0, 12, false);
        TradeOfferSnapshot repriced = bookOffer(1, 22, "minecraft:mending", 1, 0, 12, false);
        TradeInventorySnapshot startInv = inv(0, 0, 30, 1, true, false);

        VillagerTradePolicy.PreCommitCheck check = VillagerTradePolicy.recheckBeforeCommit(
                req, planned, repriced,
                "minecraft:enchanted_book", 1, Map.of("minecraft:mending", 1),
                true, startInv, 1, 0);
        Assertions.assertEquals(VillagerTradePolicy.PreCommitCheck.Decision.OVER_BUDGET, check.decision());
    }

    /**
     * Acceptance failure case 2: Sold-out offer -> spends 0 emeralds, reports sold-out.
     */
    @Test
    void soldOutOfferSpendsZeroEmeraldsAndReportsSoldOut() {
        VillagerTradeRequest req = mendingBookRequest(1, 20);
        VillagerTradeSession session = new VillagerTradeSession(req);
        TradeInventorySnapshot startInv = inv(0, 0, 20, 1, true, false);

        List<TradeOfferSnapshot> offers = List.of(
                bookOffer(0, 10, "minecraft:unbreaking", 3, 0, 12, false),
                bookOffer(1, 14, "minecraft:mending", 1, 12, 12, true)
        );

        session.initBaselineIfNeeded(startInv);
        VillagerTradePolicy.OfferEvaluation eval = VillagerTradePolicy.evaluateOffers(req, offers, startInv, 0);
        Assertions.assertEquals(VillagerTradePolicy.OfferEvaluation.Decision.SOLD_OUT, eval.decision());

        session.failFromEvaluation(eval, startInv);
        VillagerTradeOutcome out = session.outcome();
        Assertions.assertEquals(VillagerTradeOutcome.Status.SOLD_OUT, out.status());
        Assertions.assertEquals(FailureReason.TARGET_UNAVAILABLE, out.failureReason());
        Assertions.assertEquals(0, out.emeraldsSpent());
        Assertions.assertEquals(0, out.targetItemsReceived());
        Assertions.assertTrue(out.detail().contains("sold-out"));
    }

    /**
     * Acceptance failure/edge case 3a: Interruption immediately after taking the output
     * (output already landed in inventory) -> reconcile on resume; never buy a second book.
     */
    @Test
    void interruptionAfterOutputTakenReconcilesInventoryWithoutBuyingSecondBook() {
        VillagerTradeRequest req = mendingBookRequest(1, 20);
        VillagerTradeSession session = new VillagerTradeSession(req);
        TradeOfferSnapshot mendingOffer = bookOffer(1, 14, "minecraft:mending", 1, 0, 12, false);
        TradeInventorySnapshot beforeCommit = inv(0, 0, 40, 2, true, false);

        session.initBaselineIfNeeded(beforeCommit);
        session.recordCommitStarted(beforeCommit, mendingOffer, 200L);
        Assertions.assertEquals(1, session.commitAttempts());

        // Simulated interruption + resume after server delivered the 1 Mending book & debited 14 emeralds
        TradeInventorySnapshot onResume = inv(1, 0, 26, 1, true, false);
        VillagerTradeSession.ReconcileResult rec = session.reconcile(onResume, 250L);

        Assertions.assertEquals(VillagerTradeSession.ReconcileResult.Action.ALREADY_COMPLETE, rec.action());
        Assertions.assertTrue(session.outcome().isSuccess());
        Assertions.assertEquals(1, session.outcome().targetItemsReceived());
        Assertions.assertEquals(14, session.outcome().emeraldsSpent());
        Assertions.assertEquals(1, session.commitAttempts());
    }

    /**
     * Acceptance failure/edge case 3b: Interruption immediately after clicking OUTPUT_SLOT while the
     * Mending book is still on the cursor -> deposits cursor first, then finishes without a 2nd purchase.
     */
    @Test
    void interruptionWhileOutputOnCursorDepositsAndNeverDuplicates() {
        VillagerTradeRequest req = mendingBookRequest(1, 20);
        VillagerTradeSession session = new VillagerTradeSession(req);
        TradeOfferSnapshot mendingOffer = bookOffer(1, 14, "minecraft:mending", 1, 0, 12, false);
        TradeInventorySnapshot beforeCommit = inv(0, 0, 40, 2, true, false);

        session.initBaselineIfNeeded(beforeCommit);
        session.recordCommitStarted(beforeCommit, mendingOffer, 300L);

        // Resume sees item still on cursor
        TradeInventorySnapshot cursorResume = inv(0, 1, 26, 1, true, false);
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.DEPOSIT_CURSOR,
                session.reconcile(cursorResume, 310L).action());
        Assertions.assertFalse(session.isTerminal());

        // Next tick after depositing cursor into inventory
        TradeInventorySnapshot landed = inv(1, 0, 26, 1, true, false);
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.ALREADY_COMPLETE,
                session.reconcile(landed, 311L).action());
        Assertions.assertEquals(1, session.outcome().targetItemsReceived());
        Assertions.assertEquals(14, session.outcome().emeraldsSpent());
        Assertions.assertEquals(1, session.commitAttempts());
    }

    /**
     * Acceptance failure/edge case 3c: Interruption closed screen while output was on cursor so it dropped
     * beside the player -> reconciles debit + nearby drop -> picks up drop instead of buying a 2nd book.
     */
    @Test
    void interruptionDroppingCursorBookRecoversDropWithoutDuplicatePurchase() {
        VillagerTradeRequest req = mendingBookRequest(1, 20);
        VillagerTradeSession session = new VillagerTradeSession(req);
        TradeOfferSnapshot mendingOffer = bookOffer(1, 14, "minecraft:mending", 1, 0, 12, false);
        TradeInventorySnapshot beforeCommit = inv(0, 0, 40, 2, true, false);

        session.initBaselineIfNeeded(beforeCommit);
        session.recordCommitStarted(beforeCommit, mendingOffer, 400L);

        TradeInventorySnapshot afterDrop = inv(0, 0, 26, 1, true, true);
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.PICKUP_DROPPED_OUTPUT,
                session.reconcile(afterDrop, 405L).action());

        TradeInventorySnapshot afterPickup = inv(1, 0, 26, 1, true, false);
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.ALREADY_COMPLETE,
                session.reconcile(afterPickup, 415L).action());
        Assertions.assertEquals(1, session.outcome().targetItemsReceived());
        Assertions.assertEquals(14, session.outcome().emeraldsSpent());
        Assertions.assertEquals(1, session.commitAttempts());
    }

    /**
     * Distinguishes "no effect -> bounded retry" from "emeralds debited without output -> refuse duplicate".
     */
    @Test
    void distinguishesNoEffectRetryFromAmbiguousDebit() {
        VillagerTradeRequest req = mendingBookRequest(1, 20);
        TradeOfferSnapshot mendingOffer = bookOffer(1, 14, "minecraft:mending", 1, 0, 12, false);
        TradeInventorySnapshot unchanged = inv(0, 0, 20, 1, true, false);

        // No-effect click: waits during confirm window, then retries up to bound
        VillagerTradeSession retrySession = new VillagerTradeSession(req, 5, 5, 2, 10L);
        retrySession.recordCommitStarted(unchanged, mendingOffer, 100L);
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.WAIT_CONFIRM,
                retrySession.reconcile(unchanged, 105L).action());
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.RETRY_NO_EFFECT,
                retrySession.reconcile(unchanged, 110L).action());

        // Second no-effect click hits maxCommitAttempts=2 -> terminal INTERACTION_FAILED
        retrySession.recordCommitStarted(unchanged, mendingOffer, 120L);
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.FAILED,
                retrySession.reconcile(unchanged, 130L).action());
        Assertions.assertEquals(VillagerTradeOutcome.Status.INTERACTION_FAILED, retrySession.outcome().status());

        // Ambiguous debit: emeralds debited, no item anywhere after confirm window -> fails instead of duplicating
        VillagerTradeSession ambigSession = new VillagerTradeSession(req, 5, 5, 3, 10L);
        ambigSession.recordCommitStarted(unchanged, mendingOffer, 200L);
        TradeInventorySnapshot missingBook = inv(0, 0, 6, 0, true, false);
        Assertions.assertEquals(
                VillagerTradeSession.ReconcileResult.Action.FAILED,
                ambigSession.reconcile(missingBook, 210L).action());
        Assertions.assertEquals(VillagerTradeOutcome.Status.INTERACTION_FAILED, ambigSession.outcome().status());
        Assertions.assertEquals(14, ambigSession.outcome().emeraldsSpent());
    }

    @Test
    void validatesMissingSecondaryInputOfferUnavailableAndCumulativeCap() {
        VillagerTradeRequest req1 = mendingBookRequest(1, 20);
        List<TradeOfferSnapshot> offers = List.of(
                bookOffer(0, 14, "minecraft:mending", 1, 0, 12, false)
        );

        // Missing plain book (input B) -> MISSING_INPUTS, 0 emeralds spent
        TradeInventorySnapshot noBook = inv(0, 0, 20, 0, true, false);
        VillagerTradePolicy.OfferEvaluation missingEval =
                VillagerTradePolicy.evaluateOffers(req1, offers, noBook, 0);
        Assertions.assertEquals(VillagerTradePolicy.OfferEvaluation.Decision.MISSING_INPUTS, missingEval.decision());
        Assertions.assertTrue(missingEval.detail().contains("minecraft:book"));

        // Offer unavailable (only look-alike Unbreaking III)
        List<TradeOfferSnapshot> onlyLookAlike = List.of(
                bookOffer(0, 10, "minecraft:unbreaking", 3, 0, 12, false)
        );
        VillagerTradePolicy.OfferEvaluation unavail =
                VillagerTradePolicy.evaluateOffers(req1, onlyLookAlike, inv(0, 0, 20, 1, true, false), 0);
        Assertions.assertEquals(VillagerTradePolicy.OfferEvaluation.Decision.OFFER_UNAVAILABLE, unavail.decision());

        // Cumulative cap across multiple trades: cap 20, already spent 14 -> second 14-emerald trade is OVER_BUDGET
        VillagerTradeRequest req2 = mendingBookRequest(2, 20);
        VillagerTradePolicy.OfferEvaluation secondEval =
                VillagerTradePolicy.evaluateOffers(req2, offers, inv(1, 0, 26, 1, true, false), 14);
        Assertions.assertEquals(VillagerTradePolicy.OfferEvaluation.Decision.OVER_BUDGET, secondEval.decision());

        // Cheapest offer wins when multiple matching in-stock offers exist
        List<TradeOfferSnapshot> multiMending = List.of(
                bookOffer(0, 18, "minecraft:mending", 1, 0, 12, false),
                bookOffer(1, 12, "minecraft:mending", 1, 0, 12, false)
        );
        VillagerTradePolicy.OfferEvaluation cheapest =
                VillagerTradePolicy.evaluateOffers(req1, multiMending, inv(0, 0, 20, 1, true, false), 0);
        Assertions.assertEquals(VillagerTradePolicy.OfferEvaluation.Decision.SELECT_OFFER, cheapest.decision());
        Assertions.assertEquals(1, cheapest.selectedOffer().offerIndex());
        Assertions.assertEquals(12, cheapest.selectedOffer().emeraldCost());
    }
}
