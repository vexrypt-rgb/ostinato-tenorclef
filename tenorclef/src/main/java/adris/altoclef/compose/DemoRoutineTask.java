package adris.altoclef.compose;

import adris.altoclef.tasksystem.FailureReason;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskFailure;
import adris.altoclef.tasksystem.TaskResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Executes a resolved {@link DemoRoutine}'s semantic transfer steps in order.
 * Stops immediately on the first failed step, preserving the exact unsatisfied step index and diagnostic
 * for both {@link CompositionRun} and {@link DemoService}.
 */
public final class DemoRoutineTask extends Task {

    private final DemoRoutine routine;
    private final List<DemoTransferStepTask> stepTasks;

    private int index = 0;
    private boolean succeeded = false;
    private boolean terminalFailed = false;
    private int failedStepIndex = 0;
    private String failureMessage = "";

    public DemoRoutineTask(DemoRoutine routine, List<DemoTransferStepTask> stepTasks) {
        this.routine = Objects.requireNonNull(routine, "routine");
        this.stepTasks = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(stepTasks, "stepTasks")));
        if (this.stepTasks.isEmpty()) {
            throw new IllegalArgumentException("DemoRoutineTask requires at least one step");
        }
    }

    public DemoRoutine routine() {
        return routine;
    }

    public int currentStepOneBased() {
        return Math.min(index + 1, stepTasks.size());
    }

    public int failedStepIndex() {
        return failedStepIndex;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public boolean isSucceeded() {
        return succeeded;
    }

    public boolean isTerminalFailed() {
        return terminalFailed;
    }

    @Override
    protected void onStart() {
        index = 0;
        succeeded = false;
        terminalFailed = false;
        failedStepIndex = 0;
        failureMessage = "";
        for (DemoTransferStepTask stepTask : stepTasks) {
            stepTask.reset();
        }
    }

    @Override
    protected Task onTick() {
        if (succeeded || terminalFailed) {
            return null;
        }
        if (index >= stepTasks.size()) {
            succeeded = true;
            succeed();
            return null;
        }
        DemoTransferStepTask cur = stepTasks.get(index);
        if (cur.isSucceeded()) {
            index++;
            if (index >= stepTasks.size()) {
                succeeded = true;
                setDebugState("Completed " + routine.name() + " (" + stepTasks.size() + "/" + stepTasks.size() + ")");
                succeed();
                return null;
            }
            return stepTasks.get(index);
        }
        if (cur.isFailed() || cur.getExplicitResult() == TaskResult.FAILURE) {
            failedStepIndex = cur.oneBasedStepIndex();
            TaskFailure childFailure = cur.getLastFailure();
            failureMessage = !cur.getFailureMessage().isEmpty()
                    ? cur.getFailureMessage()
                    : (childFailure != null ? childFailure.toString() : "Step " + failedStepIndex + " failed");
            terminalFailed = true;
            setDebugState("FAILED at step " + failedStepIndex + ": " + failureMessage);
            if (childFailure != null) {
                fail(childFailure);
            } else {
                fail(FailureReason.UNKNOWN, failureMessage, false);
            }
            return null;
        }
        setDebugState("Step " + (index + 1) + "/" + stepTasks.size() + ": " + cur.step().describeShort());
        return cur;
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    public boolean isFinished() {
        return succeeded || terminalFailed;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "DemoRoutine \"" + routine.name() + "\" [" + currentStepOneBased() + "/" + stepTasks.size() + "]";
    }
}
