package adris.altoclef.tasks.trading;

import adris.altoclef.tasksystem.FailureReason;

/**
 * Stateful order ledger and post-interruption reconciliation engine for a single villager trade order.
 * <p>
 * Survives task interruptions and {@code Task.reset()} cycles so the task always reconciles
 * observable inventory before attempting another purchase and never duplicates a completed trade.
 * Pure Java (no Minecraft runtime dependency).
 */
public final class VillagerTradeSession {

    public static final int DEFAULT_MAX_OPEN_ATTEMPTS = 6;
    public static final int DEFAULT_MAX_SELECT_ATTEMPTS = 5;
    public static final int DEFAULT_MAX_COMMIT_ATTEMPTS = 3;
    public static final long DEFAULT_COMMIT_CONFIRM_TICKS = 20L;

    public static final class ReconcileResult {
        public enum Action {
            ALREADY_COMPLETE,
            DEPOSIT_CURSOR,
            PICKUP_DROPPED_OUTPUT,
            WAIT_CONFIRM,
            COMMIT_CONFIRMED_CONTINUE,
            RETRY_NO_EFFECT,
            FAILED,
            PROCEED
        }

        private final Action action;
        private final String detail;

        public ReconcileResult(Action action, String detail) {
            this.action = action;
            this.detail = detail == null ? "" : detail;
        }

        public Action action() {
            return action;
        }

        public String detail() {
            return detail;
        }
    }

    private final VillagerTradeRequest request;
    private final int maxOpenAttempts;
    private final int maxSelectAttempts;
    private final int maxCommitAttempts;
    private final long commitConfirmTicks;

    private boolean baselineInitialized = false;
    private int baselineMatchingCount = 0;
    private int baselineEmeraldCount = 0;
    private int targetTotalCount = 0;
    private int emeraldsSpent = 0;

    private boolean commitInFlight = false;
    private int preCommitMatchingCount = 0;
    private int preCommitEmeraldCount = 0;
    private int expectedOutputCount = 0;
    private int expectedEmeraldCost = 0;
    private long commitStartTick = 0L;

    private int openAttempts = 0;
    private int selectAttempts = 0;
    private int commitAttempts = 0;

    private VillagerTradeOutcome outcome;

    public VillagerTradeSession(VillagerTradeRequest request) {
        this(request, DEFAULT_MAX_OPEN_ATTEMPTS, DEFAULT_MAX_SELECT_ATTEMPTS,
                DEFAULT_MAX_COMMIT_ATTEMPTS, DEFAULT_COMMIT_CONFIRM_TICKS);
    }

    public VillagerTradeSession(VillagerTradeRequest request,
                                int maxOpenAttempts,
                                int maxSelectAttempts,
                                int maxCommitAttempts,
                                long commitConfirmTicks) {
        this.request = request;
        this.maxOpenAttempts = Math.max(1, maxOpenAttempts);
        this.maxSelectAttempts = Math.max(1, maxSelectAttempts);
        this.maxCommitAttempts = Math.max(1, maxCommitAttempts);
        this.commitConfirmTicks = Math.max(1L, commitConfirmTicks);
        this.outcome = VillagerTradeOutcome.running(request.maxEmeralds());
    }

    public VillagerTradeRequest request() {
        return request;
    }

    public VillagerTradeOutcome outcome() {
        return outcome;
    }

    public boolean isTerminal() {
        return outcome.isTerminal();
    }

    public int targetTotalCount() {
        return baselineInitialized ? targetTotalCount : request.targetCount();
    }

    public int emeraldsSpent() {
        return emeraldsSpent;
    }

    public int baselineMatchingCount() {
        return baselineMatchingCount;
    }

    public int baselineEmeraldCount() {
        return baselineEmeraldCount;
    }

    public boolean isCommitInFlight() {
        return commitInFlight;
    }

    public int openAttempts() {
        return openAttempts;
    }

    public int selectAttempts() {
        return selectAttempts;
    }

    public int commitAttempts() {
        return commitAttempts;
    }

    public void initBaselineIfNeeded(TradeInventorySnapshot snap) {
        if (baselineInitialized || snap == null) {
            return;
        }
        baselineInitialized = true;
        baselineMatchingCount = snap.matchingTargetInInventory();
        baselineEmeraldCount = snap.totalEmeraldsOwned();
        targetTotalCount = request.targetCount();
    }

