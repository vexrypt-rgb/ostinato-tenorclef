package adris.altoclef.tasks.replenish;

import java.util.Optional;

/**
 * Pure-Java state machine for a single-courier worksite replenishment job.
 * <p>
 * Enforces every replenishment invariant independent of Minecraft classes:
 * <ul>
 *     <li>Triggers strictly when destination count {@code < triggerBelow} (at-threshold never triggers).</li>
 *     <li>Never withdraws below {@code sourceReserve}; bounds every withdrawal by
 *         {@code min(remainingDeficit, freeInventoryCapacity, sourceSurplus)}.</li>
 *     <li>Rechecks destination count on arrival; if a human player topped it up in transit, deposits
 *         only the remaining deficit and routes carried surplus back to source ({@link Phase#RETURN_SURPLUS}).</li>
 *     <li>Tracks and persists carried order inventory across interruptions and full restarts via
 *         {@link ReplenishmentJournal}, reconciling against live inventory so orders are neither
 *         double-withdrawn nor double-delivered.</li>
 *     <li>Backs off exponentially on source shortage or bounded transient failures rather than busy-looping.</li>
 *     <li>Reacts to external destination observation events (e.g. {@code ContainerSubTracker} updates)
 *         without continuous world scans, and tracks operational cost in {@link ReplenishmentMetrics}.</li>
 * </ul>
 */
public final class ReplenishmentSession {

    public enum Phase {
        IDLE_MONITORING,
        CHECK_DEST,
        WITHDRAW_SOURCE,
        DELIVER_DEST,
        RETURN_SURPLUS,
        BACKOFF,
        FAILED,
        CANCELLED
    }

    public enum ScreenActionKind {
        NONE,
        WITHDRAW,
        DEPOSIT
    }

    public record ScreenPlan(ScreenActionKind kind, int exactAmount, String note) {
        public static ScreenPlan none(String note) {
            return new ScreenPlan(ScreenActionKind.NONE, 0, note);
        }

        public static ScreenPlan withdraw(int amount, String note) {
            return new ScreenPlan(ScreenActionKind.WITHDRAW, Math.max(0, amount), note);
        }

        public static ScreenPlan deposit(int amount, String note) {
            return new ScreenPlan(ScreenActionKind.DEPOSIT, Math.max(0, amount), note);
        }
    }

    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 5;
    public static final int DEFAULT_MAX_TRIPS_PER_ORDER = 8;
    public static final long[] DEFAULT_SHORTAGE_BACKOFF_MS = {5_000L, 15_000L, 45_000L, 120_000L};
    public static final long[] DEFAULT_FAILURE_BACKOFF_MS = {2_000L, 5_000L, 15_000L, 45_000L};

    private final ReplenishmentRule rule;
    private final ReplenishmentJournal.Store journalStore;
    private final ReplenishmentMetrics metrics = new ReplenishmentMetrics();
    private final int maxConsecutiveFailures;
    private final int maxTripsPerOrder;
    private final long[] shortageBackoffMs;
    private final long[] failureBackoffMs;

    private Phase phase = Phase.CHECK_DEST;
    private long orderId = 0L;
    private boolean activeOrder = false;
    private int baselinePersonalCount = 0;
    private int carriedForOrder = 0;
    private int visitCheckpointedWithdraw = 0;
    private int visitCheckpointedDeposit = 0;
    private int plannedDeficit = 0;
    private int tripsInCurrentOrder = 0;
    private boolean partialShortage = false;

    private int lastKnownDestCount = -1;
    private long lastDestCheckMs = 0L;
    private int lastKnownSourceCount = -1;
    private long lastSourceCheckMs = 0L;
    private long lastProcessedExternalDestMs = 0L;

    private long nextCheckMs = 0L;
    private long backoffUntilMs = 0L;
    private int consecutiveShortages = 0;
    private int consecutiveFailures = 0;
    private String lastReport = "initialized";
    private boolean initialized = false;

