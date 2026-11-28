package adris.altoclef.compose;

import adris.altoclef.tasks.replenish.ContainerSlotSnapshot;
import adris.altoclef.tasks.replenish.ContainerTransferPlanner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java verified transfer state machine for replaying a single resolved {@link DemoStep}
 * ({@code WITHDRAW} or {@code DEPOSIT}) against fresh {@link ContainerSlotSnapshot} observations.
 * <p>
 * Reuses Phase 3 {@link ContainerTransferPlanner} click planning so every replay reads live slot order
 * and inventory state rather than replaying recorded slot indices or coordinates.
 * Verifies exact target conservation on both container and player sides, verifies zero unrelated
 * item mutations, bounds stalled/total clicks, and never reports false success.
 */
public final class DemoTransferState {

    public enum Status {
        PENDING,
        IN_PROGRESS,
        SUCCEEDED,
        FAILED
    }

    public enum FailureKind {
        NONE,
        MISSING_ITEM,
        INVENTORY_FULL,
        CONTAINER_FULL,
        UNRELATED_ITEM_CHANGED,
        STALLED,
        CLICK_LIMIT_EXCEEDED,
        CURSOR_STUCK,
        INCOMPLETE_TRANSFER
    }

    public record Decision(Status status,
                           FailureKind failureKind,
                           String message,
                           ContainerTransferPlanner.ClickAction nextClick,
                           int movedSoFar,
                           int targetCount) {
    }

    public static final int DEFAULT_MAX_STALLED_TICKS = 10;
    public static final int DEFAULT_MAX_CLICKS = 64;

    private final int oneBasedStepIndex;
    private final DemoStep step;
    private final int maxStackSize;
    private final int maxStalledTicks;
    private final int maxClicks;

    private Status status = Status.PENDING;
    private FailureKind failureKind = FailureKind.NONE;
    private String message = "";

    private boolean initialized = false;
    private int initialContainerCount = 0;
    private int initialPlayerCount = 0;
    private Map<String, Integer> baselineUnrelatedContainer = Map.of();
    private Map<String, Integer> baselineUnrelatedPlayer = Map.of();

    private int clicksIssued = 0;
    private int stalledTicks = 0;
    private int lastContainerCount = -1;
    private int lastPlayerCount = -1;
    private int lastCursorCount = -1;

    public DemoTransferState(int oneBasedStepIndex, DemoStep step, int maxStackSize) {
        this(oneBasedStepIndex, step, maxStackSize, DEFAULT_MAX_STALLED_TICKS, DEFAULT_MAX_CLICKS);
    }

    public DemoTransferState(int oneBasedStepIndex,
                             DemoStep step,
                             int maxStackSize,
                             int maxStalledTicks,
                             int maxClicks) {
        this.oneBasedStepIndex = oneBasedStepIndex;
        this.step = Objects.requireNonNull(step, "step");
        if (!step.resolved()) {
            throw new IllegalArgumentException("Cannot replay unresolved step #" + oneBasedStepIndex);
        }
        this.maxStackSize = Math.max(1, maxStackSize);
        this.maxStalledTicks = Math.max(1, maxStalledTicks);
        this.maxClicks = Math.max(1, maxClicks);
    }

    public Status status() {
        return status;
    }

    public FailureKind failureKind() {
        return failureKind;
    }

    public String message() {
        return message;
    }

    public int clicksIssued() {
        return clicksIssued;
    }

