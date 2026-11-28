package adris.altoclef.tasks.replenish;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.speedrun.testrun2.McCompat;
import adris.altoclef.tasks.speedrun.testrun2.util.GameFiles;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemComponentReader;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.ChestSlot;
import adris.altoclef.util.slots.Slot;
import baritone.api.IBaritone;
import baritone.api.swarm.ISwarm;
import baritone.api.utils.input.Input;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Standing courier task that executes a {@link ReplenishmentRule} using ordinary player movement
 * and client-visible container screens only.
 * <p>
 * Policy decisions live in {@link ReplenishmentSession} and {@link ContainerTransferPlanner};
 * cooperative chest leases flow strictly through {@link baritone.api.swarm.ISwarm}.
 */
public final class ReplenishWorksiteTask extends Task {

    public static final String JOURNAL_FILE_NAME = "replenish-journal.properties";
    private static final int SCREEN_SYNC_TICKS = 3;
    private static final int CLICK_COOLDOWN_TICKS = 2;
    private static final int MAX_STALLED_CLICKS = 8;
    private static final long VISIT_TIMEOUT_MS = 45_000L;
    private static final long LEASE_TTL_MS = 30_000L;
    private static final long LEASE_WAIT_MS = 15_000L;

    private final ReplenishmentRule rule;
    private final BlockPos sourcePos;
    private final BlockPos destinationPos;
    private final Dimension dimension;
    private final Item targetItem;
    private final ReplenishmentSession session;
    private final String leaseOwner;

    private boolean startedOnce = false;
    private boolean cancelling = false;

    // Transient state for the current chest visit (reset cleanly on interrupt/reset).
    private BlockPos activeChestPos = null;
    private boolean activeChestIsSource = false;
    private String heldLeaseKey = null;
    private long visitStartedMs = 0L;
    private long leaseWaitSinceMs = 0L;
    private boolean screenObservedOpen = false;
    private int screenOpenTicks = 0;
    private int clickCooldown = 0;
    private ReplenishmentSession.ScreenPlan activePlan = null;
    private int visitInitialChestCount = 0;
    private int visitInitialPlayerCount = 0;
    private int lastCheckpointedMoved = 0;
    private int stalledClicks = 0;
    private int lastClickChestCount = -1;
    private int lastClickPlayerCount = -1;
    private int lastClickCursorCount = -1;
    private String lastLoggedReport = "";

    public ReplenishWorksiteTask(ReplenishmentRule rule,
                                 BlockPos sourcePos,
                                 BlockPos destinationPos,
                                 Dimension dimension) {
        this(rule, sourcePos, destinationPos, dimension,
                new ReplenishmentJournal.FileStore(GameFiles.dir().resolve(JOURNAL_FILE_NAME)));
    }

    public ReplenishWorksiteTask(ReplenishmentRule rule,
                                 BlockPos sourcePos,
                                 BlockPos destinationPos,
                                 Dimension dimension,
                                 ReplenishmentJournal.Store journalStore) {
        this.targetItem = McCompat.item(rule.itemId());
        if (this.targetItem == null) {
            throw new IllegalArgumentException("Unknown Minecraft item: " + rule.itemId());
        }
        int maxStack = Math.max(1, new ItemStack(this.targetItem).getMaxCount());
        this.rule = rule.withMaxStackSize(maxStack);
        this.sourcePos = sourcePos.toImmutable();
        this.destinationPos = destinationPos.toImmutable();
        this.dimension = dimension == null ? Dimension.OVERWORLD : dimension;
        this.session = new ReplenishmentSession(this.rule, journalStore);
        this.leaseOwner = "replenish:" + this.rule.ruleKey();
    }

    public ReplenishmentRule getRule() {
        return rule;
    }

    public ReplenishmentSession getSession() {
        return session;
    }

    public BlockPos getSourcePos() {
        return sourcePos;
    }

    public BlockPos getDestinationPos() {
        return destinationPos;
    }

