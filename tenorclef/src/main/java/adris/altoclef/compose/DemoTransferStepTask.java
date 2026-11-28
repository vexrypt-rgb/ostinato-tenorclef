package adris.altoclef.compose;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.replenish.ContainerSlotSnapshot;
import adris.altoclef.tasks.replenish.ContainerTransferPlanner;
import adris.altoclef.tasks.speedrun.testrun2.McCompat;
import adris.altoclef.tasksystem.FailureReason;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.ChestSlot;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.input.Input;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Objects;
import java.util.Optional;

/**
 * Verified single-step replay task for one resolved {@link DemoStep} ({@code WITHDRAW} or {@code DEPOSIT})
 * at a bound container position.
 * <p>
 * Uses ordinary player movement ({@link InteractWithBlockTask}) and client-visible container screens only,
 * reading fresh slot order and inventory state via {@link DemoContainerSnapshots} and planning clicks via
 * {@link DemoTransferState} / {@link ContainerTransferPlanner}.
 */
public final class DemoTransferStepTask extends Task {

    private static final int SCREEN_SYNC_TICKS = 3;
    private static final int CLICK_COOLDOWN_TICKS = 2;
    private static final int MAX_OPEN_ATTEMPTS = 3;
    private static final long VISIT_TIMEOUT_MS = 30_000L;

    private final int oneBasedStepIndex;
    private final DemoStep step;
    private final BlockPos targetPos;
    private final Dimension dimension;
    private final Item targetItem;
    private final int maxStackSize;

    private DemoTransferState transferState;
    private boolean succeeded = false;
    private boolean failed = false;
    private String failureMessage = "";

    private int openAttempts = 0;
    private long visitStartedMs = 0L;
    private boolean screenObservedOpen = false;
    private int screenOpenTicks = 0;
    private int clickCooldown = 0;
    private boolean behaviourPushed = false;

    public DemoTransferStepTask(int oneBasedStepIndex,
                                DemoStep step,
                                BlockPos targetPos,
                                Dimension dimension) {
        this.oneBasedStepIndex = oneBasedStepIndex;
        this.step = Objects.requireNonNull(step, "step");
        if (!step.resolved()) {
            throw new IllegalArgumentException("Cannot replay unresolved step #" + oneBasedStepIndex);
        }
        this.targetPos = Objects.requireNonNull(targetPos, "targetPos").toImmutable();
        this.dimension = dimension == null ? Dimension.OVERWORLD : dimension;
        this.targetItem = McCompat.item(step.itemId());
        if (this.targetItem == null) {
            throw new IllegalArgumentException("Unknown Minecraft item for step #" + oneBasedStepIndex + ": " + step.itemId());
        }
        this.maxStackSize = Math.max(1, new ItemStack(this.targetItem).getMaxCount());
    }

    public int oneBasedStepIndex() {
        return oneBasedStepIndex;
    }

    public DemoStep step() {
        return step;
    }

    public BlockPos targetPos() {
        return targetPos;
    }

