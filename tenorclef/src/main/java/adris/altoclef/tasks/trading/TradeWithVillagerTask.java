package adris.altoclef.tasks.trading;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasksystem.FailureReason;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemComponentReader;
import adris.altoclef.util.ItemComponentSpec;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.MerchantSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Acquires an exact item (including enchantment/component requirements and an optional total
 * emerald budget cap) from a known accessible villager using ordinary player actions only.
 * <p>
 * Key safety invariants:
 * <ul>
 *     <li>Reconciles observable inventory FIRST on every tick and after any interruption so a
 *         completed trade is never duplicated.</li>
 *     <li>Inspects actual merchant offers, validating exact output components, remaining uses,
 *         all required inputs (costA and optional costB), and remaining emerald budget.</li>
 *     <li>Rechecks the live offer, displayed output slot, payment slots, and remaining emerald
 *         budget immediately before committing a single trade via normal left-click ({@code PICKUP})
 *         on {@link MerchantSlot#OUTPUT_SLOT}.</li>
 *     <li>Confirms actual output arrival and emerald debit before finishing, and reports specific,
 *         actionable {@link VillagerTradeOutcome} + {@link FailureReason} results to parent goals.</li>
 * </ul>
 */
public class TradeWithVillagerTask extends ResourceTask {

    private static final double INTERACT_APPROACH_DISTANCE = 2.5;
    private static final double DROPPED_OUTPUT_RECOVERY_RANGE = 6.0;
    private static final long OFFERS_POPULATE_WAIT_TICKS = 30L;
    private static final long OPEN_WAIT_TICKS = 20L;
    private static final long SELECT_SYNC_WAIT_TICKS = 20L;
    private static final int MAX_RE_EVALUATES = 4;

    private final ItemTarget target;
    private final VillagerTradeRequest request;
    private final VillagerTradeSession session;

    private final TimerGame interactCooldown = new TimerGame(0.4);
    private final TimerGame slotActionTimer = new TimerGame(0.15);

    private MerchantEntity lockedMerchant = null;
    private TradeOfferSnapshot plannedOffer = null;
    private long openSentTick = -1L;
    private long screenOpenedTick = -1L;
    private long selectSentTick = -1L;
    private int reEvaluateCount = 0;

    private boolean behaviourPushed = false;
    private boolean suspending = false;

    public TradeWithVillagerTask(ItemTarget target) {
        super(target);
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("TradeWithVillagerTask requires a non-empty ItemTarget");
        }
        this.target = target;
        Item primaryItem = target.getMatches()[0];
        String outputId = ItemComponentReader.itemId(primaryItem);
        if (outputId.isEmpty() && target.isCatalogueItem()) {
            outputId = VillagerTradeRequest.normalizeItemId(target.getCatalogueName());
        }
        ItemComponentSpec spec = target.getComponentSpec() != null ? target.getComponentSpec() : ItemComponentSpec.EMPTY;
        this.request = new VillagerTradeRequest(
                outputId,
                spec,
                target.getTargetCount(),
                target.getMaxEmeraldCost(),
                target.getPreferredMerchantId());
        this.session = new VillagerTradeSession(request);
    }

    public VillagerTradeRequest getRequest() {
        return request;
    }

    public VillagerTradeOutcome getOutcome() {
        return session.outcome();
    }

    /** Exposed for tests and diagnostics: returns the persistent order/reconciliation ledger. */
    public VillagerTradeSession getSession() {
        return session;
    }

    @Override
    public boolean isFinished() {
        if (session.isTerminal()) {
            return true;
        }
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getPlayer() == null) {
            return false;
        }
        // Only declare finished if we have no commit in flight, cursor has no matching target,
        // and observable inventory meets the exact target count.
        if (session.isCommitInFlight()) {
            return false;
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (target.matches(cursor)) {
            return false;
        }
        int invCount = StorageHelper.countMatchingInventory(mod, target, false);
        if (invCount >= session.targetTotalCount()) {
            TradeInventorySnapshot snap = snapshotInventory(mod, null);
            session.reconcile(snap, BotClientBridge.tickCount());
            if (session.outcome().isSuccess()) {
                succeed();
                return true;
            }
        }
        return false;
    }

    /**
     * Preserves order baseline, emerald spend ledger, and in-flight commit evidence across
     * {@code SingleTaskChain} interrupt -> reset -> resume cycles so completed trades are never duplicated.
     */
    @Override
    public void reset() {
        super.reset();
        resetTransientInteractionState();
    }

    private void resetTransientInteractionState() {
        plannedOffer = null;
        openSentTick = -1L;
        screenOpenedTick = -1L;
        selectSentTick = -1L;
        reEvaluateCount = 0;
        interactCooldown.forceElapse();
        slotActionTimer.forceElapse();
    }

    @Override
    public void interrupt(Task interruptTask) {
        suspending = true;
        try {
            super.interrupt(interruptTask);
        } finally {
            suspending = false;
        }
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null && !behaviourPushed) {
            mod.getBehaviour().push();
            behaviourPushed = true;
            mod.getBehaviour().addProtectedItems(Items.EMERALD, Items.BOOK, Items.ENCHANTED_BOOK);
            mod.getBehaviour().addProtectedItems(target.getMatches());
        }
        resetTransientInteractionState();
        if (mod != null && mod.getPlayer() != null) {
            session.initBaselineIfNeeded(snapshotInventory(mod, null));
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        if (behaviourPushed && mod != null) {
            mod.getBehaviour().pop();
            behaviourPushed = false;
        }
        if (StorageHelper.isMerchantOpen()) {
            StorageHelper.closeScreen();
        }
        if (!suspending && !session.isTerminal()) {
            TradeInventorySnapshot snap = (mod != null && mod.getPlayer() != null)
                    ? snapshotInventory(mod, null) : null;
            session.markCancelled(snap, "cancelled by stop/interrupt");
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getPlayer() == null) {
            return null;
        }
        if (session.isTerminal()) {
            syncTerminalTaskResult();
            return null;
        }

        long tick = BotClientBridge.tickCount();
        MerchantScreenHandler merchantHandler = getOpenMerchantHandler(mod);
        if (merchantHandler == null) {
            screenOpenedTick = -1L;
            plannedOffer = null;
            selectSentTick = -1L;
        } else if (screenOpenedTick < 0) {
            screenOpenedTick = tick;
        }

        TradeInventorySnapshot invSnap = snapshotInventory(mod, plannedOffer);

        // Step 1: ALWAYS reconcile observable inventory FIRST before approaching, opening, or buying.
        VillagerTradeSession.ReconcileResult rec = session.reconcile(invSnap, tick);
        switch (rec.action()) {
            case ALREADY_COMPLETE -> {
                if (StorageHelper.isMerchantOpen()) {
                    StorageHelper.closeScreen();
                }
                Debug.logMessage("VillagerTrade COMPLETE: " + session.outcome());
                setDebugState("Trade complete: " + session.outcome().detail());
                succeed();
                return null;
            }
            case DEPOSIT_CURSOR -> {
                setDebugState("Depositing trade output from cursor");
                depositCursorIntoInventory(mod);
                return null;
            }
            case PICKUP_DROPPED_OUTPUT -> {
                if (StorageHelper.isMerchantOpen()) {
                    StorageHelper.closeScreen();
                }
                Optional<ItemEntity> dropped = findNearbyMatchingDrop(mod);
                if (dropped.isPresent()) {
                    setDebugState("Picking up dropped trade output");
                    return new GetToEntityTask(dropped.get(), 0.5);
                }
                setDebugState("Waiting for dropped output");
                return null;
            }
            case WAIT_CONFIRM -> {
                setDebugState("Confirming trade outcome: " + rec.detail());
                return null;
            }
            case COMMIT_CONFIRMED_CONTINUE -> {
                Debug.logMessage("VillagerTrade progress: " + rec.detail());
                plannedOffer = null;
                selectSentTick = -1L;
                session.resetSelectAttempts();
                // Continue into next purchase below.
            }
            case RETRY_NO_EFFECT -> {
                Debug.logWarning("VillagerTrade retry: " + rec.detail());
                plannedOffer = null;
                selectSentTick = -1L;
                session.resetSelectAttempts();
            }
            case FAILED -> {
                return terminateWithSessionFailure();
            }
            case PROCEED -> {
                // Proceed to trade flow.
            }
        }

        // Step 2: If merchant screen is NOT open, locate known accessible villager, approach, and interact.
        if (merchantHandler == null) {
            return tickApproachAndOpen(mod, invSnap, tick);
        }

        // Step 3: Merchant screen IS open — inspect actual offers, select, recheck before commit, and execute.
        return tickMerchantScreen(mod, merchantHandler, invSnap, tick);
    }

    private Task tickApproachAndOpen(AltoClef mod, TradeInventorySnapshot invSnap, long tick) {
        Optional<MerchantEntity> merchantOpt = resolveKnownMerchant(mod);
        if (merchantOpt.isEmpty()) {
            session.fail(
                    VillagerTradeOutcome.Status.VILLAGER_UNAVAILABLE,
                    FailureReason.TARGET_UNAVAILABLE,
                    request.hasPreferredMerchant()
                            ? "villager-unavailable: merchant '" + request.preferredMerchantId() + "' not found or unreachable"
                            : "villager-unavailable: no accessible villager/merchant found",
                    invSnap,
                    -1);
            return terminateWithSessionFailure();
        }

        MerchantEntity merchant = merchantOpt.get();
        lockedMerchant = merchant;

        if (openSentTick >= 0 && (tick - openSentTick) < OPEN_WAIT_TICKS) {
            setDebugState("Waiting for merchant screen");
            return null;
        }

        double reach = mod.getModSettings().getEntityReachRange();
        double distSq = merchant.squaredDistanceTo(mod.getPlayer());
        boolean inRange = mod.getControllerExtras().inRange(merchant) && distSq <= reach * reach;
        if (!inRange) {
            openSentTick = -1L;
            setDebugState("Approaching villager");
            return new GetToEntityTask(merchant, INTERACT_APPROACH_DISTANCE);
        }

        // Close any unrelated open screen before interacting with the villager.
        if (!StorageHelper.isPlayerInventoryOpen() && !StorageHelper.isMerchantOpen()) {
            StorageHelper.closeScreen();
        }

        // Ordinary player action: stop movement if safe, de-equip right-click items, look at villager, right-click.
        if (mod.getClientBaritone().getPathingBehavior().isPathing()
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
        mod.getSlotHandler().forceDeequipRightClickableItem();
        LookHelper.lookAt(mod, merchant.getEyePos());

        if (!interactCooldown.elapsed()) {
            setDebugState("Preparing to open villager trade screen");
            return null;
        }

        if (!session.recordOpenAttempt()) {
            session.fail(
                    VillagerTradeOutcome.Status.INTERACTION_FAILED,
                    FailureReason.TIMEOUT,
                    "failed to open merchant screen after " + (session.openAttempts() - 1) + " attempt(s)",
                    invSnap,
                    -1);
            return terminateWithSessionFailure();
        }

        interactCooldown.reset();
        openSentTick = tick;
        setDebugState("Opening villager merchant screen");
        mod.getController().interactEntity(mod.getPlayer(), merchant, Hand.MAIN_HAND);
        return null;
    }

    private Task tickMerchantScreen(AltoClef mod,
                                    MerchantScreenHandler handler,
                                    TradeInventorySnapshot invSnap,
                                    long tick) {
        List<TradeOfferSnapshot> offers = snapshotOffers(mod, handler);

        // Wait briefly for SetTradeOffersS2CPacket / ClientboundMerchantOffersPacket right after opening.
        if (offers.isEmpty()) {
            if (screenOpenedTick >= 0 && (tick - screenOpenedTick) < OFFERS_POPULATE_WAIT_TICKS) {
                setDebugState("Waiting for villager offers");
                return null;
            }
            session.fail(
                    VillagerTradeOutcome.Status.OFFER_UNAVAILABLE,
                    FailureReason.TARGET_UNAVAILABLE,
                    "offer-unavailable: merchant screen opened with no trade offers",
                    invSnap,
                    -1);
            return terminateWithSessionFailure();
        }

        // If we haven't selected an offer yet, inspect all offers and choose the best valid one.
        if (plannedOffer == null) {
            VillagerTradePolicy.OfferEvaluation eval = VillagerTradePolicy.evaluateOffers(
                    request, offers, invSnap, session.emeraldsSpent());
            if (eval.decision() != VillagerTradePolicy.OfferEvaluation.Decision.SELECT_OFFER) {
                session.failFromEvaluation(eval, invSnap);
                return terminateWithSessionFailure();
            }

            if (!session.recordSelectAttempt()) {
                session.fail(
                        VillagerTradeOutcome.Status.INTERACTION_FAILED,
                        FailureReason.TIMEOUT,
                        "failed to select and populate trade offer after bounded retries",
                        invSnap,
                        eval.cheapestSeenEmeraldCost());
                return terminateWithSessionFailure();
            }

            plannedOffer = eval.selectedOffer();
            selectSentTick = tick;
            selectOfferOnMerchant(handler, plannedOffer.offerIndex());
            setDebugState("Selecting offer #" + plannedOffer.offerIndex() + " (" + plannedOffer.emeraldCost() + " emeralds)");
            return null;
        }

        // Wait at least 1 tick after sending SelectTrade so server sync updates slots 0, 1, 2.
        if (tick <= selectSentTick) {
            setDebugState("Waiting for trade slot sync");
            return null;
        }

        // Recheck live offer at index, displayed output slot, payment slots, and remaining budget BEFORE committing.
        TradeOfferSnapshot liveAtIndex = (plannedOffer.offerIndex() >= 0 && plannedOffer.offerIndex() < offers.size())
                ? offers.get(plannedOffer.offerIndex()) : null;

        ItemStack slot0 = StorageHelper.getItemStackInSlot(MerchantSlot.INPUT_SLOT_1);
        ItemStack slot1 = StorageHelper.getItemStackInSlot(MerchantSlot.INPUT_SLOT_2);
        ItemStack displayedResult = StorageHelper.getItemStackInSlot(MerchantSlot.OUTPUT_SLOT);

        boolean paymentsSatisfy = liveAtIndex != null
                && offerSatisfiedByPaymentSlots(handler, liveAtIndex.offerIndex(), slot0, slot1);

        VillagerTradePolicy.PreCommitCheck preCheck = VillagerTradePolicy.recheckBeforeCommit(
                request,
                plannedOffer,
                liveAtIndex,
                ItemComponentReader.itemId(displayedResult),
                displayedResult.getCount(),
                ItemComponentReader.readEnchantments(displayedResult),
                paymentsSatisfy,
                invSnap,
                session.targetTotalCount(),
                session.emeraldsSpent());

        switch (preCheck.decision()) {
            case ALREADY_COMPLETE -> {
                session.reconcile(invSnap, tick);
                if (StorageHelper.isMerchantOpen()) {
                    StorageHelper.closeScreen();
                }
                succeed();
                return null;
            }
            case OVER_BUDGET -> {
                session.fail(VillagerTradeOutcome.Status.OVER_BUDGET,
                        FailureReason.PRECONDITION_FAILED, preCheck.detail(), invSnap,
                        liveAtIndex != null ? liveAtIndex.emeraldCost() : -1);
                return terminateWithSessionFailure();
            }
            case SOLD_OUT -> {
                session.fail(VillagerTradeOutcome.Status.SOLD_OUT,
                        FailureReason.TARGET_UNAVAILABLE, preCheck.detail(), invSnap,
                        liveAtIndex != null ? liveAtIndex.emeraldCost() : -1);
                return terminateWithSessionFailure();
            }
            case INVENTORY_FULL -> {
                session.fail(VillagerTradeOutcome.Status.INVENTORY_FULL,
                        FailureReason.INVENTORY_FULL, preCheck.detail(), invSnap,
                        liveAtIndex != null ? liveAtIndex.emeraldCost() : -1);
                return terminateWithSessionFailure();
            }
            case RE_EVALUATE -> {
                reEvaluateCount++;
                Debug.logWarning("VillagerTrade pre-commit recheck triggered re-evaluation: " + preCheck.detail());
                plannedOffer = null;
                selectSentTick = -1L;
                if (reEvaluateCount > MAX_RE_EVALUATES) {
                    session.fail(VillagerTradeOutcome.Status.INTERACTION_FAILED,
                            FailureReason.WORLD_CHANGED,
                            "offer changed repeatedly before commit: " + preCheck.detail(),
                            invSnap,
                            -1);
                    return terminateWithSessionFailure();
                }
                return null;
            }
            case WAIT_SYNC -> {
                if ((tick - selectSentTick) >= SELECT_SYNC_WAIT_TICKS) {
                    // Retry selection once if autofill/sync didn't populate slots.
                    Debug.logWarning("VillagerTrade sync timeout for offer #" + plannedOffer.offerIndex() + "; re-evaluating");
                    plannedOffer = null;
                    selectSentTick = -1L;
                } else {
                    setDebugState("Waiting for merchant payment/output sync");
                }
                return null;
            }
            case COMMIT -> {
                // Ensure cursor is empty before left-clicking OUTPUT_SLOT so PICKUP executes strictly ONE trade.
                ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                if (!cursor.isEmpty()) {
                    setDebugState("Clearing cursor before committing trade");
                    depositCursorIntoInventory(mod);
                    return null;
                }
                if (!slotActionTimer.elapsed() || !mod.getSlotHandler().canDoSlotAction()) {
                    setDebugState("Waiting slot cooldown before commit");
                    return null;
                }
                // Snapshot pre-commit inventory state IMMEDIATELY before clicking OUTPUT_SLOT.
                TradeInventorySnapshot preCommitSnap = snapshotInventory(mod, liveAtIndex);
                session.recordCommitStarted(preCommitSnap, liveAtIndex, tick);
                slotActionTimer.reset();
                setDebugState("Committing trade for " + request.describeTarget());
                mod.getSlotHandler().clickSlot(MerchantSlot.OUTPUT_SLOT, 0, SlotActionType.PICKUP);
                return null;
            }
        }
        return null;
    }

    private Task terminateWithSessionFailure() {
        if (StorageHelper.isMerchantOpen()) {
            StorageHelper.closeScreen();
        }
        syncTerminalTaskResult();
        return null;
    }

    private void syncTerminalTaskResult() {
        VillagerTradeOutcome out = session.outcome();
        if (out.isSuccess()) {
            if (getExplicitResult() != adris.altoclef.tasksystem.TaskResult.SUCCESS) {
                succeed();
            }
        } else if (out.isFailure()) {
            if (getExplicitResult() != adris.altoclef.tasksystem.TaskResult.FAILURE) {
                FailureReason reason = out.failureReason() != null ? out.failureReason() : FailureReason.UNKNOWN;
                Debug.logWarning("VillagerTrade FAILED: " + out);
                setDebugState("Trade failed: " + out.detail());
                fail(reason, out.detail(), false);
            }
        }
    }

    private void depositCursorIntoInventory(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (cursor.isEmpty()) {
            return;
        }
        if (!slotActionTimer.elapsed() || !mod.getSlotHandler().canDoSlotAction()) {
            return;
        }
        Optional<Slot> dest = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
        if (dest.isPresent()) {
            slotActionTimer.reset();
            mod.getSlotHandler().clickSlot(dest.get(), 0, SlotActionType.PICKUP);
            return;
        }
        // Fallback: find any empty player inventory slot in the current screen handler directly.
        for (Slot slot : Slot.getCurrentScreenSlots()) {
            if (!Slot.isCursor(slot) && slot.isSlotInPlayerInventory()) {
                ItemStack existing = StorageHelper.getItemStackInSlot(slot);
                if (existing.isEmpty()) {
                    slotActionTimer.reset();
                    mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                    return;
                }
            }
        }
    }

    private Optional<MerchantEntity> resolveKnownMerchant(AltoClef mod) {
        if (lockedMerchant != null && lockedMerchant.isAlive() && !lockedMerchant.isBaby()
                && mod.getEntityTracker().isEntityReachable(lockedMerchant)) {
            if (!request.hasPreferredMerchant() || matchesPreferredId(lockedMerchant, request.preferredMerchantId())) {
                return Optional.of(lockedMerchant);
            }
        }

        if (request.hasPreferredMerchant()) {
            String wanted = request.preferredMerchantId();
            for (Entity entity : BotClientBridge.entities()) {
                if (entity instanceof MerchantEntity m && m.isAlive() && !m.isBaby()
                        && matchesPreferredId(m, wanted)
                        && mod.getEntityTracker().isEntityReachable(m)) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }

        MerchantEntity best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (Entity entity : BotClientBridge.entities()) {
            if (entity instanceof MerchantEntity m && m.isAlive() && !m.isBaby()
                    && mod.getEntityTracker().isEntityReachable(m)) {
                double dSq = m.squaredDistanceTo(mod.getPlayer());
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    best = m;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean matchesPreferredId(MerchantEntity merchant, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return true;
        }
        if (merchant.getUuid() != null && merchant.getUuid().toString().equalsIgnoreCase(wanted)) {
            return true;
        }
        return Integer.toString(merchant.getId()).equals(wanted);
    }

    private Optional<ItemEntity> findNearbyMatchingDrop(AltoClef mod) {
        if (mod.getPlayer() == null) {
            return Optional.empty();
        }
        ItemEntity best = null;
        double bestDistSq = DROPPED_OUTPUT_RECOVERY_RANGE * DROPPED_OUTPUT_RECOVERY_RANGE;
        for (Entity entity : BotClientBridge.entities()) {
            if (entity instanceof ItemEntity itemEntity && itemEntity.isAlive()) {
                if (target.matches(itemEntity.getStack())) {
                    double dSq = itemEntity.squaredDistanceTo(mod.getPlayer());
                    if (dSq <= bestDistSq) {
                        bestDistSq = dSq;
                        best = itemEntity;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static MerchantScreenHandler getOpenMerchantHandler(AltoClef mod) {
        if (mod.getPlayer() != null && mod.getPlayer().currentScreenHandler instanceof MerchantScreenHandler handler) {
            return handler;
        }
        return null;
    }

    /**
     * Collects all player-owned stacks across player inventory, cursor, and (when merchant is open)
     * merchant payment slots 0 and 1. Excludes {@link MerchantSlot#OUTPUT_SLOT} so preview items
     * are never mistaken for owned items.
     */
    private List<ItemStack> collectOwnedStacksIncludingPaymentSlots(AltoClef mod) {
        List<ItemStack> stacks = new ArrayList<>(mod.getItemStorage().getItemStacksPlayerInventory(true));
        if (StorageHelper.isMerchantOpen()) {
            ItemStack p1 = StorageHelper.getItemStackInSlot(MerchantSlot.INPUT_SLOT_1);
            if (!p1.isEmpty()) stacks.add(p1);
            ItemStack p2 = StorageHelper.getItemStackInSlot(MerchantSlot.INPUT_SLOT_2);
            if (!p2.isEmpty()) stacks.add(p2);
        }
        return stacks;
    }

    private TradeInventorySnapshot snapshotInventory(AltoClef mod, TradeOfferSnapshot contextOffer) {
        int matchingInv = StorageHelper.countMatchingInventory(mod, target, false);
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        int matchingCursor = target.matches(cursor) ? cursor.getCount() : 0;

        List<ItemStack> owned = collectOwnedStacksIncludingPaymentSlots(mod);
        Map<String, Integer> counts = new LinkedHashMap<>();
        int totalEmeralds = 0;
        for (ItemStack s : owned) {
            if (s == null || s.isEmpty()) continue;
            String id = ItemComponentReader.itemId(s);
            if (!id.isEmpty()) {
                counts.merge(id, s.getCount(), Integer::sum);
                if (TradeOfferSnapshot.EMERALD_ID.equals(id)) {
                    totalEmeralds += s.getCount();
                }
            }
        }

        boolean hasSpace = hasInventorySpaceForTarget(mod, contextOffer);
        boolean droppedNearby = findNearbyMatchingDrop(mod).isPresent();
        return new TradeInventorySnapshot(matchingInv, matchingCursor, totalEmeralds, counts, hasSpace, droppedNearby);
    }

    private boolean hasInventorySpaceForTarget(AltoClef mod, TradeOfferSnapshot contextOffer) {
        if (mod.getItemStorage().hasEmptyInventorySlot()) {
            return true;
        }
        // Check if target item can stack into an existing slot (for stackable trade outputs).
        Item sampleItem = target.getMatches().length > 0 ? target.getMatches()[0] : Items.AIR;
        if (sampleItem != Items.AIR && sampleItem.getMaxCount() > 1) {
            if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(new ItemStack(sampleItem, 1), false).isPresent()) {
                return true;
            }
        }
        // If a single stack in inventory will be completely emptied when placed in payment slots, it frees a slot.
        if (contextOffer != null && mod.getPlayer() != null) {
            for (ItemStack s : mod.getItemStorage().getItemStacksPlayerInventory(false)) {
                if (s.isEmpty()) continue;
                String id = ItemComponentReader.itemId(s);
                if (id.equals(contextOffer.costAItemId()) && s.getCount() == contextOffer.costACount()) {
                    return true;
                }
                if (contextOffer.hasCostB() && id.equals(contextOffer.costBItemId()) && s.getCount() == contextOffer.costBCount()) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<TradeOfferSnapshot> snapshotOffers(AltoClef mod, MerchantScreenHandler handler) {
        List<ItemStack> owned = collectOwnedStacksIncludingPaymentSlots(mod);
        List<TradeOfferSnapshot> result = new ArrayList<>();
        //#if MC >= 260000
        //$$ net.minecraft.world.item.trading.MerchantOffers offers = handler.getOffers();
        //$$ if (offers == null) return result;
        //$$ for (int i = 0; i < offers.size(); i++) {
        //$$     net.minecraft.world.item.trading.MerchantOffer offer = offers.get(i);
        //$$     if (offer == null) continue;
        //$$     ItemStack costA = offer.getCostA();
        //$$     ItemStack costB = offer.getCostB();
        //$$     ItemStack out = offer.getResult();
        //$$     net.minecraft.world.item.trading.ItemCost itemCostA = offer.getItemCostA();
        //$$     java.util.Optional<net.minecraft.world.item.trading.ItemCost> itemCostB = offer.getItemCostB();
        //$$     boolean costAOk = countMatchingItemCost263(owned, itemCostA) >= costA.getCount();
        //$$     boolean costBOk = costB.isEmpty() || itemCostB.isEmpty()
        //$$             || countMatchingItemCost263(owned, itemCostB.get()) >= costB.getCount();
        //$$     result.add(new TradeOfferSnapshot(
        //$$             i,
        //$$             ItemComponentReader.itemId(costA),
        //$$             costA.getCount(),
        //$$             costAOk,
        //$$             ItemComponentReader.itemId(costB),
        //$$             costB.getCount(),
        //$$             costBOk,
        //$$             ItemComponentReader.itemId(out),
        //$$             out.getCount(),
        //$$             ItemComponentReader.readEnchantments(out),
        //$$             offer.getUses(),
        //$$             offer.getMaxUses(),
        //$$             offer.isOutOfStock()));
        //$$ }
        //#else
        net.minecraft.village.TradeOfferList offers = handler.getRecipes();
        if (offers == null) return result;
        for (int i = 0; i < offers.size(); i++) {
            net.minecraft.village.TradeOffer offer = offers.get(i);
            if (offer == null) continue;
            ItemStack costA = offer.getDisplayedFirstBuyItem();
            ItemStack costB = offer.getDisplayedSecondBuyItem();
            ItemStack out = offer.getSellItem();
            //#if MC >= 12005
            net.minecraft.village.TradedItem itemCostA = offer.getFirstBuyItem();
            java.util.Optional<net.minecraft.village.TradedItem> itemCostB = offer.getSecondBuyItem();
            boolean costAOk = countMatchingTradedItem(owned, itemCostA) >= costA.getCount();
            boolean costBOk = costB.isEmpty() || itemCostB.isEmpty()
                    || countMatchingTradedItem(owned, itemCostB.get()) >= costB.getCount();
            //#else
            //$$ boolean costAOk = true;
            //$$ boolean costBOk = true;
            //#endif
            result.add(new TradeOfferSnapshot(
                    i,
                    ItemComponentReader.itemId(costA),
                    costA.getCount(),
                    costAOk,
                    ItemComponentReader.itemId(costB),
                    costB.getCount(),
                    costBOk,
                    ItemComponentReader.itemId(out),
                    out.getCount(),
                    ItemComponentReader.readEnchantments(out),
                    offer.getUses(),
                    offer.getMaxUses(),
                    offer.isDisabled()));
        }
        //#endif
        return result;
    }

    //#if MC >= 260000
    //$$ private static int countMatchingItemCost263(List<ItemStack> owned, net.minecraft.world.item.trading.ItemCost cost) {
    //$$     if (cost == null) return 0;
    //$$     int total = 0;
    //$$     for (ItemStack s : owned) {
    //$$         if (s != null && !s.isEmpty() && cost.test(s)) {
    //$$             total += s.getCount();
    //$$         }
    //$$     }
    //$$     return total;
    //$$ }
    //#elseif MC >= 12005
    private static int countMatchingTradedItem(List<ItemStack> owned, net.minecraft.village.TradedItem cost) {
        if (cost == null) return 0;
        int total = 0;
        for (ItemStack s : owned) {
            if (s != null && !s.isEmpty() && cost.matches(s)) {
                total += s.getCount();
            }
        }
        return total;
    }
    //#endif

    private static boolean offerSatisfiedByPaymentSlots(MerchantScreenHandler handler,
                                                        int offerIndex,
                                                        ItemStack slot0,
                                                        ItemStack slot1) {
        //#if MC >= 260000
        //$$ net.minecraft.world.item.trading.MerchantOffers offers = handler.getOffers();
        //$$ if (offers == null || offerIndex < 0 || offerIndex >= offers.size()) return false;
        //$$ return offers.get(offerIndex).satisfiedBy(slot0, slot1);
        //#else
        net.minecraft.village.TradeOfferList offers = handler.getRecipes();
        if (offers == null || offerIndex < 0 || offerIndex >= offers.size()) return false;
        return offers.get(offerIndex).matchesBuyItems(slot0, slot1);
        //#endif
    }

    /**
     * Performs the exact ordinary-player offer selection sequence executed by {@code MerchantScreen.postButtonClick()}.
     */
    private static void selectOfferOnMerchant(MerchantScreenHandler handler, int offerIndex) {
        //#if MC >= 260000
        //$$ handler.setSelectionHint(offerIndex);
        //$$ handler.tryMoveItems(offerIndex);
        //$$ net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        //$$ if (mc != null && mc.getConnection() != null) {
        //$$     mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(offerIndex));
        //$$ }
        //#else
        handler.setRecipeIndex(offerIndex);
        handler.switchTo(offerIndex);
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket(offerIndex));
        }
        //#endif
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return true;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        return null;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof TradeWithVillagerTask that) {
            return this.request.equals(that.request);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Trade with villager for " + request.describeTarget();
    }
}