    /**
     * Evaluates the fresh container snapshot and returns the next single vanilla click to perform,
     * or a terminal {@link Status#SUCCEEDED} / {@link Status#FAILED} decision.
     */
    public Decision evaluate(ContainerSlotSnapshot snap) {
        Objects.requireNonNull(snap, "snap");
        if (status == Status.SUCCEEDED || status == Status.FAILED) {
            return new Decision(status, failureKind, message, null,
                    status == Status.SUCCEEDED ? step.count() : 0, step.count());
        }

        String itemId = step.itemId();
        boolean withdrawing = step.action() == DemoStep.Action.WITHDRAW;
        int liveContainer = snap.countInContainer(itemId);
        int livePlayer = snap.countInPlayer(itemId);

        if (!initialized) {
            initialized = true;
            status = Status.IN_PROGRESS;
            initialContainerCount = liveContainer;
            initialPlayerCount = livePlayer;
            baselineUnrelatedContainer = unrelatedCounts(snap, true, itemId);
            baselineUnrelatedPlayer = unrelatedCounts(snap, false, itemId);
        }

        // Safety invariant: never transfer or mutate unrelated items.
        String unrelatedDrift = detectUnrelatedDrift(snap, itemId);
        if (unrelatedDrift != null) {
            return fail(FailureKind.UNRELATED_ITEM_CHANGED,
                    stepPrefix() + ": unrelated item changed during transfer (" + unrelatedDrift + ")", 0);
        }

        int containerMoved = withdrawing
                ? Math.max(0, initialContainerCount - liveContainer)
                : Math.max(0, liveContainer - initialContainerCount);
        int playerMoved = withdrawing
                ? Math.max(0, livePlayer - initialPlayerCount)
                : Math.max(0, initialPlayerCount - livePlayer);
        // Both container and player inventory must confirm the movement.
        int confirmedMoved = Math.min(containerMoved, playerMoved);
        int remaining = Math.max(0, step.count() - confirmedMoved);

        // Completion check: exact count confirmed on BOTH sides AND cursor is empty!
        if (remaining == 0 && snap.cursorEmpty() && containerMoved == step.count() && playerMoved == step.count()) {
            status = Status.SUCCEEDED;
            failureKind = FailureKind.NONE;
            message = stepPrefix() + ": verified " + step.count() + "x " + itemId;
            return new Decision(status, failureKind, message, null, step.count(), step.count());
        }

        // Availability & capacity checks (accounting for any target items temporarily held on cursor mid-split).
        int cursorTarget = snap.cursorMatches(itemId) ? snap.cursorCount() : 0;
        if (withdrawing) {
            int availableInSource = liveContainer + cursorTarget;
            if (remaining > 0 && availableInSource < remaining) {
                if (!snap.cursorEmpty()) {
                    return cursorCleanupOrFail(snap, true, confirmedMoved,
                            FailureKind.MISSING_ITEM,
                            stepPrefix() + ": missing " + itemId + " in \"" + step.stationName()
                                    + "\" (found " + availableInSource + ", need " + remaining + ")");
                }
                return fail(FailureKind.MISSING_ITEM,
                        stepPrefix() + ": missing " + itemId + " in \"" + step.stationName()
                                + "\" (found " + availableInSource + ", need " + remaining + ")",
                        confirmedMoved);
            }
            int playerFree = snap.freeCapacityInPlayer(itemId, maxStackSize);
            if (remaining > 0 && playerFree < remaining) {
                if (!snap.cursorEmpty()) {
                    return cursorCleanupOrFail(snap, true, confirmedMoved,
                            FailureKind.INVENTORY_FULL,
                            stepPrefix() + ": insufficient player inventory space for " + remaining + "x " + itemId
                                    + " (free " + playerFree + ")");
                }
                return fail(FailureKind.INVENTORY_FULL,
                        stepPrefix() + ": insufficient player inventory space for " + remaining + "x " + itemId
                                + " (free " + playerFree + ")",
                        confirmedMoved);
            }
        } else {
            int availableInPlayer = livePlayer + cursorTarget;
            if (remaining > 0 && availableInPlayer < remaining) {
                if (!snap.cursorEmpty()) {
                    return cursorCleanupOrFail(snap, false, confirmedMoved,
                            FailureKind.MISSING_ITEM,
                            stepPrefix() + ": missing " + itemId + " in player inventory (found "
                                    + availableInPlayer + ", need " + remaining + ")");
                }
                return fail(FailureKind.MISSING_ITEM,
                        stepPrefix() + ": missing " + itemId + " in player inventory (found "
                                + availableInPlayer + ", need " + remaining + ")",
                        confirmedMoved);
            }
            int containerFree = snap.freeCapacityInContainer(itemId, maxStackSize);
            if (remaining > 0 && containerFree < remaining) {
                if (!snap.cursorEmpty()) {
                    return cursorCleanupOrFail(snap, false, confirmedMoved,
                            FailureKind.CONTAINER_FULL,
                            stepPrefix() + ": insufficient space in \"" + step.stationName() + "\" for "
                                    + remaining + "x " + itemId + " (free " + containerFree + ")");
                }
                return fail(FailureKind.CONTAINER_FULL,
                        stepPrefix() + ": insufficient space in \"" + step.stationName() + "\" for "
                                + remaining + "x " + itemId + " (free " + containerFree + ")",
                        confirmedMoved);
            }
        }

        // Stall detection between ticks after clicks.
        if (lastContainerCount >= 0) {
            if (liveContainer == lastContainerCount && livePlayer == lastPlayerCount && snap.cursorCount() == lastCursorCount) {
                if (++stalledTicks >= maxStalledTicks) {
                    return fail(FailureKind.STALLED,
                            stepPrefix() + ": container click stalled after " + stalledTicks + " ticks ("
                                    + confirmedMoved + "/" + step.count() + " moved)",
                            confirmedMoved);
                }
            } else {
                stalledTicks = 0;
            }
        }

        if (clicksIssued >= maxClicks) {
            return fail(FailureKind.CLICK_LIMIT_EXCEEDED,
                    stepPrefix() + ": exceeded bounded click limit (" + maxClicks + ")",
                    confirmedMoved);
        }

        // Plan next single ordinary vanilla click from fresh snapshot.
        ContainerTransferPlanner.ClickAction click = withdrawing
                ? ContainerTransferPlanner.planNextWithdrawClick(snap, itemId, maxStackSize, remaining, 0)
                : ContainerTransferPlanner.planNextDepositClick(snap, itemId, maxStackSize, remaining);

        if (click == null) {
            if (!snap.cursorEmpty()) {
                return cursorCleanupOrFail(snap, withdrawing, confirmedMoved,
                        FailureKind.CURSOR_STUCK,
                        stepPrefix() + ": cursor still held " + snap.cursorCount() + "x " + snap.cursorItemId());
            }
            // Planner returned null before confirmedMoved reached step.count() — never report false success!
            return fail(FailureKind.INCOMPLETE_TRANSFER,
                    stepPrefix() + ": transfer ended incomplete (" + confirmedMoved + "/" + step.count()
                            + " verified; containerDelta=" + containerMoved + ", playerDelta=" + playerMoved + ")",
                    confirmedMoved);
        }

        return new Decision(Status.IN_PROGRESS, FailureKind.NONE,
                stepPrefix() + ": " + confirmedMoved + "/" + step.count() + " (" + click.reason() + ")",
                click, confirmedMoved, step.count());
    }