    public boolean isSucceeded() {
        return succeeded;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null && mod.getBehaviour() != null) {
            mod.getBehaviour().push();
            behaviourPushed = true;
            mod.getBehaviour().addProtectedItems(targetItem);
            mod.getBehaviour().avoidBlockBreaking(pos -> pos.equals(targetPos));
        }
        transferState = new DemoTransferState(oneBasedStepIndex, step, maxStackSize);
        succeeded = false;
        failed = false;
        failureMessage = "";
        openAttempts = 0;
        visitStartedMs = System.currentTimeMillis();
        screenObservedOpen = false;
        screenOpenTicks = 0;
        clickCooldown = 0;
    }

    @Override
    protected Task onTick() {
        if (succeeded || failed) {
            return null;
        }
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getPlayer() == null || mod.getWorld() == null) {
            return null;
        }
        if (WorldHelper.getCurrentDimension() != dimension) {
            failStep(mod, FailureReason.PRECONDITION_FAILED,
                    stepPrefix() + ": wrong dimension (need " + dimension + ", currently " + WorldHelper.getCurrentDimension() + ")");
            return null;
        }

        long now = System.currentTimeMillis();
        if (visitStartedMs == 0L) {
            visitStartedMs = now;
        }

        PlayerEntity player = mod.getPlayer();
        ScreenHandler handler = player.currentScreenHandler;
        boolean supportedOpen = DemoContainerSnapshots.isSupportedContainerHandler(handler);
        Optional<BlockPos> lastInteracted = mod.getItemStorage().getLastBlockPosInteraction();
        boolean matchingOpen = supportedOpen
                && (lastInteracted.isEmpty() || isSameOrAdjacent(lastInteracted.get(), targetPos));

        if (matchingOpen) {
            screenObservedOpen = true;
            return tickOpenContainer(mod, handler);
        }

        // Container screen closed unexpectedly during this step.
        if (screenObservedOpen && !matchingOpen) {
            if (transferState.clicksIssued() > 0) {
                failStep(mod, FailureReason.WORLD_CHANGED,
                        stepPrefix() + ": container \"" + step.stationName() + "\" closed mid-transfer");
                return null;
            }
            if (++openAttempts >= MAX_OPEN_ATTEMPTS) {
                failStep(mod, FailureReason.TARGET_UNAVAILABLE,
                        stepPrefix() + ": container \"" + step.stationName() + "\" closed before syncing (" + openAttempts + " attempts)");
                return null;
            }
            screenObservedOpen = false;
            screenOpenTicks = 0;
            visitStartedMs = now;
        }

        if (now - visitStartedMs > VISIT_TIMEOUT_MS) {
            if (transferState.clicksIssued() == 0 && ++openAttempts < MAX_OPEN_ATTEMPTS) {
                StorageHelper.closeScreen();
                visitStartedMs = now;
                screenObservedOpen = false;
                screenOpenTicks = 0;
            } else {
                failStep(mod, FailureReason.TIMEOUT,
                        stepPrefix() + ": timed out reaching/opening \"" + step.stationName() + "\" at " + targetPos.toShortString());
                return null;
            }
        }

        // Close any unrelated open screen before walking to targetPos.
        if (!StorageHelper.isPlayerInventoryOpen()) {
            StorageHelper.closeScreen();
            setDebugState("Closing unrelated screen before " + step.stationName());
            return null;
        }

        // Verify bound container block exists when chunk is loaded.
        if (mod.getChunkTracker().isChunkLoaded(targetPos)) {
            Block block = mod.getWorld().getBlockState(targetPos).getBlock();
            if (ContainerType.getFromBlock(block) == ContainerType.EMPTY) {
                failStep(mod, FailureReason.TARGET_UNAVAILABLE,
                        stepPrefix() + ": no storage container at \"" + step.stationName() + "\" (" + targetPos.toShortString() + ")");
                return null;
            }
            if (WorldHelper.isChest(targetPos) && WorldHelper.isSolidBlock(targetPos.up())) {
                failStep(mod, FailureReason.TARGET_UNAVAILABLE,
                        stepPrefix() + ": chest at \"" + step.stationName() + "\" (" + targetPos.toShortString() + ") is blocked from above");
                return null;
            }
        }

        setDebugState("Opening \"" + step.stationName() + "\" for " + step.describeShort());
        Direction face = openFace(mod, targetPos);
        if (face != null) {
            return new InteractWithBlockTask(ItemTarget.EMPTY, face, targetPos, Input.CLICK_RIGHT, false, false);
        }
        return new InteractWithBlockTask(targetPos);
    }

    private Task tickOpenContainer(AltoClef mod, ScreenHandler handler) {
        if (++screenOpenTicks < SCREEN_SYNC_TICKS) {
            setDebugState("Syncing \"" + step.stationName() + "\" screen");
            return null;
        }
        ContainerSlotSnapshot snap = DemoContainerSnapshots.capture(handler);
        DemoTransferState.Decision decision = transferState.evaluate(snap);

        switch (decision.status()) {
            case SUCCEEDED -> {
                StorageHelper.closeScreen();
                succeeded = true;
                setDebugState("Verified " + step.describeShort());
                Debug.logInternal("TEACH REPLAY: " + decision.message());
                succeed();
                return null;
            }
            case FAILED -> {
                cleanupCursorBeforeClose(mod, handler);
                StorageHelper.closeScreen();
                failStep(mod, mapFailureReason(decision.failureKind()), decision.message());
                return null;
            }
            case IN_PROGRESS -> {
                setDebugState(decision.message());
                if (decision.nextClick() == null) {
                    return null;
                }
                if (clickCooldown > 0) {
                    clickCooldown--;
                    return null;
                }
                if (!mod.getSlotHandler().canDoSlotAction()) {
                    return null;
                }
                executeClick(mod, handler, decision.nextClick());
                transferState.recordClickDispatched(snap);
                clickCooldown = CLICK_COOLDOWN_TICKS;
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private void executeClick(AltoClef mod, ScreenHandler handler, ContainerTransferPlanner.ClickAction click) {
        int containerSlots = Math.max(0, handler.slots.size() - 36);
        // ChestSlot with inventory=false passes click.windowSlot() verbatim to SlotHandler.clickWindowSlot.
        Slot slot = new ChestSlot(click.windowSlot(), containerSlots > 27, false);
        SlotActionType actionType = click.kind() == ContainerTransferPlanner.ClickKind.QUICK_MOVE
                ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP;
        mod.getSlotHandler().clickSlot(slot, click.button(), actionType);
    }

    private void cleanupCursorBeforeClose(AltoClef mod, ScreenHandler handler) {
        if (mod == null || mod.getPlayer() == null || !DemoContainerSnapshots.isSupportedContainerHandler(handler)) {
            return;
        }
        if (StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return;
        }
        ContainerSlotSnapshot snap = DemoContainerSnapshots.capture(handler);
        boolean preferContainer = step.action() == DemoStep.Action.WITHDRAW;
        ContainerTransferPlanner.ClickAction cleanup = ContainerTransferPlanner.planCursorCleanupClick(snap, preferContainer);
        if (cleanup != null) {
            SlotActionType type = cleanup.kind() == ContainerTransferPlanner.ClickKind.QUICK_MOVE
                    ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP;
            BotClientBridge.clickSlot(handler.syncId, cleanup.windowSlot(), cleanup.button(), type, mod.getPlayer());
        }
    }

    private void failStep(AltoClef mod, FailureReason reason, String msg) {
        failed = true;
        failureMessage = msg;
        setDebugState("FAILED: " + msg);
        Debug.logWarning("TEACH REPLAY FAILED: " + msg);
        if (mod != null && mod.getClientBaritone() != null) {
            try {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            } catch (RuntimeException ignored) {
            }
        }
        fail(reason, msg, false);
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null) {
            if (behaviourPushed && mod.getBehaviour() != null) {
                mod.getBehaviour().pop();
                behaviourPushed = false;
            }
            if (mod.getPlayer() != null && !StorageHelper.isPlayerInventoryOpen()) {
                cleanupCursorBeforeClose(mod, mod.getPlayer().currentScreenHandler);
                StorageHelper.closeScreen();
            }
            if (mod.getClientBaritone() != null) {
                try {
                    mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    @Override
    public boolean isFinished() {
        // Strictly true on verified success only so parent sequences never skip a failed step.
        return succeeded;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DemoTransferStepTask that) {
            return oneBasedStepIndex == that.oneBasedStepIndex
                    && step.equals(that.step)
                    && targetPos.equals(that.targetPos)
                    && dimension == that.dimension;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "DemoStep#" + oneBasedStepIndex + " " + step.describeShort() + " @" + targetPos.toShortString();
    }

    private String stepPrefix() {
        return "Step " + oneBasedStepIndex + " (" + step.describeShort() + ")";
    }

    private static FailureReason mapFailureReason(DemoTransferState.FailureKind kind) {
        return switch (kind) {
            case MISSING_ITEM -> FailureReason.RESOURCE_MISSING;
            case INVENTORY_FULL, CONTAINER_FULL -> FailureReason.INVENTORY_FULL;
            case STALLED, CLICK_LIMIT_EXCEEDED -> FailureReason.TIMEOUT;
            case UNRELATED_ITEM_CHANGED -> FailureReason.WORLD_CHANGED;
            default -> FailureReason.TARGET_UNAVAILABLE;
        };
    }

    private static boolean isSameOrAdjacent(BlockPos a, BlockPos b) {
        if (a == null || b == null) return false;
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ()) <= 1;
    }

    private static Direction openFace(AltoClef mod, BlockPos pos) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos n = pos.offset(d);
            if (isStandOpen(mod, n) && isStandOpen(mod, n.up()) && !isStandOpen(mod, n.down())) return d;
            if (isStandOpen(mod, n) && isStandOpen(mod, n.down()) && !isStandOpen(mod, n.down(2))) return d;
        }
        if (isStandOpen(mod, pos.down()) && isStandOpen(mod, pos.down(2))) return Direction.DOWN;
        return null;
    }

    private static boolean isStandOpen(AltoClef mod, BlockPos p) {
        BlockState s = mod.getWorld().getBlockState(p);
        return s.isAir() || (s.getCollisionShape(mod.getWorld(), p).isEmpty() && s.getFluidState().isEmpty()
                && !(s.getBlock() instanceof AbstractSignBlock));
    }
}