    /**
     * Reconciles observable inventory against baseline and any in-flight commit evidence.
     * MUST be called before approaching, opening, selecting, or committing another trade,
     * especially after any interruption or unexpected screen close.
     */
    public ReconcileResult reconcile(TradeInventorySnapshot snap, long currentTick) {
        if (outcome.isTerminal()) {
            return outcome.isSuccess()
                    ? new ReconcileResult(ReconcileResult.Action.ALREADY_COMPLETE, outcome.detail())
                    : new ReconcileResult(ReconcileResult.Action.FAILED, outcome.detail());
        }
        initBaselineIfNeeded(snap);

        // If a matching target item is currently on the cursor (e.g., right after PICKUP on output slot
        // or after an interruption mid-transfer), land it into player inventory before doing anything else.
        if (snap.matchingTargetInCursor() > 0) {
            return new ReconcileResult(ReconcileResult.Action.DEPOSIT_CURSOR,
                    "depositing matching target item from cursor (" + snap.matchingTargetInCursor() + ")");
        }

        if (commitInFlight) {
            int gainedMatching = snap.matchingTargetInInventory() - preCommitMatchingCount;
            int debitedEmeralds = preCommitEmeraldCount - snap.totalEmeraldsOwned();
            long elapsed = Math.max(0L, currentTick - commitStartTick);

            if (gainedMatching >= expectedOutputCount || snap.matchingTargetInInventory() >= targetTotalCount) {
                // Wait briefly only if emeralds haven't synced yet while output already arrived.
                if (expectedEmeraldCost > 0 && debitedEmeralds < expectedEmeraldCost && elapsed < commitConfirmTicks) {
                    return new ReconcileResult(ReconcileResult.Action.WAIT_CONFIRM,
                            "output received, waiting for emerald debit sync");
                }
                int observedSpend = Math.max(0, debitedEmeralds);
                emeraldsSpent += observedSpend;
                commitInFlight = false;
                if (snap.matchingTargetInInventory() >= targetTotalCount) {
                    int received = Math.max(0, snap.matchingTargetInInventory() - baselineMatchingCount);
                    outcome = VillagerTradeOutcome.success(
                            received,
                            emeraldsSpent,
                            request.maxEmeralds(),
                            "received " + received + " " + request.describeTarget()
                                    + " spending " + emeraldsSpent + " emeralds");
                    return new ReconcileResult(ReconcileResult.Action.ALREADY_COMPLETE, outcome.detail());
                }
                return new ReconcileResult(ReconcileResult.Action.COMMIT_CONFIRMED_CONTINUE,
                        "confirmed +" + gainedMatching + " item(s), spent " + observedSpend + " emeralds");
            }

            if (debitedEmeralds > 0) {
                if (snap.matchingItemDroppedNearby()) {
                    return new ReconcileResult(ReconcileResult.Action.PICKUP_DROPPED_OUTPUT,
                            "emeralds debited (" + debitedEmeralds + ") and matching output dropped nearby; picking up");
                }
                if (elapsed < commitConfirmTicks) {
                    return new ReconcileResult(ReconcileResult.Action.WAIT_CONFIRM,
                            "emeralds debited (" + debitedEmeralds + "), awaiting output inventory sync");
                }
                emeraldsSpent += debitedEmeralds;
                commitInFlight = false;
                return fail(
                        VillagerTradeOutcome.Status.INTERACTION_FAILED,
                        FailureReason.BACKEND_FAILURE,
                        "trade debited " + debitedEmeralds + " emeralds without confirming matching output; refusing duplicate purchase",
                        snap,
                        -1);
            }

            if (elapsed < commitConfirmTicks) {
                return new ReconcileResult(ReconcileResult.Action.WAIT_CONFIRM,
                        "awaiting trade commit confirmation");
            }

            commitInFlight = false;
            if (commitAttempts >= maxCommitAttempts) {
                return fail(
                        VillagerTradeOutcome.Status.INTERACTION_FAILED,
                        FailureReason.TIMEOUT,
                        "trade commit produced no observable inventory change after " + commitAttempts + " attempt(s)",
                        snap,
                        -1);
            }
            return new ReconcileResult(ReconcileResult.Action.RETRY_NO_EFFECT,
                    "commit click had no observable effect; retrying (" + commitAttempts + "/" + maxCommitAttempts + ")");
        }

        if (snap.matchingTargetInInventory() >= targetTotalCount) {
            int received = Math.max(0, snap.matchingTargetInInventory() - baselineMatchingCount);
            outcome = VillagerTradeOutcome.success(
                    received,
                    emeraldsSpent,
                    request.maxEmeralds(),
                    "target already met (" + snap.matchingTargetInInventory() + "/" + targetTotalCount
                            + "), spent " + emeraldsSpent + " emeralds");
            return new ReconcileResult(ReconcileResult.Action.ALREADY_COMPLETE, outcome.detail());
        }

        return new ReconcileResult(ReconcileResult.Action.PROCEED, "ready to trade");
    }