    /** Records that the planned click was actually dispatched on this snapshot. */
    public void recordClickDispatched(ContainerSlotSnapshot snap) {
        clicksIssued++;
        lastContainerCount = snap.countInContainer(step.itemId());
        lastPlayerCount = snap.countInPlayer(step.itemId());
        lastCursorCount = snap.cursorCount();
    }

    private Decision cursorCleanupOrFail(ContainerSlotSnapshot snap,
                                         boolean preferContainer,
                                         int confirmedMoved,
                                         FailureKind fallbackKind,
                                         String fallbackMsg) {
        ContainerTransferPlanner.ClickAction cleanup = ContainerTransferPlanner.planCursorCleanupClick(snap, preferContainer);
        if (cleanup != null && clicksIssued < maxClicks) {
            return new Decision(Status.IN_PROGRESS, FailureKind.NONE,
                    stepPrefix() + ": returning cursor stack before failing",
                    cleanup, confirmedMoved, step.count());
        }
        return fail(fallbackKind, fallbackMsg, confirmedMoved);
    }

    private Decision fail(FailureKind kind, String msg, int moved) {
        status = Status.FAILED;
        failureKind = kind;
        message = msg;
        return new Decision(status, failureKind, message, null, moved, step.count());
    }

    private String stepPrefix() {
        return "Step " + oneBasedStepIndex + " (" + step.describeShort() + ")";
    }

    private String detectUnrelatedDrift(ContainerSlotSnapshot snap, String targetItem) {
        if (!snap.cursorEmpty() && !snap.cursorMatches(targetItem)) {
            return "cursor holds unrelated " + snap.cursorItemId() + " x" + snap.cursorCount();
        }
        Map<String, Integer> nowC = unrelatedCounts(snap, true, targetItem);
        if (!nowC.equals(baselineUnrelatedContainer)) {
            return "container unrelated items changed from " + baselineUnrelatedContainer + " to " + nowC;
        }
        Map<String, Integer> nowP = unrelatedCounts(snap, false, targetItem);
        if (!nowP.equals(baselineUnrelatedPlayer)) {
            return "player unrelated items changed from " + baselineUnrelatedPlayer + " to " + nowP;
        }
        return null;
    }

    private static Map<String, Integer> unrelatedCounts(ContainerSlotSnapshot snap, boolean container, String targetItem) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ContainerSlotSnapshot.SlotEntry s : snap.slots()) {
            if (s.container() == container && !s.isEmpty() && !s.matches(targetItem)) {
                out.merge(s.itemId(), s.count(), Integer::sum);
            }
        }
        return out;
    }
}
