package adris.altoclef.tasks.husbandry;

import adris.altoclef.tasksystem.FailureReason;

import java.util.Objects;

/**
 * Structured, actionable outcome reported by {@link BreedCowsTask} to callers and tests.
 */
public final class HusbandryOutcome {

    public enum Status {
        RUNNING,
        SUCCESS,
        FEED_SHORTAGE,
        PEN_UNAVAILABLE,
        NO_BREEDING_PAIR,
        INTERACTION_FAILED,
        CANCELLED
    }

    private final Status status;
    private final FailureReason failureReason;
    private final String detail;
    private final int observedCount;
    private final int targetCount;
    private final int birthsObserved;
    private final int wheatConsumed;

    public HusbandryOutcome(Status status,
                            FailureReason failureReason,
                            String detail,
                            int observedCount,
                            int targetCount,
                            int birthsObserved,
                            int wheatConsumed) {
        this.status = Objects.requireNonNull(status, "status");
        this.failureReason = failureReason;
        this.detail = detail == null ? "" : detail;
        this.observedCount = Math.max(0, observedCount);
        this.targetCount = Math.max(0, targetCount);
        this.birthsObserved = Math.max(0, birthsObserved);
        this.wheatConsumed = Math.max(0, wheatConsumed);
    }

    public static HusbandryOutcome running(int observedCount, int targetCount, int birthsObserved, int wheatConsumed) {
        return new HusbandryOutcome(Status.RUNNING, null, "running",
                observedCount, targetCount, birthsObserved, wheatConsumed);
    }

    public static HusbandryOutcome success(int observedCount, int targetCount, int birthsObserved, int wheatConsumed, String detail) {
        return new HusbandryOutcome(Status.SUCCESS, null, detail,
                observedCount, targetCount, birthsObserved, wheatConsumed);
    }

    public static HusbandryOutcome failure(Status status, FailureReason reason,
                                           int observedCount, int targetCount, int birthsObserved, int wheatConsumed,
                                           String detail) {
        return new HusbandryOutcome(status, reason, detail,
                observedCount, targetCount, birthsObserved, wheatConsumed);
    }

    public static HusbandryOutcome cancelled(int observedCount, int targetCount, int birthsObserved, int wheatConsumed, String detail) {
        return new HusbandryOutcome(Status.CANCELLED, null, detail,
                observedCount, targetCount, birthsObserved, wheatConsumed);
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

    public int observedCount() {
        return observedCount;
    }

    public int targetCount() {
        return targetCount;
    }

    public int birthsObserved() {
        return birthsObserved;
    }

    public int wheatConsumed() {
        return wheatConsumed;
    }

    public boolean isTerminal() {
        return status != Status.RUNNING;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isFailure() {
        return isTerminal() && status != Status.SUCCESS && status != Status.CANCELLED;
    }

    @Override
    public String toString() {
        return status + "(count=" + observedCount + "/" + targetCount
                + ", births=" + birthsObserved
                + ", wheat=" + wheatConsumed
                + (detail.isEmpty() ? "" : ", " + detail) + ")";
    }
}
