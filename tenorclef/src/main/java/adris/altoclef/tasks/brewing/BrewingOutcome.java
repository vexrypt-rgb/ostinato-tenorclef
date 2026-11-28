package adris.altoclef.tasks.brewing;

import adris.altoclef.tasksystem.FailureReason;

import java.util.Objects;

/**
 * Structured, actionable outcome reported by {@link BrewPotionTask} to callers and tests.
 */
public final class BrewingOutcome {

    public enum Status {
        RUNNING,
        SUCCESS,
        STAND_OCCUPIED,
        MISSING_FUEL,
        MISSING_SUPPLIES,
        STAND_UNAVAILABLE,
        INVENTORY_FULL,
        INTERACTION_FAILED,
        CANCELLED
    }

    private final Status status;
    private final FailureReason failureReason;
    private final String detail;
    private final int producedCount;
    private final int requestedCount;
    private final int stage1Loads;
    private final int stage2Loads;
    private final int fuelLoads;

    public BrewingOutcome(Status status,
                          FailureReason failureReason,
                          String detail,
                          int producedCount,
                          int requestedCount,
                          int stage1Loads,
                          int stage2Loads,
                          int fuelLoads) {
        this.status = Objects.requireNonNull(status, "status");
        this.failureReason = failureReason;
        this.detail = detail == null ? "" : detail;
        this.producedCount = Math.max(0, producedCount);
        this.requestedCount = Math.max(0, requestedCount);
        this.stage1Loads = Math.max(0, stage1Loads);
        this.stage2Loads = Math.max(0, stage2Loads);
        this.fuelLoads = Math.max(0, fuelLoads);
    }

    public static BrewingOutcome running(int requestedCount) {
        return new BrewingOutcome(Status.RUNNING, null, "running", 0, requestedCount, 0, 0, 0);
    }

    public static BrewingOutcome success(int producedCount,
                                         int requestedCount,
                                         int stage1Loads,
                                         int stage2Loads,
                                         int fuelLoads,
                                         String detail) {
        return new BrewingOutcome(Status.SUCCESS, null, detail, producedCount, requestedCount,
                stage1Loads, stage2Loads, fuelLoads);
    }

    public static BrewingOutcome failure(Status status,
                                         FailureReason reason,
                                         int producedCount,
                                         int requestedCount,
                                         int stage1Loads,
                                         int stage2Loads,
                                         int fuelLoads,
                                         String detail) {
        return new BrewingOutcome(status, reason, detail, producedCount, requestedCount,
                stage1Loads, stage2Loads, fuelLoads);
    }

    public static BrewingOutcome cancelled(int producedCount,
                                           int requestedCount,
                                           int stage1Loads,
                                           int stage2Loads,
                                           int fuelLoads,
                                           String detail) {
        return new BrewingOutcome(Status.CANCELLED, null, detail, producedCount, requestedCount,
                stage1Loads, stage2Loads, fuelLoads);
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

    public int producedCount() {
        return producedCount;
    }

    public int requestedCount() {
        return requestedCount;
    }

    public int stage1Loads() {
        return stage1Loads;
    }

    public int stage2Loads() {
        return stage2Loads;
    }

    public int fuelLoads() {
        return fuelLoads;
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
                + " [produced=" + producedCount + "/" + requestedCount
                + ", stage1Loads=" + stage1Loads
                + ", stage2Loads=" + stage2Loads
                + ", fuelLoads=" + fuelLoads + "]";
    }
}