    public String getStatusLine() {
        return session.statusLine();
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        // Protect target item from being thrown away while courier is carrying it.
        mod.getBehaviour().addProtectedItems(targetItem);
        // Protect source and destination chests from accidental breaking.
        mod.getBehaviour().avoidBlockBreaking(pos -> pos.equals(sourcePos) || pos.equals(destinationPos));

        cancelling = false;
        long now = System.currentTimeMillis();
        int liveInv = countPlayerInventory(mod);
        session.reconcileOnStartOrResume(liveInv, now);
        startedOnce = true;
        logReportIfChanged();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getPlayer() == null) {
            return null;
        }
        long now = System.currentTimeMillis();

        if (!startedOnce) {
            session.reconcileOnStartOrResume(countPlayerInventory(mod), now);
            startedOnce = true;
            logReportIfChanged();
        }

        if (session.isTerminal()) {
            cleanupAndRelease(mod);
            setDebugState("Terminal: " + session.phase());
            return null;
        }

        if (WorldHelper.getCurrentDimension() != dimension) {
            setDebugState("Waiting for dimension " + dimension);
            return null;
        }

        // Observe external destination updates from ContainerSubTracker (O(1) lookup, zero world scans).
        Optional<ContainerCache> destCache = mod.getItemStorage().getContainerAtPosition(destinationPos);
        if (destCache.isPresent() && destCache.get().getLastUpdatedMs() > 0L) {
            int cachedCount = destCache.get().getItemCount(targetItem);
            session.onDestinationObservedExternally(cachedCount, destCache.get().getLastUpdatedMs(),
                    countPlayerInventory(mod), now);
        }

        session.tickScheduler(now, countPlayerInventory(mod));
        logReportIfChanged();

