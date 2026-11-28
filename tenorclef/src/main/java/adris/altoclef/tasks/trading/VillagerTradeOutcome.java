package adris.altoclef.tasks.trading;

import adris.altoclef.tasksystem.FailureReason;

import java.util.Objects;

/**
 * Specific, actionable outcome reported by a villager trade task to its parent goal.
 */
public final class VillagerTradeOutcome {

    public enum Status {
        RUNNING,
        SUCCESS,
        OVER_BUDGET,
        SOLD_OUT,
        OFFER_UNAVAILABLE,
        MISSING_INPUTS,
        VILLAGER_UNAVAILABLE,
        INVENTORY_FULL,
        INTERACTION_FAILED,
        CANCELLED
    }

    private final Status status;
    private final FailureReason failureReason;
    private final String detail;
    private final int targetItemsReceived;
    private final int emeraldsSpent;
    private final int emeraldCap;
    private final int cheapestSeenEmeraldCost;

    public VillagerTradeOutcome(Status status,
                                FailureReason failureReason,
                                String detail,
                                int targetItemsReceived,
                                int emeraldsSpent,
                                int emeraldCap,
                                int cheapestSeenEmeraldCost) {
        this.status = Objects.requireNonNull(status, "status");
        this.failureReason = failureReason;
        this.detail = detail == null ? "" : detail;
        this.targetItemsReceived = Math.max(0, targetItemsReceived);
        this.emeraldsSpent = Math.max(0, emeraldsSpent);
        this.emeraldCap = emeraldCap;
        this.cheapestSeenEmeraldCost = cheapestSeenEmeraldCost;
    }

    public static VillagerTradeOutcome running(int emeraldCap) {
        return new VillagerTradeOutcome(Status.RUNNING, null, "running", 0, 0, emeraldCap, -1);
    }

    public static VillagerTradeOutcome success(int received, int emeraldsSpent, int emeraldCap, String detail) {
        return new VillagerTradeOutcome(Status.SUCCESS, null, detail, received, emeraldsSpent, emeraldCap, -1);
    }

    public static VillagerTradeOutcome failure(Status status,
                                               FailureReason reason,
                                               String detail,
                                               int received,
                                               int emeraldsSpent,
                                               int emeraldCap,
                                               int cheapestSeenEmeraldCost) {
        return new VillagerTradeOutcome(status, reason, detail, received, emeraldsSpent, emeraldCap, cheapestSeenEmeraldCost);
    }

    public static VillagerTradeOutcome cancelled(int received, int emeraldsSpent, int emeraldCap, String detail) {
        return new VillagerTradeOutcome(Status.CANCELLED, null, detail, received, emeraldsSpent, emeraldCap, -1);
    }

    public Status status() {
        return status;
    }

    public FailureReason failureReason() {
        return failureReason;
    }

    public String detail() {
        return detail;
    }

    public int targetItemsReceived() {
        return targetItemsReceived;
    }

    public int emeraldsSpent() {
        return emeraldsSpent;
    }

    public int emeraldCap() {
        return emeraldCap;
    }

    public int cheapestSeenEmeraldCost() {
        return cheapestSeenEmeraldCost;
    }

    public boolean isTerminal() {
        return status != Status.RUNNING;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isFailure() {
        return status != Status.RUNNING && status != Status.SUCCESS && status != Status.CANCELLED;
    }

    @Override
    public String toString() {
        return status + (detail.isEmpty() ? "" : ": " + detail)
                + " [received=" + targetItemsReceived
                + ", emeraldsSpent=" + emeraldsSpent
                + (emeraldCap >= 0 ? "/" + emeraldCap : "")
                + (cheapestSeenEmeraldCost >= 0 ? ", cheapestOffer=" + cheapestSeenEmeraldCost : "")
                + "]";
    }
}
