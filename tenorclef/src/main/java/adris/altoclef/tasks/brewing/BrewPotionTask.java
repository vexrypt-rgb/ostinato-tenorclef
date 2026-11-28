package adris.altoclef.tasks.brewing;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.FailureReason;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskResult;
import adris.altoclef.util.ItemComponentReader;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.BrewingStandSlot;
import adris.altoclef.util.slots.ChestSlot;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.input.Input;
//#if MC < 260000
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
//#else
//$$ import net.minecraft.world.level.block.BarrelBlock;
//$$ import net.minecraft.world.level.block.Block;
//$$ import net.minecraft.world.level.block.state.BlockState;
//$$ import net.minecraft.world.level.block.Blocks;
//$$ import net.minecraft.world.level.block.ChestBlock;
//$$ import net.minecraft.world.level.block.ShulkerBoxBlock;
//#endif
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
//#if MC < 260000
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
//#else
//$$ import net.minecraft.world.inventory.BrewingStandMenu;
//$$ import net.minecraft.world.inventory.ChestMenu;
//$$ import net.minecraft.world.inventory.AbstractContainerMenu;
//$$ import net.minecraft.world.inventory.ShulkerBoxMenu;
//#endif
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * High-level TenorClef task that brews potions at a brewing stand using stocked supplies beside it.
 * <p>
 * Architectural & safety invariants:
 * <ul>
 *     <li>Acts strictly as an ordinary player via normal movement, block interaction, and vanilla
 *         {@link BrewingStandScreenHandler} / container screens.</li>
 *     <li>Inspects the brewing stand FIRST; if unexpected items or another batch occupy it, leaves
 *         all slots untouched, closes the screen, and fails with {@link BrewingOutcome.Status#STAND_OCCUPIED}.</li>
 *     <li>Advances stages ONLY after observing actual intermediate/final potion components in the live
 *         stand/inventory — never relies on timers or click success alone.</li>
 *     <li>Resumes cleanly from the stand's actual contents after interruption without re-consuming
 *         stage-1 ingredients (e.g., Nether Wart) when Awkward Potions are already brewed.</li>
 *     <li>Verifies fuel availability across stand/inventory/nearby supplies before withdrawing or loading
 *         items so missing fuel fails cleanly without consuming unrelated items.</li>
 * </ul>
 */
public final class BrewPotionTask extends Task {

    private static final int SCREEN_SYNC_TICKS = 3;
    private static final int CLICK_COOLDOWN_TICKS = 3;
    private static final long OPEN_ATTEMPT_TIMEOUT_MS = 20_000L;
    private static final int MAX_FUEL_CHARGE_WAIT_TICKS = 40;

    private enum Mode {
        AT_STAND,
        FETCH_SUPPLIES
    }

    private final BrewRequest request;
    private final BlockPos standPos;
    private final BrewingSession session;

    private boolean behaviourPushed = false;
    private boolean suspending = false;
    private boolean cancelRequested = false;

    // Transient interaction state (safely reset on interrupt/reset; session persists across resumes).
    private Mode mode = Mode.AT_STAND;
    private long openStartedMs = 0L;
    private int screenOpenTicks = 0;
    private int clickCooldown = 0;
    private String lastClickSignature = null;
    private int stalledClicks = 0;

    private BrewingStandSnapshot lastObservedStand = null;
    private int lastBrewTicks = -1;
    private int brewStallTicks = 0;
    private int brewStartWaitTicks = 0;
    private int fuelChargeWaitTicks = 0;

    // Supply-container visit state.
    private List<BlockPos> nearbyContainers = new ArrayList<>();
    private BlockPos activeSupplyPos = null;
    private boolean containerInspectedThisVisit = false;
    private boolean supplyAuthorized = false;
    private int lastOriginChestSlot = -1;
    private BrewingPolicy.SupplyNeeds pendingNeeds = BrewingPolicy.SupplyNeeds.NONE;

    public BrewPotionTask(BrewRequest request, BlockPos standPos) {
        this.request = Objects.requireNonNull(request, "request");
        this.standPos = Objects.requireNonNull(standPos, "standPos").toImmutable();
        this.session = new BrewingSession(request);
    }

    public BrewRequest getRequest() {
        return request;
    }

    public BlockPos getStandPos() {
        return standPos;
    }

    public BrewingSession getSession() {
        return session;
    }

    public BrewingOutcome getOutcome() {
        return session.outcome();
    }

    public String getStatusLine() {
        return request.describe() + " @ " + standPos.toShortString()
                + " [" + mode + ", " + session.outcome() + "]";
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    @Override
    public boolean isFinished() {
        return session.isTerminal();
    }

    @Override
    public void reset() {
        super.reset();
        resetTransientState();
    }

    @Override
    public void interrupt(Task interruptTask) {
        suspending = true;
        session.recordInterrupt();
        try {
            super.interrupt(interruptTask);
        } finally {
            suspending = false;
        }
    }

    private void resetTransientState() {
        mode = Mode.AT_STAND;
        openStartedMs = 0L;
        screenOpenTicks = 0;
        clickCooldown = 0;
        lastClickSignature = null;
        stalledClicks = 0;
        lastBrewTicks = -1;
        brewStallTicks = 0;
        brewStartWaitTicks = 0;
        fuelChargeWaitTicks = 0;
        activeSupplyPos = null;
        containerInspectedThisVisit = false;
        supplyAuthorized = false;
        lastOriginChestSlot = -1;
        pendingNeeds = BrewingPolicy.SupplyNeeds.NONE;
        session.clearInspectedContainers();
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null && !behaviourPushed) {
            mod.getBehaviour().push();
            behaviourPushed = true;
            mod.getBehaviour().addProtectedItems(
                    Items.POTION,
                    Items.NETHER_WART,
                    Items.GLISTERING_MELON_SLICE,
                    Items.BLAZE_POWDER);
            mod.getBehaviour().avoidBlockBreaking(pos -> pos.equals(standPos) || isNearbyContainerPos(pos));
        }
        resetTransientState();
        if (mod != null && mod.getPlayer() != null) {
            session.initBaselineIfNeeded(countPlayerFinalPotions(mod));
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        if (behaviourPushed && mod != null) {
            mod.getBehaviour().pop();
            behaviourPushed = false;
        }
        if (StorageHelper.isBrewingStandOpen() || isSupplyContainerScreenOpen(mod)) {
            StorageHelper.closeScreen();
        }
        if (!suspending && !session.isTerminal()) {
            int produced = mod != null && mod.getPlayer() != null
                    ? session.producedSoFar(countPlayerFinalPotions(mod)) : 0;
            session.markCancelled(produced, cancelRequested ? "cancelled by player" : "stopped by task chain");
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getPlayer() == null || mod.getWorld() == null) {
            return null;
        }
        if (cancelRequested) {
            if (!session.isTerminal()) {
                session.markCancelled(session.producedSoFar(countPlayerFinalPotions(mod)), "cancelled by player");
            }
            closeAnyBrewingOrSupplyScreen(mod);
            return null;
        }
        if (session.isTerminal()) {
            syncTerminalTaskResult();
            return null;
        }

        session.initBaselineIfNeeded(countPlayerFinalPotions(mod));

        // Fast completion check when inventory already holds the target potions and cursor is clear.
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        int liveFinal = countPlayerFinalPotions(mod);
        if (cursor.isEmpty() && liveFinal >= session.targetInventoryCount()) {
            closeAnyBrewingOrSupplyScreen(mod);
            int produced = session.producedSoFar(liveFinal);
            session.markSuccess(produced, "verified " + produced + "x " + request.recipe().displayName() + " in inventory");
            Debug.logMessage("BREW COMPLETE: " + session.outcome());
            setDebugState("Brew complete: " + session.outcome().detail());
            succeed();
            return null;
        }

        // Verify the brewing stand block still exists when its chunk is loaded.
        if (mod.getChunkTracker().isChunkLoaded(standPos)
                && mod.getWorld().getBlockState(standPos).getBlock() != Blocks.BREWING_STAND) {
            closeAnyBrewingOrSupplyScreen(mod);
            session.markStandUnavailable(session.producedSoFar(liveFinal),
                    "no brewing stand block at " + standPos.toShortString());
            return terminateWithSessionFailure();
        }

        return switch (mode) {
            case AT_STAND -> tickAtStand(mod);
            case FETCH_SUPPLIES -> tickFetchSupplies(mod);
        };
    }

    private Task tickAtStand(AltoClef mod) {
        PlayerEntity player = mod.getPlayer();
        ScreenHandler handler = player.currentScreenHandler;

        if (handler instanceof BrewingStandScreenHandler standHandler) {
            openStartedMs = 0L;
            session.resetOpenRetries();

            if (++screenOpenTicks < SCREEN_SYNC_TICKS) {
                setDebugState("Syncing brewing stand screen");
                return null;
            }

            BrewingStandSnapshot snap = captureStandSnapshot(standHandler);
            lastObservedStand = snap;
            int liveFinal = snap.countPlayerFinal(request.recipe(), false);

            // Detect click stalls between consecutive clicks.
            if (lastClickSignature != null) {
                if (snap.stateSignature().equals(lastClickSignature)) {
                    if (clickCooldown > 0) {
                        clickCooldown--;
                        return null;
                    }
                    if (++stalledClicks >= BrewingSession.MAX_STALLED_CLICKS) {
                        StorageHelper.closeScreen();
                        session.markInteractionFailed(session.producedSoFar(liveFinal),
                                FailureReason.BACKEND_FAILURE,
                                "brewing stand slot click had no observable effect after " + stalledClicks + " attempts");
                        return terminateWithSessionFailure();
                    }
                } else {
                    lastClickSignature = null;
                    stalledClicks = 0;
                }
            }

            BrewingPolicy.StandDecision decision = BrewingPolicy.planNextStandAction(
                    request.recipe(),
                    session.targetInventoryCount(),
                    snap,
                    session.isStandClaimed());

            switch (decision.kind()) {
                case CONFLICT -> {
                    // Leave stand contents completely untouched!
                    StorageHelper.closeScreen();
                    session.markConflict(session.producedSoFar(liveFinal),
                            "stand conflict at " + standPos.toShortString() + ": " + decision.detail());
                    return terminateWithSessionFailure();
                }
                case COMPLETE -> {
                    StorageHelper.closeScreen();
                    int produced = session.producedSoFar(liveFinal);
                    session.markSuccess(produced, decision.detail());
                    Debug.logMessage("BREW COMPLETE: " + session.outcome());
                    setDebugState("Brew complete: " + session.outcome().detail());
                    succeed();
                    return null;
                }
                case INVENTORY_FULL -> {
                    StorageHelper.closeScreen();
                    session.markInventoryFull(session.producedSoFar(liveFinal), decision.detail());
                    return terminateWithSessionFailure();
                }
                case WAIT_FUEL_CHARGE -> {
                    session.claimStand();
                    if (++fuelChargeWaitTicks > MAX_FUEL_CHARGE_WAIT_TICKS) {
                        StorageHelper.closeScreen();
                        session.markInteractionFailed(session.producedSoFar(liveFinal),
                                FailureReason.TIMEOUT,
                                "brewing stand fuel failed to charge from fuel slot");
                        return terminateWithSessionFailure();
                    }
                    setDebugState("Waiting for stand fuel charge (" + decision.detail() + ")");
                    return null;
                }
                case WAIT_BREWING -> {
                    session.claimStand();
                    fuelChargeWaitTicks = 0;
                    int bt = snap.brewTicks();
                    if (bt > 0) {
                        brewStartWaitTicks = 0;
                        if (bt != lastBrewTicks) {
                            lastBrewTicks = bt;
                            brewStallTicks = 0;
                        } else if (++brewStallTicks > BrewingSession.MAX_BREW_STALL_TICKS) {
                            StorageHelper.closeScreen();
                            session.markInteractionFailed(session.producedSoFar(liveFinal),
                                    FailureReason.TIMEOUT,
                                    "brewing progress stalled at brewTicks=" + bt);
                            return terminateWithSessionFailure();
                        }
                        setDebugState("Brewing stage in progress (ticks=" + bt + ", fuel=" + snap.fuelCharges() + ")");
                    } else {
                        lastBrewTicks = 0;
                        brewStallTicks = 0;
                        if (++brewStartWaitTicks > BrewingSession.MAX_BREW_START_WAIT_TICKS) {
                            StorageHelper.closeScreen();
                            session.markInteractionFailed(session.producedSoFar(liveFinal),
                                    FailureReason.TIMEOUT,
                                    "loaded ingredient did not begin brewing (" + decision.detail() + ")");
                            return terminateWithSessionFailure();
                        }
                        setDebugState("Waiting for brewing to start (" + decision.detail() + ")");
                    }
                    return null;
                }
                case NEED_SUPPLIES -> {
                    session.claimStand();
                    fuelChargeWaitTicks = 0;
                    brewStartWaitTicks = 0;
                    brewStallTicks = 0;
                    if (session.incrementSupplyCyclesExceeded()) {
                        StorageHelper.closeScreen();
                        if (decision.supplyNeeds().needsFuel()) {
                            session.markMissingFuel(session.producedSoFar(liveFinal),
                                    "missing fuel (blaze_powder) after checking nearby supplies");
                        } else {
                            session.markMissingSupplies(session.producedSoFar(liveFinal),
                                    "missing supplies after bounded fetch cycles: " + decision.supplyNeeds().describeMissing());
                        }
                        return terminateWithSessionFailure();
                    }
                    StorageHelper.closeScreen();
                    beginSupplyFetch(mod, decision.supplyNeeds(), liveFinal);
                    return null;
                }
                case CLICK -> {
                    session.claimStand();
                    fuelChargeWaitTicks = 0;
                    brewStartWaitTicks = 0;
                    brewStallTicks = 0;
                    if (clickCooldown > 0) {
                        clickCooldown--;
                        setDebugState(decision.detail());
                        return null;
                    }
                    if (!mod.getSlotHandler().canDoSlotAction()) {
                        setDebugState(decision.detail());
                        return null;
                    }
                    BrewingStandSnapshot.ClickAction click = decision.click();
                    recordLoadMetricIfPlacing(decision.clickRole(), click, snap);
                    lastClickSignature = snap.stateSignature();
                    executeStandClick(mod, click);
                    clickCooldown = CLICK_COOLDOWN_TICKS;
                    setDebugState(decision.detail());
                    return null;
                }
            }
            return null;
        }

        // Brewing stand screen is not open yet.
        screenOpenTicks = 0;
        lastClickSignature = null;
        stalledClicks = 0;

        if (!StorageHelper.isPlayerInventoryOpen()) {
            StorageHelper.closeScreen();
            setDebugState("Closing unrelated screen before opening brewing stand");
            return null;
        }

        long now = System.currentTimeMillis();
        if (openStartedMs == 0L) {
            openStartedMs = now;
        } else if (now - openStartedMs > OPEN_ATTEMPT_TIMEOUT_MS) {
            openStartedMs = now;
            if (session.incrementOpenRetriesExceeded()) {
                session.markStandUnavailable(session.producedSoFar(countPlayerFinalPotions(mod)),
                        "could not open brewing stand at " + standPos.toShortString() + " after bounded retries");
                return terminateWithSessionFailure();
            }
        }

        setDebugState("Opening brewing stand at " + standPos.toShortString());
        return openBlockTask(mod, standPos);
    }

    private void beginSupplyFetch(AltoClef mod, BrewingPolicy.SupplyNeeds needs, int liveFinal) {
        pendingNeeds = needs;
        nearbyContainers = scanNearbySupplyContainers(mod);
        session.clearInspectedContainers();
        supplyAuthorized = false;
        containerInspectedThisVisit = false;
        activeSupplyPos = null;
        lastOriginChestSlot = -1;
        openStartedMs = 0L;
        screenOpenTicks = 0;
        lastClickSignature = null;
        stalledClicks = 0;

        if (nearbyContainers.isEmpty()) {
            if (needs.needsFuel()) {
                session.markMissingFuel(session.producedSoFar(liveFinal),
                        "missing fuel (blaze_powder) and no supply container beside brewing stand");
            } else {
                session.markMissingSupplies(session.producedSoFar(liveFinal),
                        "missing supplies (" + needs.describeMissing() + ") and no supply container beside brewing stand");
            }
            syncTerminalTaskResult();
            return;
        }

        mode = Mode.FETCH_SUPPLIES;
        activeSupplyPos = nearbyContainers.get(0);
    }

    private Task tickFetchSupplies(AltoClef mod) {
        PlayerEntity player = mod.getPlayer();
        ScreenHandler handler = player.currentScreenHandler;

        if (isSupplyContainerHandler(handler)) {
            openStartedMs = 0L;
            session.resetOpenRetries();

            if (++screenOpenTicks < SCREEN_SYNC_TICKS) {
                setDebugState("Syncing supply container screen");
                return null;
            }

            SupplyContainerSnapshot snap = captureSupplySnapshot(handler);
            String containerKey = activeSupplyPos != null ? posKey(activeSupplyPos) : "open";

            // Detect stalled clicks inside supply container.
            if (lastClickSignature != null) {
                if (snap.stateSignature().equals(lastClickSignature)) {
                    if (clickCooldown > 0) {
                        clickCooldown--;
                        return null;
                    }
                    if (++stalledClicks >= BrewingSession.MAX_STALLED_CLICKS) {
                        StorageHelper.closeScreen();
                        session.markInteractionFailed(session.producedSoFar(countPlayerFinalPotions(mod)),
                                FailureReason.BACKEND_FAILURE,
                                "supply container click stalled after " + stalledClicks + " attempts");
                        return terminateWithSessionFailure();
                    }
                } else {
                    lastClickSignature = null;
                    stalledClicks = 0;
                }
            }

            // Recompute exact remaining needs from last observed stand + live player inventory (excluding cursor).
            SupplyContainerSnapshot.SupplyCounts playerCounts = snap.countPlayerSupplies(request.recipe(), false);
            BrewingPolicy.SupplyNeeds liveNeeds = lastObservedStand != null
                    ? BrewingPolicy.computeSupplyNeeds(request.recipe(), session.targetInventoryCount(), lastObservedStand, playerCounts)
                    : pendingNeeds;

            if (!containerInspectedThisVisit) {
                containerInspectedThisVisit = true;
                SupplyContainerSnapshot.SupplyCounts chestCounts = snap.countContainerSupplies(request.recipe());
                session.recordInspectedContainer(containerKey, chestCounts);
                // If this is a double chest (54 slots), also mark any horizontally adjacent chest block as inspected
                // with ZERO additional supplies so we never open the second half redundantly.
                if (snap.containerSlotCount() > 27 && activeSupplyPos != null) {
                    markAdjacentDoubleChestHalf(mod, activeSupplyPos);
                }

                if (!supplyAuthorized) {
                    SupplyContainerSnapshot.SupplyCounts knownTotal = session.totalInspectedContainerSupplies();
                    BrewingPolicy.SupplyNeeds deficit = BrewingPolicy.subtractAvailableFromNeeds(liveNeeds, knownTotal);
                    if (deficit.isSatisfied()) {
                        supplyAuthorized = true;
                    } else {
                        BlockPos nextUnchecked = findNextUncheckedContainer();
                        if (nextUnchecked != null) {
                            // Do NOT withdraw anything until all required supplies (especially fuel) are confirmed!
                            StorageHelper.closeScreen();
                            switchSupplyTarget(nextUnchecked);
                            setDebugState("Checking next nearby supply container");
                            return null;
                        }
                        // All nearby containers checked and required supplies (or fuel) are still missing!
                        StorageHelper.closeScreen();
                        int produced = session.producedSoFar(playerCounts.exactHealingPotions());
                        if (deficit.needsFuel()) {
                            session.markMissingFuel(produced,
                                    "missing fuel (blaze_powder x" + deficit.blazePowder() + ") in stand, inventory, and nearby supplies");
                        } else {
                            session.markMissingSupplies(produced,
                                    "missing brewing supplies: " + deficit.describeMissing());
                        }
                        return terminateWithSessionFailure();
                    }
                }
            }

            // Authorized: withdraw ONLY the exact missing items from this container.
            SupplyContainerSnapshot.WithdrawalDecision wd = snap.planNextWithdrawal(
                    request.recipe(), liveNeeds, lastOriginChestSlot);

            switch (wd.status()) {
                case DONE -> {
                    StorageHelper.closeScreen();
                    returnToStand();
                    setDebugState("Supplies collected; returning to brewing stand");
                    return null;
                }
                case NOTHING_TO_TAKE -> {
                    // Refresh cached counts for this container after taking what we could.
                    session.recordInspectedContainer(containerKey, snap.countContainerSupplies(request.recipe()));
                    StorageHelper.closeScreen();
                    if (liveNeeds.isSatisfied()) {
                        returnToStand();
                        return null;
                    }
                    BlockPos nextUseful = findNextContainerWithNeededSupply(liveNeeds);
                    if (nextUseful != null) {
                        switchSupplyTarget(nextUseful);
                        setDebugState("Moving to next supply container for remaining items");
                        return null;
                    }
                    int produced = session.producedSoFar(playerCounts.exactHealingPotions());
                    if (liveNeeds.needsFuel()) {
                        session.markMissingFuel(produced,
                                "missing fuel (blaze_powder) while withdrawing supplies");
                    } else {
                        session.markMissingSupplies(produced,
                                "missing supplies while withdrawing: " + liveNeeds.describeMissing());
                    }
                    return terminateWithSessionFailure();
                }
                case INVENTORY_FULL -> {
                    StorageHelper.closeScreen();
                    session.markInventoryFull(session.producedSoFar(playerCounts.exactHealingPotions()), wd.detail());
                    return terminateWithSessionFailure();
                }
                case CLICK -> {
                    if (clickCooldown > 0) {
                        clickCooldown--;
                        setDebugState(wd.detail());
                        return null;
                    }
                    if (!mod.getSlotHandler().canDoSlotAction()) {
                        setDebugState(wd.detail());
                        return null;
                    }
                    lastOriginChestSlot = wd.originChestSlot();
                    lastClickSignature = snap.stateSignature();
                    executeSupplyClick(mod, snap.containerSlotCount(), wd.click());
                    clickCooldown = CLICK_COOLDOWN_TICKS;
                    setDebugState(wd.detail());
                    return null;
                }
            }
            return null;
        }

        // Supply container screen is not open yet.
        screenOpenTicks = 0;
        lastClickSignature = null;
        stalledClicks = 0;

        if (!StorageHelper.isPlayerInventoryOpen()) {
            StorageHelper.closeScreen();
            setDebugState("Closing screen before opening supply container");
            return null;
        }

        if (activeSupplyPos == null) {
            returnToStand();
            return null;
        }

        long now = System.currentTimeMillis();
        if (openStartedMs == 0L) {
            openStartedMs = now;
        } else if (now - openStartedMs > OPEN_ATTEMPT_TIMEOUT_MS) {
            openStartedMs = now;
            if (session.incrementOpenRetriesExceeded()) {
                // Mark this container unreachable/empty and try next, or fail cleanly.
                session.recordInspectedContainer(posKey(activeSupplyPos), SupplyContainerSnapshot.SupplyCounts.ZERO);
                BlockPos next = findNextUncheckedContainer();
                if (next != null) {
                    session.resetOpenRetries();
                    switchSupplyTarget(next);
                    return null;
                }
                int produced = session.producedSoFar(countPlayerFinalPotions(mod));
                if (pendingNeeds.needsFuel()) {
                    session.markMissingFuel(produced, "could not open nearby supply container to obtain blaze powder");
                } else {
                    session.markMissingSupplies(produced, "could not open nearby supply container for " + pendingNeeds.describeMissing());
                }
                return terminateWithSessionFailure();
            }
        }

        setDebugState("Opening supply container at " + activeSupplyPos.toShortString());
        return openBlockTask(mod, activeSupplyPos);
    }

    private void switchSupplyTarget(BlockPos nextPos) {
        activeSupplyPos = nextPos;
        containerInspectedThisVisit = false;
        lastOriginChestSlot = -1;
        openStartedMs = 0L;
        screenOpenTicks = 0;
        lastClickSignature = null;
        stalledClicks = 0;
    }

    private void returnToStand() {
        mode = Mode.AT_STAND;
        activeSupplyPos = null;
        containerInspectedThisVisit = false;
        lastOriginChestSlot = -1;
        openStartedMs = 0L;
        screenOpenTicks = 0;
        lastClickSignature = null;
        stalledClicks = 0;
    }

    private BlockPos findNextUncheckedContainer() {
        for (BlockPos p : nearbyContainers) {
            if (!session.hasInspectedContainer(posKey(p))) {
                return p;
            }
        }
        return null;
    }

    private BlockPos findNextContainerWithNeededSupply(BrewingPolicy.SupplyNeeds needs) {
        for (BlockPos p : nearbyContainers) {
            if (p.equals(activeSupplyPos)) continue;
            SupplyContainerSnapshot.SupplyCounts c = session.inspectedContainers().get(posKey(p));
            if (c == null) continue;
            if ((needs.blazePowder() > 0 && c.blazePowder() > 0)
                    || (needs.waterBottles() > 0 && c.exactWaterBottles() > 0)
                    || (needs.awkwardPotions() > 0 && c.exactAwkwardPotions() > 0)
                    || (needs.stage1Ingredient() > 0 && c.stage1Ingredients() > 0)
                    || (needs.stage2Ingredient() > 0 && c.stage2Ingredients() > 0)) {
                return p;
            }
        }
        return null;
    }

    private void markAdjacentDoubleChestHalf(AltoClef mod, BlockPos openedPos) {
        Block openedBlock = mod.getWorld().getBlockState(openedPos).getBlock();
        if (!(openedBlock instanceof ChestBlock)) return;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = openedPos.offset(d);
            if (mod.getChunkTracker().isChunkLoaded(adj)
                    && mod.getWorld().getBlockState(adj).getBlock() == openedBlock) {
                String k = posKey(adj);
                if (!session.hasInspectedContainer(k)) {
                    session.recordInspectedContainer(k, SupplyContainerSnapshot.SupplyCounts.ZERO);
                }
            }
        }
    }

    private List<BlockPos> scanNearbySupplyContainers(AltoClef mod) {
        int r = request.supplySearchRadius();
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = standPos.add(dx, dy, dz);
                    if (!mod.getChunkTracker().isChunkLoaded(p)) continue;
                    Block b = mod.getWorld().getBlockState(p).getBlock();
                    if (isSupplyContainerBlock(b) && seen.add(p.toImmutable())) {
                        found.add(p.toImmutable());
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(standPos)));
        return found;
    }

    private boolean isNearbyContainerPos(BlockPos pos) {
        for (BlockPos p : nearbyContainers) {
            if (p.equals(pos)) return true;
        }
        return false;
    }

    private static boolean isSupplyContainerBlock(Block b) {
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
    }

    private static boolean isSupplyContainerHandler(ScreenHandler h) {
        return h instanceof GenericContainerScreenHandler || h instanceof ShulkerBoxScreenHandler;
    }

    private static boolean isSupplyContainerScreenOpen(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return false;
        return isSupplyContainerHandler(mod.getPlayer().currentScreenHandler);
    }

    private static void closeAnyBrewingOrSupplyScreen(AltoClef mod) {
        if (StorageHelper.isBrewingStandOpen() || isSupplyContainerScreenOpen(mod)) {
            StorageHelper.closeScreen();
        }
    }

    private static Task openBlockTask(AltoClef mod, BlockPos pos) {
        Direction face = openFace(mod, pos);
        if (face != null) {
            return new InteractWithBlockTask(ItemTarget.EMPTY, face, pos, Input.CLICK_RIGHT, false, false);
        }
        return new InteractWithBlockTask(pos);
    }

    private static Direction openFace(AltoClef mod, BlockPos pos) {
        Direction[] order = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        Direction best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Direction d : order) {
            BlockPos adj = pos.offset(d);
            if (!mod.getChunkTracker().isChunkLoaded(adj)) continue;
            BlockState st = mod.getWorld().getBlockState(adj);
            if (!st.isAir() && !WorldHelper.isSolidBlock(adj)) {
                continue;
            }
            if (st.isAir()) {
                double dist = adj.getSquaredDistance(mod.getPlayer().getBlockPos());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = d;
                }
            }
        }
        return best;
    }

    private void recordLoadMetricIfPlacing(BrewingPolicy.ClickRole role,
                                           BrewingStandSnapshot.ClickAction click,
                                           BrewingStandSnapshot snap) {
        if (click == null || snap.cursorEmpty()) {
            return;
        }
        if (role == BrewingPolicy.ClickRole.LOAD_FUEL && click.windowSlot() == BrewingStandSnapshot.FUEL_WINDOW_SLOT) {
            session.recordFuelLoad();
        } else if (role == BrewingPolicy.ClickRole.LOAD_STAGE1_INGREDIENT
                && click.windowSlot() == BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT) {
            session.recordStage1Load();
        } else if (role == BrewingPolicy.ClickRole.LOAD_STAGE2_INGREDIENT
                && click.windowSlot() == BrewingStandSnapshot.INGREDIENT_WINDOW_SLOT) {
            session.recordStage2Load();
        }
    }

    private void executeStandClick(AltoClef mod, BrewingStandSnapshot.ClickAction click) {
        Slot slot = new BrewingStandSlot(click.windowSlot());
        SlotActionType actionType = click.kind() == BrewingStandSnapshot.ClickKind.QUICK_MOVE
                ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP;
        mod.getSlotHandler().clickSlot(slot, click.button(), actionType);
    }

    private void executeSupplyClick(AltoClef mod, int containerSlots, BrewingStandSnapshot.ClickAction click) {
        Slot slot = new ChestSlot(click.windowSlot(), containerSlots > 27, false);
        SlotActionType actionType = click.kind() == BrewingStandSnapshot.ClickKind.QUICK_MOVE
                ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP;
        mod.getSlotHandler().clickSlot(slot, click.button(), actionType);
    }

    private static BrewItemSnapshot toSnapshot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return BrewItemSnapshot.EMPTY;
        }
        String itemId = ItemComponentReader.itemId(stack);
        String potionId = ItemComponentReader.readPotionId(stack);
        boolean customEffects = ItemComponentReader.hasCustomPotionEffects(stack);
        return new BrewItemSnapshot(itemId, stack.getCount(), stack.getMaxCount(), potionId, customEffects);
    }

    private static BrewingStandSnapshot captureStandSnapshot(BrewingStandScreenHandler handler) {
        int n = handler.slots.size();
        List<BrewItemSnapshot> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            slots.add(toSnapshot(handler.getSlot(i).getStack()));
        }
        BrewItemSnapshot cursor = toSnapshot(StorageHelper.getItemStackInCursorSlot());
        int fuel = Math.max(0, StorageHelper.getBrewingStandFuel(handler));
        int brewTicks = Math.max(0, StorageHelper.getBrewingStandBrewTime(handler));
        return new BrewingStandSnapshot(slots, cursor, fuel, brewTicks);
    }

    private static SupplyContainerSnapshot captureSupplySnapshot(ScreenHandler handler) {
        int total = handler.slots.size();
        int containerSlots = Math.max(0, total - 36);
        List<BrewItemSnapshot> slots = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            slots.add(toSnapshot(handler.getSlot(i).getStack()));
        }
        BrewItemSnapshot cursor = toSnapshot(StorageHelper.getItemStackInCursorSlot());
        return new SupplyContainerSnapshot(containerSlots, slots, cursor);
    }

    private int countPlayerFinalPotions(AltoClef mod) {
        int count = 0;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(false)) {
            if (request.recipe().isExactFinalOutput(toSnapshot(stack))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private Task terminateWithSessionFailure() {
        syncTerminalTaskResult();
        return null;
    }

    private void syncTerminalTaskResult() {
        BrewingOutcome out = session.outcome();
        if (out.isSuccess()) {
            if (getExplicitResult() != TaskResult.SUCCESS) {
                succeed();
            }
        } else if (out.isFailure()) {
            if (getExplicitResult() != TaskResult.FAILURE) {
                FailureReason reason = out.failureReason() != null ? out.failureReason() : FailureReason.UNKNOWN;
                Debug.logWarning("BREW FAILED: " + out);
                setDebugState("Brew failed: " + out.detail());
                fail(reason, out.detail(), false);
            }
        }
    }

    private static String posKey(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof BrewPotionTask task) {
            return request.equals(task.request) && standPos.equals(task.standPos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Brew " + request.describe() + " at " + standPos.toShortString();
    }
}