    public void recordCommitStarted(TradeInventorySnapshot preCommitSnap,
                                    TradeOfferSnapshot offer,
                                    long currentTick) {
        initBaselineIfNeeded(preCommitSnap);
        this.commitInFlight = true;
        this.preCommitMatchingCount = preCommitSnap.matchingTargetInInventory();
        this.preCommitEmeraldCount = preCommitSnap.totalEmeraldsOwned();
        this.expectedOutputCount = Math.max(1, offer.outputCount());
        this.expectedEmeraldCost = offer.emeraldCost();
        this.commitStartTick = currentTick;
        this.commitAttempts++;
    }

    public boolean recordOpenAttempt() {
        openAttempts++;
        return openAttempts <= maxOpenAttempts;
    }

    public boolean recordSelectAttempt() {
        selectAttempts++;
        return selectAttempts <= maxSelectAttempts;
    }

    public void resetSelectAttempts() {
        selectAttempts = 0;
    }

    public ReconcileResult failFromEvaluation(VillagerTradePolicy.OfferEvaluation eval,
                                              TradeInventorySnapshot snap) {
        return switch (eval.decision()) {
            case OVER_BUDGET -> fail(VillagerTradeOutcome.Status.OVER_BUDGET,
                    FailureReason.PRECONDITION_FAILED, eval.detail(), snap, eval.cheapestSeenEmeraldCost());
            case SOLD_OUT -> fail(VillagerTradeOutcome.Status.SOLD_OUT,
                    FailureReason.TARGET_UNAVAILABLE, eval.detail(), snap, eval.cheapestSeenEmeraldCost());
            case OFFER_UNAVAILABLE -> fail(VillagerTradeOutcome.Status.OFFER_UNAVAILABLE,
                    FailureReason.TARGET_UNAVAILABLE, eval.detail(), snap, eval.cheapestSeenEmeraldCost());
            case MISSING_INPUTS -> fail(VillagerTradeOutcome.Status.MISSING_INPUTS,
                    FailureReason.RESOURCE_MISSING, eval.detail(), snap, eval.cheapestSeenEmeraldCost());
            case INVENTORY_FULL -> fail(VillagerTradeOutcome.Status.INVENTORY_FULL,
                    FailureReason.INVENTORY_FULL, eval.detail(), snap, eval.cheapestSeenEmeraldCost());
            default -> throw new IllegalArgumentException("Not a failure evaluation: " + eval.decision());
        };
    }

    public ReconcileResult fail(VillagerTradeOutcome.Status status,
                                FailureReason reason,
                                String detail,
                                TradeInventorySnapshot snap,
                                int cheapestSeenEmeraldCost) {
        int currentMatching = snap != null ? snap.matchingTargetInInventory() : baselineMatchingCount;
        int received = Math.max(0, currentMatching - baselineMatchingCount);
        this.outcome = VillagerTradeOutcome.failure(
                status, reason, detail, received, emeraldsSpent, request.maxEmeralds(), cheapestSeenEmeraldCost);
        return new ReconcileResult(ReconcileResult.Action.FAILED, detail);
    }

    public void markCancelled(TradeInventorySnapshot snap, String detail) {
        if (outcome.isTerminal()) {
            return;
        }
        int currentMatching = snap != null ? snap.matchingTargetInInventory() : baselineMatchingCount;
        int received = Math.max(0, currentMatching - baselineMatchingCount);
        this.outcome = VillagerTradeOutcome.cancelled(received, emeraldsSpent, request.maxEmeralds(), detail);
    }
}