        ReplenishmentSession.Phase phase = session.phase();
        return switch (phase) {
            case IDLE_MONITORING -> {
                cleanupAndRelease(mod);
                long waitSec = Math.max(0L, (session.nextCheckMs() - now) / 1000L);
                setDebugState("Monitoring '" + rule.destinationName() + "' (next check in " + waitSec + "s)");
                yield null;
            }
            case BACKOFF -> {
                cleanupAndRelease(mod);
                long waitSec = Math.max(0L, (session.backoffUntilMs() - now) / 1000L);
                setDebugState("Backoff (" + waitSec + "s): " + session.lastReport());
                yield null;
            }
            case CHECK_DEST -> runChestVisit(mod, destinationPos, false, now);
            case DELIVER_DEST -> runChestVisit(mod, destinationPos, false, now);
            case WITHDRAW_SOURCE -> runChestVisit(mod, sourcePos, true, now);
            case RETURN_SURPLUS -> runChestVisit(mod, sourcePos, true, now);
            case FAILED, CANCELLED -> {
                cleanupAndRelease(mod);
                yield null;
            }
        };
    }

    private Task runChestVisit(AltoClef mod, BlockPos chestPos, boolean isSource, long now) {
        // Switching target chest cleanly resets transient visit state and releases prior lease.
        if (!chestPos.equals(activeChestPos) || activeChestIsSource != isSource) {
            cleanupAndRelease(mod);
            activeChestPos = chestPos;
            activeChestIsSource = isSource;
            visitStartedMs = now;
            leaseWaitSinceMs = 0L;
            screenObservedOpen = false;
            screenOpenTicks = 0;
            clickCooldown = 0;
            activePlan = null;
            lastCheckpointedMoved = 0;
            stalledClicks = 0;
        }

        // 1. Acquire or renew cooperative chest lease via baritone.api.swarm.ISwarm (26.3 Ostinato API).
        String wantKey = chestLeaseKey(chestPos);
        if (!tryAcquireLease(mod, wantKey)) {
            if (leaseWaitSinceMs == 0L) {
                leaseWaitSinceMs = now;
            }
            if (now - leaseWaitSinceMs > LEASE_WAIT_MS) {
                session.onVisitFailed("chest lease busy for " + wantKey, now);
                cleanupAndRelease(mod);
                logReportIfChanged();
                return null;
            }
            setDebugState("Waiting for cooperative lease on " + chestLabel(isSource));
            return null;
        }
        heldLeaseKey = wantKey;
        leaseWaitSinceMs = 0L;

        // 2. Enforce bounded visit timeout so unreachable/stuck chests never hang forever.
        if (now - visitStartedMs > VISIT_TIMEOUT_MS) {
            session.onVisitFailed("visit to '" + chestLabel(isSource) + "' timed out", now);
            cleanupAndRelease(mod);
            logReportIfChanged();
            return null;
        }

        // 3. Check if a chest screen is currently open.
        PlayerEntity player = mod.getPlayer();
        ScreenHandler handler = player.currentScreenHandler;
        boolean chestMenuOpen = handler instanceof GenericContainerScreenHandler;
        Optional<BlockPos> lastInteracted = mod.getItemStorage().getLastBlockPosInteraction();
        boolean matchingChestOpen = chestMenuOpen
                && (lastInteracted.isEmpty() || isSameOrAdjacentChest(lastInteracted.get(), chestPos));

        if (matchingChestOpen) {
            screenObservedOpen = true;
            return tickOpenChest(mod, (GenericContainerScreenHandler) handler, isSource, now);
        }

        // If the chest screen closed unexpectedly mid-visit (e.g. human/interruption), fail this visit cleanly.
        if (screenObservedOpen && !chestMenuOpen) {
            session.onVisitFailed("chest '" + chestLabel(isSource) + "' closed early", now);
            cleanupAndRelease(mod);
            logReportIfChanged();
            return null;
        }

        // If some OTHER container screen is open, close it first.
        if (!StorageHelper.isPlayerInventoryOpen()) {
            StorageHelper.closeScreen();
            setDebugState("Closing unrelated screen before visiting '" + chestLabel(isSource) + "'");
            return null;
        }

        // 4. Verify container block exists when chunk is loaded.
        if (mod.getChunkTracker().isChunkLoaded(chestPos)) {
            Block block = mod.getWorld().getBlockState(chestPos).getBlock();
            ContainerType ctype = ContainerType.getFromBlock(block);
            if (ctype == ContainerType.EMPTY) {
                session.onVisitFailed("no container block at '" + chestLabel(isSource) + "' (" + chestPos.toShortString() + ")", now);
                cleanupAndRelease(mod);
                logReportIfChanged();
                return null;
            }
            if (WorldHelper.isChest(chestPos) && WorldHelper.isSolidBlock(chestPos.up()) && WorldHelper.canBreak(chestPos.up())) {
                setDebugState("Clearing block above '" + chestLabel(isSource) + "'");
                return new DestroyBlockTask(chestPos.up());
            }
        }

        // 5. Walk to and open the chest using ordinary player controls.
        setDebugState(session.phase().name().toLowerCase() + ": opening '" + chestLabel(isSource) + "'");
        Direction face = openFace(mod, chestPos);
        if (face != null) {
            return new InteractWithBlockTask(ItemTarget.EMPTY, face, chestPos, Input.CLICK_RIGHT, false, false);
        }
        return new InteractWithBlockTask(chestPos);
    }

    private Task tickOpenChest(AltoClef mod, GenericContainerScreenHandler handler, boolean isSource, long now) {
        // Wait briefly for server container contents packet to populate slots.
        if (++screenOpenTicks < SCREEN_SYNC_TICKS) {
            setDebugState("Syncing '" + chestLabel(isSource) + "' screen");
            return null;
        }

        ContainerSlotSnapshot snap = captureSnapshot(handler);
        int liveChest = snap.countInContainer(rule.itemId());
        int livePlayer = snap.countInPlayer(rule.itemId());
        int chestFree = snap.freeCapacityInContainer(rule.itemId(), rule.maxStackSize());
        int playerFree = snap.freeCapacityInPlayer(rule.itemId(), rule.maxStackSize());

        // Compute the transfer plan ONCE from fresh open-screen counts.
        if (activePlan == null) {
            visitInitialChestCount = liveChest;
            visitInitialPlayerCount = livePlayer;
            lastCheckpointedMoved = 0;
            stalledClicks = 0;
            lastClickChestCount = -1;
            lastClickPlayerCount = -1;
            lastClickCursorCount = -1;

            if (isSource) {
                activePlan = session.onSourceOpened(liveChest, chestFree, playerFree, livePlayer, now);
            } else {
                activePlan = session.onDestinationOpened(liveChest, chestFree, livePlayer, now);
            }
            Debug.logInternal("REPLENISH open '" + chestLabel(isSource) + "': chest=" + liveChest
                    + " player=" + livePlayer + " plan=" + activePlan.kind() + " x" + activePlan.exactAmount()
                    + " (" + activePlan.note() + ")");
        }

        if (activePlan.kind() == ReplenishmentSession.ScreenActionKind.NONE) {
            if (!snap.cursorEmpty()) {
                return tryCursorCleanup(mod, handler, snap, isSource, now);
            }
            finishOpenVisit(mod, isSource, liveChest, chestFree, playerFree, livePlayer, 0, now);
            return null;
        }

        // Compute how much has already landed on the target side during this visit.
        boolean withdrawing = activePlan.kind() == ReplenishmentSession.ScreenActionKind.WITHDRAW;
        int movedSoFar = withdrawing
                ? Math.max(0, livePlayer - visitInitialPlayerCount)
                : Math.max(0, liveChest - visitInitialChestCount);

        // Monotonically checkpoint verified deltas to the journal in case of sudden crash mid-click.
        if (movedSoFar > lastCheckpointedMoved) {
            int delta = movedSoFar - lastCheckpointedMoved;
            lastCheckpointedMoved = movedSoFar;
            if (withdrawing) {
                session.checkpointCarriedDelta(delta, 0);
            } else {
                session.checkpointCarriedDelta(0, delta);
            }
        }

        int remaining = Math.max(0, activePlan.exactAmount() - movedSoFar);

        // Stall detection between clicks.
        if (lastClickChestCount >= 0) {
            if (liveChest == lastClickChestCount && livePlayer == lastClickPlayerCount && snap.cursorCount() == lastClickCursorCount) {
                if (++stalledClicks >= MAX_STALLED_CLICKS) {
                    if (!snap.cursorEmpty()) {
                        return tryCursorCleanup(mod, handler, snap, withdrawing, now);
                    }
                    session.onVisitFailed("container click stalled at '" + chestLabel(isSource) + "'", now);
                    cleanupAndRelease(mod);
                    logReportIfChanged();
                    return null;
                }
            } else {
                stalledClicks = 0;
            }
        }

        // Plan next single ordinary vanilla click.
        ContainerTransferPlanner.ClickAction click = withdrawing
                ? ContainerTransferPlanner.planNextWithdrawClick(snap, rule.itemId(), rule.maxStackSize(), remaining, rule.sourceReserve())
                : ContainerTransferPlanner.planNextDepositClick(snap, rule.itemId(), rule.maxStackSize(), remaining);

        if (click == null) {
            if (!snap.cursorEmpty()) {
                return tryCursorCleanup(mod, handler, snap, withdrawing, now);
            }
            finishOpenVisit(mod, isSource, liveChest, chestFree, playerFree, livePlayer, movedSoFar, now);
            return null;
        }

        setDebugState((withdrawing ? "Withdrawing" : "Depositing") + " " + movedSoFar + "/" + activePlan.exactAmount()
                + " at '" + chestLabel(isSource) + "'");

        if (clickCooldown > 0) {
            clickCooldown--;
            return null;
        }
        if (!mod.getSlotHandler().canDoSlotAction()) {
            return null;
        }

        lastClickChestCount = liveChest;
        lastClickPlayerCount = livePlayer;
        lastClickCursorCount = snap.cursorCount();
        executeClick(mod, handler, click);
        clickCooldown = CLICK_COOLDOWN_TICKS;
        return null;
    }

    private Task tryCursorCleanup(AltoClef mod,
                                  GenericContainerScreenHandler handler,
                                  ContainerSlotSnapshot snap,
                                  boolean preferContainer,
                                  long now) {
        ContainerTransferPlanner.ClickAction cleanup = ContainerTransferPlanner.planCursorCleanupClick(snap, preferContainer);
        if (cleanup == null) {
            session.onVisitFailed("unable to clear cursor stack cleanly", now);
            cleanupAndRelease(mod);
            logReportIfChanged();
            return null;
        }
        if (clickCooldown > 0) {
            clickCooldown--;
            return null;
        }
        if (!mod.getSlotHandler().canDoSlotAction()) {
            return null;
        }
        executeClick(mod, handler, cleanup);
        clickCooldown = CLICK_COOLDOWN_TICKS;
        setDebugState("Returning cursor stack before closing");
        return null;
    }

    private void finishOpenVisit(AltoClef mod,
                                 boolean isSource,
                                 int finalChestCount,
                                 int chestFreeAfter,
                                 int playerFreeAfter,
                                 int livePlayerCount,
                                 int movedTotal,
                                 long now) {
        boolean wasReturn = isSource && session.phase() == ReplenishmentSession.Phase.RETURN_SURPLUS;
        StorageHelper.closeScreen();
        releaseHeldLease(mod);
        resetTransientVisitState();

        if (isSource) {
            session.onSourceVisitFinished(visitInitialChestCount, finalChestCount, movedTotal, wasReturn,
                    playerFreeAfter, livePlayerCount, now);
        } else {
            session.onDestinationVisitFinished(visitInitialChestCount, finalChestCount, movedTotal,
                    chestFreeAfter, livePlayerCount, now);
        }
        logReportIfChanged();
    }

    private void executeClick(AltoClef mod, GenericContainerScreenHandler handler, ContainerTransferPlanner.ClickAction click) {
        int containerSlots = Math.max(0, handler.slots.size() - 36);
        Slot slot = Slot.getFromCurrentScreen(click.windowSlot());
        if (slot == null) {
            slot = new ChestSlot(click.windowSlot(), containerSlots > 27, false);
        }
        SlotActionType actionType = click.kind() == ContainerTransferPlanner.ClickKind.QUICK_MOVE
                ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP;
        mod.getSlotHandler().clickSlot(slot, click.button(), actionType);
    }

    private ContainerSlotSnapshot captureSnapshot(GenericContainerScreenHandler handler) {
        int totalSlots = handler.slots.size();
        int containerSlots = Math.max(0, totalSlots - 36);
        List<ContainerSlotSnapshot.SlotEntry> entries = new ArrayList<>(totalSlots);
        for (int i = 0; i < totalSlots; i++) {
            ItemStack st = handler.slots.get(i).getStack();
            if (st == null || st.isEmpty()) {
                entries.add(new ContainerSlotSnapshot.SlotEntry(i, i < containerSlots, "", 0, 64));
            } else {
                String id = ContainerSlotSnapshot.normalizeOrEmpty(ItemComponentReader.itemId(st));
                entries.add(new ContainerSlotSnapshot.SlotEntry(i, i < containerSlots, id, st.getCount(), st.getMaxCount()));
            }
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        String cursorId = (cursor == null || cursor.isEmpty())
                ? "" : ContainerSlotSnapshot.normalizeOrEmpty(ItemComponentReader.itemId(cursor));
        int cursorCount = (cursor == null || cursor.isEmpty()) ? 0 : cursor.getCount();
        int cursorMax = (cursor == null || cursor.isEmpty()) ? 64 : cursor.getMaxCount();
        return new ContainerSlotSnapshot(containerSlots, entries, cursorId, cursorCount, cursorMax);
    }

    private int countPlayerInventory(AltoClef mod) {
        if (mod == null || mod.getItemStorage() == null) {
            return 0;
        }
        int total = mod.getItemStorage().getItemCountInventoryOnly(targetItem);
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (cursor != null && !cursor.isEmpty() && cursor.getItem() == targetItem) {
            total = Math.max(0, total - cursor.getCount());
        }
        return total;
    }

    /**
     * Preserves {@link ReplenishmentSession} across {@code SingleTaskChain} interrupt -> reset -> resume
     * cycles while releasing transient screen/lease state cleanly.
     */
    @Override
    public void reset() {
        super.reset();
        AltoClef mod = AltoClef.getInstance();
        if (mod != null) {
            cleanupAndRelease(mod);
        } else {
            resetTransientVisitState();
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null) {
            if (mod.getBehaviour() != null) {
                mod.getBehaviour().pop();
            }
            cleanupAndRelease(mod);
            if (cancelling) {
                session.cancel(System.currentTimeMillis());
                logReportIfChanged();
            }
        }
    }

    public void requestCancel() {
        cancelling = true;
        AltoClef mod = AltoClef.getInstance();
        if (mod != null) {
            cleanupAndRelease(mod);
        }
        session.cancel(System.currentTimeMillis());
        logReportIfChanged();
    }

    @Override
    public boolean isFinished() {
        return session.isTerminal();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ReplenishWorksiteTask task) {
            return Objects.equals(rule, task.rule)
                    && Objects.equals(sourcePos, task.sourcePos)
                    && Objects.equals(destinationPos, task.destinationPos)
                    && dimension == task.dimension;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Replenish " + rule.describe() + " [" + session.phase() + "]";
    }

    private void cleanupAndRelease(AltoClef mod) {
        if (mod != null && mod.getPlayer() != null && !StorageHelper.isPlayerInventoryOpen()) {
            // If interrupted mid-split with cursor holding items, try a single immediate return click before close.
            ScreenHandler h = mod.getPlayer().currentScreenHandler;
            if (h instanceof GenericContainerScreenHandler gch && !StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                ContainerSlotSnapshot snap = captureSnapshot(gch);
                ContainerTransferPlanner.ClickAction cleanup = ContainerTransferPlanner.planCursorCleanupClick(snap, activeChestIsSource);
                if (cleanup != null) {
                    int containerSlots = Math.max(0, gch.slots.size() - 36);
                    SlotActionType type = cleanup.kind() == ContainerTransferPlanner.ClickKind.QUICK_MOVE
                            ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP;
                    BotClientBridge.clickSlot(gch.syncId, cleanup.windowSlot(), cleanup.button(), type, mod.getPlayer());
                }
            }
            StorageHelper.closeScreen();
        }
        releaseHeldLease(mod);
        if (mod != null && mod.getClientBaritone() != null) {
            try {
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            } catch (RuntimeException ignored) {
            }
        }
        resetTransientVisitState();
    }

    private boolean tryAcquireLease(AltoClef mod, String chestKey) {
        //#if MC >= 260000
        //$$ ISwarm swarm = swarmOrNull(mod);
        //$$ if (swarm != null) {
        //$$     try {
        //$$         return swarm.tryAcquireChestLease(chestKey, leaseOwner, LEASE_TTL_MS);
        //$$     } catch (LinkageError older) {
        //$$         return true;
        //$$     }
        //$$ }
        //#endif
        return true;
    }

    private void releaseHeldLease(AltoClef mod) {
        //#if MC >= 260000
        //$$ ISwarm swarm = swarmOrNull(mod);
        //$$ if (swarm != null) {
        //$$     try {
        //$$         if (heldLeaseKey != null) {
        //$$             swarm.releaseChestLease(heldLeaseKey, leaseOwner);
        //$$         }
        //$$         swarm.releaseAllChestLeases(leaseOwner);
        //$$     } catch (LinkageError ignored) {
        //$$     }
        //$$ }
        //#endif
        heldLeaseKey = null;
    }

    private void resetTransientVisitState() {
        activeChestPos = null;
        activeChestIsSource = false;
        visitStartedMs = 0L;
        leaseWaitSinceMs = 0L;
        screenObservedOpen = false;
        screenOpenTicks = 0;
        clickCooldown = 0;
        activePlan = null;
        lastCheckpointedMoved = 0;
        stalledClicks = 0;
        lastClickChestCount = -1;
        lastClickPlayerCount = -1;
        lastClickCursorCount = -1;
    }

    private ISwarm swarmOrNull(AltoClef mod) {
        if (mod == null) return null;
        try {
            IBaritone baritone = mod.getClientBaritone();
            return baritone == null ? null : baritone.getSwarm();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private String chestLeaseKey(BlockPos pos) {
        char dimChar = switch (dimension) {
            case NETHER -> 'n';
            case END -> 'e';
            default -> 'o';
        };
        return "c:" + dimChar + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String chestLabel(boolean isSource) {
        return isSource ? rule.sourceName() : rule.destinationName();
    }

    private void logReportIfChanged() {
        String r = session.lastReport();
        if (r != null && !r.equals(lastLoggedReport)) {
            lastLoggedReport = r;
            Debug.logMessage("REPLENISH: " + r + " [" + session.metrics().summary() + "]");
        }
    }

    private static boolean isSameOrAdjacentChest(BlockPos a, BlockPos b) {
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
