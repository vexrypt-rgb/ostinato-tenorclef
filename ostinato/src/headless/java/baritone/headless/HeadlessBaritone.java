/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * The IPathingHost for a bot: the headless behaviours and world view, with every client process
 * (follow, mine, builder, explore, farm, custom goal, get-to-block, elytra, world provider, event bus,
 * selection, command manager, swarm, movement engine, predictive physics, Grace Note, threat, PvP,
 * secondo) absent. Nothing on the executor's path calls those; the ones that read them are configured
 * off, and the host reports them as null rather than inventing a client.
 */
package baritone.headless;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.cache.IWorldData;
import baritone.api.cache.IWorldProvider;
import baritone.api.command.manager.ICommandManager;
import baritone.api.event.listener.IEventBus;
import baritone.api.gracenote.IGraceNote;
import baritone.api.movement.IMovementEngine;
import baritone.api.movement.MovementBackendKind;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.physics.IPredictivePhysics;
import baritone.api.policy.IAgentPolicy;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.movement.IMovement;
import baritone.api.process.*;
import baritone.api.selection.ISelectionManager;
import baritone.api.swarm.ISwarm;
import baritone.api.threat.IThreatAssessment;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IWorldQueries;
import baritone.pathing.IPathingHost;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.unified.UnifiedExecutor;
import baritone.process.MineProcess;
import baritone.utils.BlockBreakHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputArbiter;
import baritone.utils.ToolSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class HeadlessBaritone implements IPathingHost {

    private final HeadlessPlayerContext ctx;
    private final HeadlessBlockStateInterface bsi;
    private final HeadlessInputOverrideHandler input;
    private final HeadlessLookBehavior look;
    private final HeadlessPathingBehavior pathing;
    private final BlockBreakHelper breakHelper;
    private final ICustomGoalProcess customGoalProcess;
    private final IExploreProcess exploreProcess;
    private final IBuilderProcess builderProcess;
    private final IGetToBlockProcess getToBlockProcess;
    private final IMineProcess mineProcess;
    private final IFollowProcess followProcess;
    private final IWorldQueries worldQueries;

    private CalculationContext context;

    public HeadlessBaritone(HeadlessPlayerContext ctx, HeadlessBlockStateInterface bsi,
                            HeadlessInputOverrideHandler input, HeadlessLookBehavior look) {
        this.ctx = ctx;
        this.bsi = bsi;
        this.input = input;
        this.look = look;
        this.breakHelper = new BlockBreakHelper(ctx);
        this.pathing = new HeadlessPathingBehavior(this);
        this.customGoalProcess = new HeadlessCustomGoalProcess();
        this.exploreProcess = new HeadlessExploreProcess();
        this.builderProcess = new HeadlessBuilderProcess();
        this.getToBlockProcess = new HeadlessGetToBlockProcess();
        this.mineProcess = new HeadlessMineProcess();
        this.followProcess = new HeadlessFollowProcess();
        this.worldQueries = new HeadlessWorldQueries();
    }

    /** The context the last search was planned with; re-costing reads it instead of a client world. */
    public void setCalculationContext(CalculationContext context) {
        this.context = context;
    }

    // ------------------------------------------------------------------ IPathingHost

    @Override
    public BlockStateInterface bsi() {
        return bsi;
    }

    @Override
    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.empty();
    }

    @Override
    public BlockBreakHelper blockBreakHelper() {
        return breakHelper;
    }

    @Override
    public boolean hasGenericThrowaway() {
        // Ostinato: hotbar, offhand and the main inventory all count. There is no LocalPlayer to ask.
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false;
        }
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (AltoClefSettings.getInstance().isItemProtected(item)) {
                continue;
            }
            if (findThrowaway(item, false)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        // Ostinato: no builder process on this host. Acceptable throwaways, then a hotbar select.
        // select(true) cannot SWAP a backpack stack: that click needs a hashed slot map we do not send.
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false;
        }
        if (AltoClefSettings.getInstance().shouldAvoidPlacingAt(x, y, z)) {
            return false;
        }
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (AltoClefSettings.getInstance().isItemProtected(item)) {
                continue;
            }
            if (findThrowaway(item, select)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hotbar first, then offhand, then main inventory slots 9-35.
     * Selecting the offhand holds an empty or non-block hotbar slot so the place path can try OFF_HAND.
     */
    private boolean findThrowaway(Item item, boolean select) {
        Inventory inv = ctx.entity().getInventory();
        synchronized (inv) {
            var items = inv.getNonEquipmentItems();
            int hotbar = Math.min(Inventory.SELECTION_SIZE, items.size());
            for (int i = 0; i < hotbar; i++) {
                ItemStack stack = items.get(i);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    if (select) {
                        inv.setSelectedSlot(i);
                        ctx.playerController().syncHeldItem();
                    }
                    return true;
                }
            }
            ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
            if (!off.isEmpty() && off.getItem() == item) {
                if (!select) {
                    return true;
                }
                for (int i = 0; i < hotbar; i++) {
                    ItemStack hot = items.get(i);
                    if (hot.isEmpty() || !(hot.getItem() instanceof BlockItem)) {
                        inv.setSelectedSlot(i);
                        ctx.playerController().syncHeldItem();
                        return true;
                    }
                }
            }
            if (select) {
                return false;
            }
            for (int i = Inventory.SELECTION_SIZE; i < items.size() && i < Inventory.INVENTORY_SIZE; i++) {
                ItemStack stack = items.get(i);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Ostinato: count of acceptable throwaway blocks in the inventory mirror (hotbar, offhand, main 9-35). */
    int countGenericThrowaway() {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return 0;
        }
        int n = 0;
        Inventory inv = ctx.entity().getInventory();
        synchronized (inv) {
            var items = inv.getNonEquipmentItems();
            for (int i = 0; i < items.size() && i < Inventory.INVENTORY_SIZE; i++) {
                ItemStack stack = items.get(i);
                if (isCountedThrowaway(stack)) {
                    n += stack.getCount();
                }
            }
            ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
            if (isCountedThrowaway(off)) {
                n += off.getCount();
            }
        }
        return n;
    }

    /** Ostinato: selected slot, hotbar, and offhand for A* diagnostics. */
    String describeHotbar() {
        Inventory inv = ctx.entity().getInventory();
        synchronized (inv) {
            var items = inv.getNonEquipmentItems();
            StringBuilder sb = new StringBuilder("sel=").append(inv.getSelectedSlot());
            int hotbar = Math.min(Inventory.SELECTION_SIZE, items.size());
            for (int i = 0; i < hotbar; i++) {
                ItemStack stack = items.get(i);
                if (!stack.isEmpty()) {
                    sb.append(" [").append(i).append(']').append(stack.getCount()).append('x').append(stack.getItem());
                }
            }
            ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
            if (!off.isEmpty()) {
                sb.append(" off=").append(off.getCount()).append('x').append(off.getItem());
            }
            return sb.toString();
        }
    }

    private static boolean isCountedThrowaway(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (AltoClefSettings.getInstance().isItemProtected(item)) {
            return false;
        }
        return Baritone.settings().acceptableThrowawayItems.value.contains(item);
    }

    @Override
    public UnifiedExecutor getUnifiedExecutor() {
        return null;
    }

    @Override
    public InputArbiter getInputArbiter() {
        return null;
    }

    // ------------------------------------------------------------------ IBaritone

    @Override
    public HeadlessPathingBehavior getPathingBehavior() {
        return pathing;
    }

    @Override
    public ILookBehavior getLookBehavior() {
        return look;
    }

    @Override
    public IInputOverrideHandler getInputOverrideHandler() {
        return input;
    }

    @Override
    public IPlayerContext getPlayerContext() {
        return ctx;
    }

    @Override
    public IFollowProcess getFollowProcess() {
        return followProcess;
    }

    @Override
    public IMineProcess getMineProcess() {
        return mineProcess;
    }

    @Override
    public IBuilderProcess getBuilderProcess() {
        return builderProcess;
    }

    @Override
    public IExploreProcess getExploreProcess() {
        return exploreProcess;
    }

    @Override
    public IFarmProcess getFarmProcess() {
        return null;
    }

    @Override
    public ICustomGoalProcess getCustomGoalProcess() {
        return customGoalProcess;
    }

    @Override
    public IGetToBlockProcess getGetToBlockProcess() {
        return getToBlockProcess;
    }

    @Override
    public IElytraProcess getElytraProcess() {
        return null;
    }

    @Override
    public IWorldProvider getWorldProvider() {
        return null;
    }

    @Override
    public IPathingControlManager getPathingControlManager() {
        return null;
    }

    @Override
    public IEventBus getGameEventHandler() {
        return null;
    }

    @Override
    public ISelectionManager getSelectionManager() {
        return null;
    }

    @Override
    public ICommandManager getCommandManager() {
        return null;
    }

    @Override
    public void openClick() {
    }

    @Override
    public IAgentPolicy getAgentPolicy() {
        return AltoClefSettings.getInstance();
    }

    @Override
    public IPvpProcess getPvpProcess() {
        return null;
    }

    @Override
    public ISwarm getSwarm() {
        return null;
    }

    @Override
    public IMovementEngine getMovementEngine() {
        return null;
    }

    @Override
    public MovementBackendKind getMovementBackendPreference() {
        return null;
    }

    @Override
    public void setFreecamStatus(Supplier<String> status) {
    }

    @Override
    public IWorldQueries getWorldQueries() {
        return worldQueries;
    }

    @Override
    public IPredictivePhysics getPredictivePhysics() {
        return null;
    }

    @Override
    public IThreatAssessment getThreatAssessment() {
        return null;
    }

    @Override
    public IGraceNote getGraceNote() {
        return null;
    }

    @Override
    public ISecondoProcess getSecondoProcess() {
        return null;
    }

    private final class HeadlessCustomGoalProcess implements ICustomGoalProcess {
        private Goal goal;
        private Goal mostRecentGoal;
        private boolean active;

        @Override
        public void setGoal(Goal goal) {
            this.goal = goal;
            if (goal != null) {
                this.mostRecentGoal = goal;
            }
        }

        @Override
        public void path() {
            if (goal != null) {
                active = true;
                pathing.gotoGoal(goal);
            }
        }

        @Override
        public Goal getGoal() {
            return goal;
        }

        @Override
        public Goal mostRecentGoal() {
            return mostRecentGoal;
        }

        @Override
        public boolean isActive() {
            if (active && !pathing.isPathing() && !pathing.planning()) {
                active = false;
            }
            return active;
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
        }

        @Override
        public boolean isTemporary() {
            return false;
        }

        @Override
        public void onLostControl() {
            if (active) {
                active = false;
                goal = null;
                pathing.cancelEverything();
            } else {
                goal = null;
            }
        }

        @Override
        public String displayName0() {
            return "HeadlessCustomGoal";
        }
    }

    private final class HeadlessExploreProcess implements IExploreProcess {
        private boolean active;

        @Override
        public void explore(int centerX, int centerZ) {
            active = true;
            pathing.gotoGoal(new GoalXZ(centerX, centerZ));
        }

        @Override
        public void applyJsonFilter(Path path, boolean invert) {
        }

        @Override
        public boolean isActive() {
            if (active && !pathing.isPathing() && !pathing.planning()) {
                active = false;
            }
            return active;
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        @Override
        public boolean isTemporary() {
            return false;
        }

        @Override
        public void onLostControl() {
            if (active) {
                active = false;
                pathing.cancelEverything();
            }
        }

        @Override
        public String displayName0() {
            return "HeadlessExplore";
        }
    }

    private static final class HeadlessBuilderProcess implements IBuilderProcess {
        @Override
        public void build(String name, baritone.api.schematic.ISchematic schematic, Vec3i origin) {}

        @Override
        public boolean build(String name, File schematic, Vec3i origin) { return false; }

        @Override
        public boolean buildRegion(String name, File schematic, Vec3i origin, int regionIndex,
                                   baritone.api.schematic.partition.PartitionPlan plan) { return false; }

        @Override
        public void buildOpenSchematic() {}

        @Override
        public void buildOpenLitematic(int i) {}

        @Override
        public void pause() {}

        @Override
        public boolean isPaused() { return false; }

        @Override
        public void popStack() {}

        @Override
        public boolean isFromAltoclefFinished() { return true; }

        @Override
        public void resume() {}

        @Override
        public void clearArea(BlockPos corner1, BlockPos corner2) {}

        @Override
        public List<BlockState> getApproxPlaceable() { return List.of(); }

        @Override
        public Optional<Integer> getMinLayer() { return Optional.empty(); }

        @Override
        public Optional<Integer> getMaxLayer() { return Optional.empty(); }

        @Override
        public boolean isActive() { return false; }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        @Override
        public boolean isTemporary() { return false; }

        @Override
        public void onLostControl() {}

        @Override
        public String displayName0() { return "HeadlessBuilder"; }
    }

    private static final class HeadlessGetToBlockProcess implements IGetToBlockProcess {
        @Override
        public void getToBlock(BlockOptionalMeta block) {}

        @Override
        public boolean blacklistClosest() { return false; }

        @Override
        public boolean isActive() { return false; }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        @Override
        public boolean isTemporary() { return false; }

        @Override
        public void onLostControl() {}

        @Override
        public String displayName0() { return "HeadlessGetToBlock"; }
    }

    private static final class HeadlessMineProcess implements IMineProcess {
        @Override
        public void mineByName(int quantity, String... blocks) {}

        @Override
        public void mine(int quantity, BlockOptionalMetaLookup filter) {}

        @Override
        public boolean isActive() { return false; }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        @Override
        public boolean isTemporary() { return false; }

        @Override
        public void onLostControl() {}

        @Override
        public String displayName0() { return "HeadlessMine"; }
    }

    private static final class HeadlessFollowProcess implements IFollowProcess {
        private Predicate<Entity> filter;

        @Override
        public void follow(Predicate<Entity> filter) {
            this.filter = filter;
        }

        @Override
        public void pickup(Predicate<ItemStack> filter) {}

        @Override
        public List<Entity> following() { return List.of(); }

        @Override
        public Predicate<Entity> currentFilter() { return filter; }

        @Override
        public boolean isActive() { return false; }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        @Override
        public boolean isTemporary() { return false; }

        @Override
        public void onLostControl() { this.filter = null; }

        @Override
        public String displayName0() { return "HeadlessFollow"; }
    }

    private final class HeadlessWorldQueries implements IWorldQueries {
        @Override
        public boolean isWater(BlockState state) {
            return MovementHelper.isWater(state);
        }

        @Override
        public boolean isLava(BlockState state) {
            return MovementHelper.isLava(state);
        }

        @Override
        public boolean canPlaceAgainst(BlockPos pos) {
            return MovementHelper.canPlaceAgainst(bsi, pos.getX(), pos.getY(), pos.getZ(), bsi.get0(pos));
        }

        @Override
        public boolean avoidBreaking(BlockPos pos) {
            return MovementHelper.avoidBreaking(bsi, pos.getX(), pos.getY(), pos.getZ(), bsi.get0(pos));
        }

        @Override
        public boolean plausibleToBreak(BlockPos pos) {
            CalculationContext calc = new CalculationContext(
                    HeadlessBaritone.this, bsi, new ToolSet(ctx.entity()),
                    hasGenericThrowaway(), bsi.minY(), bsi.height());
            return MineProcess.plausibleToBreak(calc, pos);
        }

        @Override
        public double toolSpeedVsBlock(ItemStack tool, BlockState state) {
            return ToolSet.calculateSpeedVsBlock(tool, state);
        }

        @Override
        public List<BlockPos> blocksToBreak(IMovement movement) {
            return movement instanceof Movement m ? m.toBreak(bsi) : Collections.emptyList();
        }

        @Override
        public List<BlockPos> blocksToPlace(IMovement movement) {
            return movement instanceof Movement m ? m.toPlace(bsi) : Collections.emptyList();
        }
    }
}