    public ReplenishmentSession(ReplenishmentRule rule, ReplenishmentJournal.Store journalStore) {
        this(rule, journalStore, DEFAULT_MAX_CONSECUTIVE_FAILURES, DEFAULT_MAX_TRIPS_PER_ORDER,
                DEFAULT_SHORTAGE_BACKOFF_MS, DEFAULT_FAILURE_BACKOFF_MS);
    }

    public ReplenishmentSession(ReplenishmentRule rule,
                                ReplenishmentJournal.Store journalStore,
                                int maxConsecutiveFailures,
                                int maxTripsPerOrder,
                                long[] shortageBackoffMs,
                                long[] failureBackoffMs) {
        this.rule = rule;
        this.journalStore = journalStore;
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.maxTripsPerOrder = Math.max(1, maxTripsPerOrder);
        this.shortageBackoffMs = shortageBackoffMs == null || shortageBackoffMs.length == 0
                ? DEFAULT_SHORTAGE_BACKOFF_MS : shortageBackoffMs.clone();
        this.failureBackoffMs = failureBackoffMs == null || failureBackoffMs.length == 0
                ? DEFAULT_FAILURE_BACKOFF_MS : failureBackoffMs.clone();
    }

    public ReplenishmentRule rule() {
        return rule;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isTerminal() {
        return phase == Phase.FAILED || phase == Phase.CANCELLED;
    }

    public boolean isActiveOrder() {
        return activeOrder;
    }

    public long orderId() {
        return orderId;
    }

    public int baselinePersonalCount() {
        return baselinePersonalCount;
    }

    public int carriedForOrder() {
        return carriedForOrder;
    }

    public int plannedDeficit() {
        return plannedDeficit;
    }

    public int lastKnownDestCount() {
        return lastKnownDestCount;
    }

    public int lastKnownSourceCount() {
        return lastKnownSourceCount;
    }

    public long nextCheckMs() {
        return nextCheckMs;
    }

    public long backoffUntilMs() {
        return backoffUntilMs;
    }

    public int consecutiveShortages() {
        return consecutiveShortages;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public String lastReport() {
        return lastReport;
    }

    public ReplenishmentMetrics metrics() {
        return metrics;
    }

    /**
     * Loads persisted journal (if any) on startup or reconciles existing in-memory state after an
     * interruption, reconstructing {@link #carriedForOrder()} from live player inventory so in-transit
     * items are delivered cleanly without duplicate withdrawals or duplicate deliveries.
     */
    public void reconcileOnStartOrResume(int liveInvCount, long nowMs) {
        int inv = Math.max(0, liveInvCount);
        if (!initialized) {
            initialized = true;
            Optional<ReplenishmentJournal.Entry> loaded = journalStore == null
                    ? Optional.empty() : journalStore.load(rule.ruleKey());
            if (loaded.isPresent()) {
                restoreFromJournal(loaded.get(), inv, nowMs);
                return;
            }
            baselinePersonalCount = inv;
            carriedForOrder = 0;
            activeOrder = false;
            phase = Phase.CHECK_DEST;
            nextCheckMs = nowMs;
            lastProcessedExternalDestMs = Math.max(lastProcessedExternalDestMs, nowMs);
            lastReport = "starting initial check of '" + rule.destinationName() + "'";
            persist();
            return;
        }
        // Already initialized in memory (task interrupted and resumed): reconcile against live inventory.
        reconcileLiveInventory(inv, nowMs, true);
    }

    private void restoreFromJournal(ReplenishmentJournal.Entry e, int liveInvCount, long nowMs) {
        this.orderId = e.orderId();
        this.activeOrder = e.activeOrder();
        this.baselinePersonalCount = Math.max(0, e.baselinePersonalCount());
        this.carriedForOrder = Math.max(0, e.carriedForOrder());
        this.plannedDeficit = Math.max(0, e.plannedDeficit());
        this.partialShortage = e.partialShortage();
        this.lastKnownDestCount = e.lastKnownDestCount();
        this.lastDestCheckMs = e.lastDestCheckMs();
        this.lastKnownSourceCount = e.lastKnownSourceCount();
        this.lastSourceCheckMs = e.lastSourceCheckMs();
        this.nextCheckMs = e.nextCheckMs();
        this.backoffUntilMs = e.backoffUntilMs();
        this.consecutiveShortages = Math.max(0, e.consecutiveShortages());
        this.consecutiveFailures = 0; // Fresh restart resets transient walk/open retries
        this.lastReport = e.lastReport();
        this.metrics.restoreFrom(e.metrics());
        Phase parsedPhase;
        try {
            parsedPhase = Phase.valueOf(e.phase());
        } catch (IllegalArgumentException ex) {
            parsedPhase = Phase.CHECK_DEST;
        }
        if (parsedPhase == Phase.CANCELLED || parsedPhase == Phase.FAILED) {
            // Re-issuing the command after prior cancel/fail starts active monitoring again,
            // while still reconciling any leftover carried order items.
            parsedPhase = this.activeOrder && this.carriedForOrder > 0 ? Phase.DELIVER_DEST : Phase.CHECK_DEST;
        }
        this.phase = parsedPhase;
        reconcileLiveInventory(liveInvCount, nowMs, true);
    }

    private void reconcileLiveInventory(int liveInvCount, long nowMs, boolean countAsRecovery) {
        visitCheckpointedWithdraw = 0;
        visitCheckpointedDeposit = 0;
        int aboveBaseline = Math.max(0, liveInvCount - baselinePersonalCount);
        if (activeOrder) {
            int reconstructed;
            if (phase == Phase.WITHDRAW_SOURCE) {
                // If interrupted mid-withdrawal before final journal flush, live delta above baseline is truth.
                reconstructed = aboveBaseline;
            } else {
                // During DELIVER_DEST or RETURN_SURPLUS, inventory may have dropped if deposit happened before crash.
                reconstructed = Math.min(Math.max(carriedForOrder, aboveBaseline), aboveBaseline);
                if (carriedForOrder > 0) {
                    reconstructed = Math.min(carriedForOrder, aboveBaseline);
                }
            }
            carriedForOrder = Math.max(0, reconstructed);
            if (carriedForOrder > 0) {
                if (countAsRecovery) {
                    metrics.recordInterruptRecovery();
                }
                if (phase == Phase.RETURN_SURPLUS) {
                    lastReport = "recovered " + carriedForOrder + " surplus " + rule.itemId()
                            + " in transit; returning to '" + rule.sourceName() + "'";
                } else {
                    phase = Phase.DELIVER_DEST;
                    lastReport = "recovered " + carriedForOrder + " " + rule.itemId()
                            + " in transit for order #" + orderId + "; delivering to '" + rule.destinationName() + "'";
                }
                persist();
                return;
            }
            // No carried items left in inventory for the active order (e.g. deposit already completed before restart).
            if (phase == Phase.DELIVER_DEST || phase == Phase.RETURN_SURPLUS) {
                if (countAsRecovery) {
                    metrics.recordInterruptRecovery();
                }
                clearOrder(liveInvCount);
                phase = Phase.CHECK_DEST;
                nextCheckMs = nowMs;
                lastReport = "reconciled completed in-flight transfer (0 carried); verifying '" + rule.destinationName() + "'";
                persist();
                return;
            }
        } else {
            baselinePersonalCount = liveInvCount;
            carriedForOrder = 0;
        }
        if (phase == Phase.IDLE_MONITORING && nowMs >= nextCheckMs) {
            phase = Phase.CHECK_DEST;
        } else if (phase == Phase.BACKOFF && nowMs >= backoffUntilMs) {
            phase = Phase.CHECK_DEST;
        }
        persist();
    }

    /**
     * External change-event hook (e.g. {@code ContainerSubTracker} updated cache for destination chest).
     * Avoids an extra destination open when the destination count was just observed.
     */
    public void onDestinationObservedExternally(int destCount, long observedMs, int liveInvCount, long nowMs) {
        if (isTerminal() || observedMs <= lastProcessedExternalDestMs || observedMs <= lastDestCheckMs) {
            return;
        }
        lastProcessedExternalDestMs = observedMs;
        lastKnownDestCount = Math.max(0, destCount);
        lastDestCheckMs = observedMs;

        if (phase != Phase.IDLE_MONITORING) {
            return;
        }
        if (rule.shouldTrigger(lastKnownDestCount)) {
            startOrderIfNeeded(lastKnownDestCount, liveInvCount);
            if (carriedForOrder > 0) {
                phase = Phase.DELIVER_DEST;
                lastReport = "change event: '" + rule.destinationName() + "' has " + lastKnownDestCount
                        + " (<" + rule.triggerBelow() + "); delivering carried " + carriedForOrder;
            } else {
                phase = Phase.WITHDRAW_SOURCE;
                lastReport = "change event: '" + rule.destinationName() + "' has " + lastKnownDestCount
                        + " (<" + rule.triggerBelow() + "); withdrawing " + plannedDeficit + " from '" + rule.sourceName() + "'";
            }
            persist();
        } else {
            nextCheckMs = nowMs + rule.pollIntervalMs();
            lastReport = "change event: '" + rule.destinationName() + "' has " + lastKnownDestCount
                    + " (>=" + rule.triggerBelow() + "); no refill needed";
            persist();
        }
    }

    /**
     * Lightweight O(1) background scheduler tick (no world scans).
     */
    public void tickScheduler(long nowMs, int liveInvCount) {
        if (isTerminal()) {
            return;
        }
        if (phase == Phase.IDLE_MONITORING) {
            if (nowMs >= nextCheckMs) {
                phase = Phase.CHECK_DEST;
                lastReport = "scheduled check of '" + rule.destinationName() + "'";
                persist();
            }
        } else if (phase == Phase.BACKOFF) {
            if (nowMs >= backoffUntilMs) {
                partialShortage = false;
                if (carriedForOrder > 0) {
                    phase = Phase.DELIVER_DEST;
                    lastReport = "backoff elapsed; delivering carried " + carriedForOrder + " to '" + rule.destinationName() + "'";
                } else {
                    phase = Phase.CHECK_DEST;
                    lastReport = "backoff elapsed; rechecking '" + rule.destinationName() + "'";
                }
                persist();
            }
        }
    }

    /**
     * Called when the destination chest screen opens with fresh live counts.
     * Decides whether to deposit right now (in the same open screen) or close immediately.
     */
    public ScreenPlan onDestinationOpened(int freshDestCount, int destFreeCapacity, int liveInvCount, long nowMs) {
        int dest = Math.max(0, freshDestCount);
        lastKnownDestCount = dest;
        lastDestCheckMs = nowMs;

        if (phase == Phase.DELIVER_DEST) {
            // Re-verify live inventory in case carried changed.
            int freshDeficit = rule.deficit(dest);
            int availableCarried = Math.min(carriedForOrder, Math.max(0, liveInvCount));
            int wantDeposit = Math.min(availableCarried, Math.min(freshDeficit, Math.max(0, destFreeCapacity)));
            if (wantDeposit > 0) {
                return ScreenPlan.deposit(wantDeposit,
                        "depositing " + wantDeposit + " into '" + rule.destinationName() + "' (had " + dest + ", target " + rule.targetCount() + ")");
            }
            if (freshDeficit == 0) {
                return ScreenPlan.none("'" + rule.destinationName() + "' already at/above target (" + dest + "/" + rule.targetCount() + ")");
            }
            if (destFreeCapacity <= 0) {
                return ScreenPlan.none("'" + rule.destinationName() + "' is full (" + dest + ")");
            }
            return ScreenPlan.none("no carried " + rule.itemId() + " to deposit");
        }

        if (phase == Phase.CHECK_DEST) {
            // If we somehow already carry order items while opening dest, deposit them in this same open screen.
            if (carriedForOrder > 0 && rule.deficit(dest) > 0) {
                int wantDeposit = Math.min(Math.min(carriedForOrder, Math.max(0, liveInvCount)),
                        Math.min(rule.deficit(dest), Math.max(0, destFreeCapacity)));
                if (wantDeposit > 0) {
                    return ScreenPlan.deposit(wantDeposit, "opportunistic deposit of carried " + wantDeposit);
                }
            }
            if (rule.shouldTrigger(dest)) {
                return ScreenPlan.none("'" + rule.destinationName() + "' below threshold (" + dest + " < " + rule.triggerBelow() + ")");
            }
            return ScreenPlan.none("'" + rule.destinationName() + "' healthy (" + dest + " >= " + rule.triggerBelow() + ")");
        }

        metrics.recordUnnecessaryChestOpen();
        return ScreenPlan.none("unexpected destination open in phase " + phase);
    }

    /**
     * Called once the destination chest visit finishes (screen closed after any planned deposit).
     */
    public void onDestinationVisitFinished(int initialDestCount,
                                           int finalDestCount,
                                           int actualDeposited,
                                           int destFreeCapacityAfter,
                                           int liveInvCount,
                                           long nowMs) {
        int dest = Math.max(0, finalDestCount);
        int dep = Math.max(0, actualDeposited);
        lastKnownDestCount = dest;
        lastDestCheckMs = nowMs;
        lastProcessedExternalDestMs = Math.max(lastProcessedExternalDestMs, nowMs);
        consecutiveFailures = 0;

        boolean wasDelivery = (phase == Phase.DELIVER_DEST);
        if (wasDelivery || dep > 0) {
            metrics.recordDestDeliveryVisit(dep);
            int remainingDelta = Math.max(0, dep - visitCheckpointedDeposit);
            carriedForOrder = Math.max(0, carriedForOrder - remainingDelta);
        } else {
            metrics.recordDestCheck();
        }
        visitCheckpointedWithdraw = 0;
        visitCheckpointedDeposit = 0;

        // Case 1: Still carrying order items after checking/delivering at destination.
        if (carriedForOrder > 0) {
            if (rule.deficit(dest) == 0) {
                // Human topped up destination in transit (or we reached target with extra): return surplus to home!
                phase = Phase.RETURN_SURPLUS;
                lastReport = "'" + rule.destinationName() + "' at " + dest + "/" + rule.targetCount()
                        + "; returning " + carriedForOrder + " surplus " + rule.itemId() + " to '" + rule.sourceName() + "'";
                persist();
                return;
            }
            if (destFreeCapacityAfter <= 0) {
                // Destination chest is full: return undeliverable surplus and back off.
                phase = Phase.RETURN_SURPLUS;
                lastReport = "'" + rule.destinationName() + "' full at " + dest
                        + "; returning " + carriedForOrder + " to '" + rule.sourceName() + "'";
                persist();
                return;
            }
        }

        // Case 2: No carried items left.
        if (wasDelivery) {
            int remDeficit = rule.deficit(dest);
            if (remDeficit == 0) {
                metrics.recordCompletedRefill();
                consecutiveShortages = 0;
                partialShortage = false;
                clearOrder(liveInvCount);
                phase = Phase.IDLE_MONITORING;
                nextCheckMs = nowMs + rule.pollIntervalMs();
                lastReport = "refilled '" + rule.destinationName() + "' to " + dest + "/" + rule.targetCount();
                persist();
                return;
            }
            if (partialShortage) {
                // Source was already drained down to reserve on the withdrawal leg: report remaining deficit & back off
                // instead of walking back to source for an empty trip!
                metrics.recordShortageReport();
                consecutiveShortages++;
                long wait = shortageBackoff(consecutiveShortages);
                partialShortage = false;
                clearOrder(liveInvCount);
                phase = Phase.BACKOFF;
                backoffUntilMs = nowMs + wait;
                lastReport = "partial refill complete ('" + rule.destinationName() + "' at " + dest
                        + "/" + rule.targetCount() + ", remaining deficit=" + remDeficit
                        + "); '" + rule.sourceName() + "' at reserve " + rule.sourceReserve()
                        + "; backing off " + (wait / 1000L) + "s";
                persist();
                return;
            }
            if (destFreeCapacityAfter <= 0) {
                consecutiveShortages++;
                long wait = shortageBackoff(consecutiveShortages);
                clearOrder(liveInvCount);
                phase = Phase.BACKOFF;
                backoffUntilMs = nowMs + wait;
                lastReport = "'" + rule.destinationName() + "' full at " + dest + "; backing off " + (wait / 1000L) + "s";
                persist();
                return;
            }
            if (tripsInCurrentOrder >= maxTripsPerOrder) {
                long wait = shortageBackoff(1);
                clearOrder(liveInvCount);
                phase = Phase.BACKOFF;
                backoffUntilMs = nowMs + wait;
                lastReport = "trip cap (" + maxTripsPerOrder + ") reached for order; remaining deficit=" + remDeficit;
                persist();
                return;
            }
            // Need another withdrawal trip (e.g. inventory capacity was smaller than deficit, or more consumed in transit).
            plannedDeficit = remDeficit;
            tripsInCurrentOrder++;
            phase = Phase.WITHDRAW_SOURCE;
            lastReport = "'" + rule.destinationName() + "' at " + dest + "/" + rule.targetCount()
                    + "; fetching remaining " + remDeficit + " from '" + rule.sourceName() + "'";
            persist();
            return;
        }

        // Case 3: Finished a CHECK_DEST visit.
        if (rule.shouldTrigger(dest)) {
            startOrderIfNeeded(dest, liveInvCount);
            phase = Phase.WITHDRAW_SOURCE;
            lastReport = "'" + rule.destinationName() + "' has " + dest + " (<" + rule.triggerBelow()
                    + "); withdrawing " + plannedDeficit + " from '" + rule.sourceName() + "'";
        } else {
            // Strictly at or above threshold (e.g. 32 >= 32): MUST NOT trigger replenishment!
            consecutiveShortages = 0;
            clearOrder(liveInvCount);
            phase = Phase.IDLE_MONITORING;
            nextCheckMs = nowMs + rule.pollIntervalMs();
            lastReport = "'" + rule.destinationName() + "' has " + dest + " (>=" + rule.triggerBelow() + "); no refill needed";
        }
        persist();
    }

    /**
     * Called when the source chest screen opens with fresh live counts.
     * Bounds withdrawals strictly by {@code min(remainingDeficit, freeInvCapacity, sourceSurplusAboveReserve)}.
     */
    public ScreenPlan onSourceOpened(int freshSourceCount,
                                     int sourceFreeCapacity,
                                     int freeInvCapacity,
                                     int liveInvCount,
                                     long nowMs) {
        int src = Math.max(0, freshSourceCount);
        lastKnownSourceCount = src;
        lastSourceCheckMs = nowMs;

        if (phase == Phase.RETURN_SURPLUS) {
            int available = Math.min(carriedForOrder, Math.max(0, liveInvCount));
            int toReturn = Math.min(available, Math.max(0, sourceFreeCapacity));
            if (toReturn > 0) {
                return ScreenPlan.deposit(toReturn,
                        "returning " + toReturn + " surplus " + rule.itemId() + " to '" + rule.sourceName() + "'");
            }
            return ScreenPlan.none("cannot return surplus to '" + rule.sourceName() + "' (freeCap=" + sourceFreeCapacity + ")");
        }

        if (phase == Phase.WITHDRAW_SOURCE) {
            int remainingNeed = Math.max(0, plannedDeficit - carriedForOrder);
            int bound = rule.withdrawBound(remainingNeed, freeInvCapacity, src);
            if (bound > 0) {
                return ScreenPlan.withdraw(bound,
                        "withdrawing " + bound + " from '" + rule.sourceName() + "' (has " + src
                                + ", reserve " + rule.sourceReserve() + ", need " + remainingNeed + ")");
            }
            int surplus = rule.sourceSurplus(src);
            if (surplus <= 0) {
                return ScreenPlan.none("'" + rule.sourceName() + "' insufficient: has " + src
                        + " <= reserve " + rule.sourceReserve() + " (deficit " + remainingNeed + ")");
            }
            return ScreenPlan.none("no free player inventory capacity for " + rule.itemId());
        }

        metrics.recordUnnecessaryChestOpen();
        return ScreenPlan.none("unexpected source open in phase " + phase);
    }

    /**
     * Called when the source chest visit finishes (screen closed).
     */
    public void onSourceVisitFinished(int initialSourceCount,
                                      int finalSourceCount,
                                      int actualMoved,
                                      boolean wasReturn,
                                      int freeInvCapacityAfter,
                                      int liveInvCount,
                                      long nowMs) {
        int src = Math.max(0, finalSourceCount);
        int moved = Math.max(0, actualMoved);
        lastKnownSourceCount = src;
        lastSourceCheckMs = nowMs;
        consecutiveFailures = 0;

        if (wasReturn) {
            metrics.recordSourceReturnVisit(moved);
            int remainingDelta = Math.max(0, moved - visitCheckpointedDeposit);
            carriedForOrder = Math.max(0, carriedForOrder - remainingDelta);
            visitCheckpointedWithdraw = 0;
            visitCheckpointedDeposit = 0;
            if (carriedForOrder == 0) {
                clearOrder(liveInvCount);
                phase = Phase.IDLE_MONITORING;
                nextCheckMs = nowMs + rule.pollIntervalMs();
                lastReport = "returned " + moved + " surplus " + rule.itemId() + " to '" + rule.sourceName() + "'";
            } else {
                // Source chest was full and could not absorb all surplus: back off.
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    phase = Phase.FAILED;
                    lastReport = "failed returning " + carriedForOrder + " surplus: '" + rule.sourceName() + "' full";
                } else {
                    long wait = failureBackoff(consecutiveFailures);
                    phase = Phase.BACKOFF;
                    backoffUntilMs = nowMs + wait;
                    lastReport = "'" + rule.sourceName() + "' full with " + carriedForOrder
                            + " surplus remaining; backing off " + (wait / 1000L) + "s";
                }
            }
            persist();
            return;
        }

        // Withdrawal visit finished.
        metrics.recordSourceWithdrawVisit(moved);
        int remainingDelta = Math.max(0, moved - visitCheckpointedWithdraw);
        carriedForOrder += remainingDelta;
        visitCheckpointedWithdraw = 0;
        visitCheckpointedDeposit = 0;

        int remainingNeed = Math.max(0, plannedDeficit - carriedForOrder);
        int remainingSurplus = rule.sourceSurplus(src);

        if (moved == 0 && carriedForOrder == 0) {
            metrics.recordShortageReport();
            consecutiveShortages++;
            long wait = shortageBackoff(consecutiveShortages);
            clearOrder(liveInvCount);
            phase = Phase.BACKOFF;
            backoffUntilMs = nowMs + wait;
            if (remainingSurplus <= 0) {
                lastReport = "source '" + rule.sourceName() + "' insufficient (has " + src
                        + ", reserve " + rule.sourceReserve() + ", remaining deficit=" + remainingNeed
                        + "); backing off " + (wait / 1000L) + "s";
            } else {
                lastReport = "courier inventory full (freeCap=" + freeInvCapacityAfter
                        + ", remaining deficit=" + remainingNeed + "); backing off " + (wait / 1000L) + "s";
            }
            persist();
            return;
        }

        // We withdrew > 0 (or already had carried items): deliver to destination!
        partialShortage = (remainingNeed > 0 && remainingSurplus <= 0);
        phase = Phase.DELIVER_DEST;
        lastReport = "withdrew " + moved + " (carrying " + carriedForOrder + ") from '" + rule.sourceName()
                + "' (left " + src + "); delivering to '" + rule.destinationName() + "'";
        persist();
    }

    /**
     * Records a live withdrawal increment while the screen is still open so that even a sudden crash
     * before screen close keeps the journal's carried accounting monotonic.
     */
    public void checkpointCarriedDelta(int withdrawnDelta, int depositedDelta) {
        boolean changed = false;
        if (withdrawnDelta > 0) {
            carriedForOrder += withdrawnDelta;
            visitCheckpointedWithdraw += withdrawnDelta;
            changed = true;
        }
        if (depositedDelta > 0) {
            int actual = Math.min(carriedForOrder, depositedDelta);
            carriedForOrder -= actual;
            visitCheckpointedDeposit += actual;
            changed = true;
        }
        if (changed) {
            persist();
        }
    }

    /**
     * Bounds retries on walk/open/lease/transfer failures.
     */
    public void onVisitFailed(String reason, long nowMs) {
        visitCheckpointedWithdraw = 0;
        visitCheckpointedDeposit = 0;
        metrics.recordFailedAttempt();
        consecutiveFailures++;
        if (consecutiveFailures >= maxConsecutiveFailures) {
            phase = Phase.FAILED;
            lastReport = "failed after " + consecutiveFailures + " attempts: " + reason;
        } else {
            long wait = failureBackoff(consecutiveFailures);
            phase = Phase.BACKOFF;
            backoffUntilMs = nowMs + wait;
            lastReport = "attempt " + consecutiveFailures + "/" + maxConsecutiveFailures
                    + " failed (" + reason + "); backing off " + (wait / 1000L) + "s";
        }
        persist();
    }

    /** Honors explicit cancellation. */
    public void cancel(long nowMs) {
        phase = Phase.CANCELLED;
        lastReport = "cancelled";
        persist();
    }

    /** Clears persisted journal entry (used by fixture/test reset). */
    public void clearJournal() {
        if (journalStore != null) {
            journalStore.clear(rule.ruleKey());
        }
    }

    public String statusLine() {
        return "replenish[" + rule.describe() + "] phase=" + phase
                + " carried=" + carriedForOrder
                + " lastDest=" + (lastKnownDestCount < 0 ? "?" : lastKnownDestCount)
                + " lastSrc=" + (lastKnownSourceCount < 0 ? "?" : lastKnownSourceCount)
                + " | " + lastReport
                + " | " + metrics.summary();
    }

    private void startOrderIfNeeded(int destCount, int liveInvCount) {
        int def = rule.deficit(destCount);
        if (!activeOrder) {
            orderId++;
            activeOrder = true;
            baselinePersonalCount = Math.max(0, liveInvCount);
            carriedForOrder = 0;
            tripsInCurrentOrder = 1;
            partialShortage = false;
        }
        plannedDeficit = def;
    }

    private void clearOrder(int liveInvCount) {
        activeOrder = false;
        baselinePersonalCount = Math.max(0, liveInvCount);
        carriedForOrder = 0;
        plannedDeficit = 0;
        tripsInCurrentOrder = 0;
        partialShortage = false;
    }

    private long shortageBackoff(int count) {
        int idx = Math.min(Math.max(0, count - 1), shortageBackoffMs.length - 1);
        return shortageBackoffMs[idx];
    }

    private long failureBackoff(int count) {
        int idx = Math.min(Math.max(0, count - 1), failureBackoffMs.length - 1);
        return failureBackoffMs[idx];
    }

    private void persist() {
        if (journalStore == null) {
            return;
        }
        journalStore.save(new ReplenishmentJournal.Entry(
                rule.ruleKey(),
                orderId,
                activeOrder,
                phase.name(),
                baselinePersonalCount,
                carriedForOrder,
                plannedDeficit,
                partialShortage,
                lastKnownDestCount,
                lastDestCheckMs,
                lastKnownSourceCount,
                lastSourceCheckMs,
                nextCheckMs,
                backoffUntilMs,
                consecutiveShortages,
                consecutiveFailures,
                lastReport,
                metrics.snapshot()));
    }
}
