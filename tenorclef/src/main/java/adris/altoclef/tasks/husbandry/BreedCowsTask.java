package adris.altoclef.tasks.husbandry;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasksystem.FailureReason;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskResult;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.GoalNear;
//#if MC < 260000
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.CowEntity;
//#else
//$$ import net.minecraft.world.level.block.state.BlockState;
//$$ import net.minecraft.world.level.block.FenceGateBlock;
//$$ import net.minecraft.world.entity.Entity;
//$$ import net.minecraft.world.entity.animal.cow.Cow;
//#endif
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * High-level TenorClef task that grows the cow herd in a designated fenced pen to a target count.
 * <p>
 * Architectural & safety invariants:
 * <ul>
 *     <li>TenorClef owns husbandry policy via {@link HusbandrySession}; Ostinato supplies movement
 *         and entity interaction through {@code baritone.api.*} and player controller primitives.</li>
 *     <li>Uses strictly client-visible entity state ({@link BotClientBridge#entities()}, {@code isBaby()},
 *         {@code isAlive()}, chunk residency, and player wheat count).</li>
 *     <li>Protects pen perimeter fences, gates, and walls from breaking, block-placing, and gate-opening
 *         so the bot causes zero cow escapes.</li>
 *     <li>Feeds one eligible adult pair at a time, confirms wheat consumption, observes the resulting calf,
 *         recounts the pen, and stops immediately at the target count without overshooting.</li>
 * </ul>
 */
public final class BreedCowsTask extends Task {

    private static final double MAX_OBSERVATION_RANGE = 32.0;
    private static final int INTERACT_COOLDOWN_TICKS = 6;

    private final HusbandryRequest request;
    private final PenBounds pen;
    private final HusbandrySession session;

    private boolean behaviourPushed = false;
    private boolean cancelRequested = false;
    private boolean suspending = false;
    private long lastInteractTick = -100L;
    private BlockPos activeMoveGoal = null;
    private long activeMoveGoalSetTick = -100L;

    public BreedCowsTask(HusbandryRequest request, PenBounds pen) {
        this.request = Objects.requireNonNull(request, "request");
        this.pen = Objects.requireNonNull(pen, "pen");
        this.session = new HusbandrySession(request);
    }

    public HusbandryRequest getRequest() {
        return request;
    }

    public PenBounds getPen() {
        return pen;
    }

    public HusbandrySession getSession() {
        return session;
    }

    public HusbandryOutcome getOutcome() {
        return session.outcome();
    }

    public String getStatusLine() {
        return request.describe() + " @ pen" + pen.describe()
                + " [" + session.phase() + ", " + session.outcome() + "]";
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
        activeMoveGoal = null;
        activeMoveGoalSetTick = -100L;
        lastInteractTick = -100L;
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

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null && !behaviourPushed) {
            mod.getBehaviour().push();
            behaviourPushed = true;
            mod.getBehaviour().addProtectedItems(Items.WHEAT);
            // Never break pen fences/gates/walls or blocks underneath them.
            mod.getBehaviour().avoidBlockBreaking(pos ->
                    pen.isProtectedPerimeter(pos.getX(), pos.getY(), pos.getZ()));
            // Never place blocks on/next to the pen that could let cows jump out.
            mod.getBehaviour().avoidBlockPlacing(pos ->
                    pen.isProtectedPlacement(pos.getX(), pos.getY(), pos.getZ()));
            // Never open or walk through any fence gate!
            mod.getBehaviour().avoidWalkingThrough(pos ->
                    pen.isGate(pos.getX(), pos.getY(), pos.getZ()) || isFenceGate(mod, pos));
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null) {
            cancelPathingIfSafe(mod);
            if (behaviourPushed) {
                mod.getBehaviour().pop();
                behaviourPushed = false;
            }
        }
        activeMoveGoal = null;
        if (cancelRequested && !session.isTerminal()) {
            session.cancel("cancelled by user");
        } else if (!suspending && interruptTask == null && !session.isTerminal()) {
            session.cancel("stopped");
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getWorld() == null || mod.getPlayer() == null) {
            return null;
        }

        if (cancelRequested) {
            session.cancel("cancelled by user");
            cancelPathingIfSafe(mod);
            return null;
        }

        if (session.isTerminal()) {
            syncTerminalTaskResult();
            return null;
        }

        long tick = currentGameTick(mod);
        List<Entity> livePenCowEntities = new ArrayList<>();
        CowObservation.PenSnapshot snapshot = capturePenSnapshot(mod, tick, livePenCowEntities);
        HusbandrySession.Decision decision = session.evaluate(snapshot);

        switch (decision.kind()) {
            case COMPLETE -> {
                cancelPathingIfSafe(mod);
                deequipWheat(mod);
                setDebugState("Complete: " + decision.detail());
                syncTerminalTaskResult();
                return null;
            }
            case FAIL -> {
                cancelPathingIfSafe(mod);
                deequipWheat(mod);
                syncTerminalTaskResult();
                return null;
            }
            case APPROACH_PEN -> {
                setDebugState("Approaching pen: " + decision.detail());
                BlockPos vantage = chooseExteriorVantage(mod, pen.centerX(), pen.centerZ());
                pathToBlock(mod, vantage, 2, tick);
                return null;
            }
            case FREEZE_WAIT -> {
                cancelPathingIfSafe(mod);
                setDebugState("Frozen: " + decision.detail());
                return null;
            }
            case WAIT_FEED_CONFIRM -> {
                cancelPathingIfSafe(mod);
                setDebugState(decision.detail());
                return null;
            }
            case WAIT_FOR_BIRTH -> {
                cancelPathingIfSafe(mod);
                // De-equip wheat while waiting for birth so the two cows walk to each other instead of to the player!
                deequipWheat(mod);
                setDebugState(decision.detail() + " (" + (decision.waitRemainingTicks() / 20L) + "s)");
                return null;
            }
            case WAIT_COOLDOWN -> {
                cancelPathingIfSafe(mod);
                deequipWheat(mod);
                setDebugState(decision.detail() + " (" + (decision.waitRemainingTicks() / 20L) + "s)");
                return null;
            }
            case FEED_COW -> {
                return tickFeedCow(mod, decision.targetCowId(), livePenCowEntities, snapshot.playerWheatCount(), tick);
            }
        }
        return null;
    }

    private Task tickFeedCow(AltoClef mod,
                             int targetCowId,
                             List<Entity> livePenCowEntities,
                             int wheatCountNow,
                             long tick) {
        Entity target = null;
        for (Entity e : livePenCowEntities) {
            if (e.getId() == targetCowId && e.isAlive()) {
                target = e;
                break;
            }
        }
        if (target == null) {
            setDebugState("Target cow #" + targetCowId + " temporarily out of view");
            return null;
        }

        // Equip wheat first: holding wheat naturally tempts cows inside the pen up to the fence!
        if (!StorageHelper.isPlayerInventoryOpen()) {
            StorageHelper.closeScreen();
        }
        mod.getSlotHandler().forceEquipItem(Items.WHEAT);

        double reach = Math.min(3.0, mod.getModSettings().getEntityReachRange());
        double distSq = target.squaredDistanceTo(mod.getPlayer());
        boolean inRange = mod.getControllerExtras().inRange(target) && distSq <= reach * reach;

        if (!inRange) {
            setDebugState("Approaching cow #" + targetCowId + " along pen perimeter");
            BlockPos playerPos = mod.getPlayer().getBlockPos();
            boolean playerInsidePen = pen.containsBlockColumn(playerPos.getX(), playerPos.getZ())
                    && playerPos.getY() >= pen.minY() - 1 && playerPos.getY() <= pen.maxY() + 2;
            BlockPos standPos = playerInsidePen
                    ? target.getBlockPos()
                    : chooseExteriorVantage(mod, target.getX(), target.getZ());
            pathToBlock(mod, standPos, playerInsidePen ? 2 : 0, tick);
            // Look toward the cow once arrived at the perimeter while holding wheat to keep tempt attraction steady.
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                LookHelper.lookAt(mod, target.getEyePos());
            }
            return null;
        }

        // In range: stop moving, aim at cow, and feed wheat!
        cancelPathingIfSafe(mod);
        LookHelper.lookAt(mod, target.getEyePos());

        if (tick - lastInteractTick < INTERACT_COOLDOWN_TICKS) {
            setDebugState("Preparing to feed cow #" + targetCowId);
            return null;
        }

        if (!mod.getSlotHandler().forceEquipItem(Items.WHEAT)) {
            setDebugState("Equipping wheat for cow #" + targetCowId);
            return null;
        }

        lastInteractTick = tick;
        session.recordFeedAttempt(targetCowId, wheatCountNow, tick);
        setDebugState("Feeding wheat to cow #" + targetCowId);
        mod.getController().interactEntity(mod.getPlayer(), target, Hand.MAIN_HAND);
        return null;
    }

    private CowObservation.PenSnapshot capturePenSnapshot(AltoClef mod, long tick, List<Entity> outEntities) {
        boolean allLoaded = true;
        for (long chunkKey : pen.requiredChunkKeys()) {
            int cx = PenBounds.unpackX(chunkKey);
            int cz = PenBounds.unpackZ(chunkKey);
            BlockPos probe = new BlockPos(cx * 16 + 8, pen.minY(), cz * 16 + 8);
            if (!mod.getChunkTracker().isChunkLoaded(probe)) {
                allLoaded = false;
                break;
            }
        }

        double dx = mod.getPlayer().getX() - pen.centerX();
        double dy = mod.getPlayer().getY() - pen.centerY();
        double dz = mod.getPlayer().getZ() - pen.centerZ();
        boolean inObsRange = (dx * dx + dy * dy + dz * dz) <= MAX_OBSERVATION_RANGE * MAX_OBSERVATION_RANGE;

        List<CowObservation> cows = new ArrayList<>();
        if (allLoaded && inObsRange) {
            for (Entity entity : BotClientBridge.entities()) {
                if (!isPlainCow(entity) || !entity.isAlive()) {
                    continue;
                }
                if (!pen.containsPoint(entity.getX(), entity.getY(), entity.getZ())) {
                    continue;
                }
                boolean baby = isBabyCow(entity);
                cows.add(new CowObservation(
                        entity.getId(),
                        entity.getUuid(),
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        baby,
                        entity.isAlive()));
                outEntities.add(entity);
            }
        }

        int wheatCount = mod.getItemStorage().getItemCount(Items.WHEAT);
        return new CowObservation.PenSnapshot(tick, allLoaded, inObsRange, wheatCount, cows);
    }

    /**
     * Computes the closest walkable stand block strictly OUTSIDE the pen perimeter near (targetX, targetZ),
     * avoiding fence gates so the bot never walks through or opens a gate.
     */
    private BlockPos chooseExteriorVantage(AltoClef mod, double targetX, double targetZ) {
        BlockPos best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        int y = pen.minY();

        int minX = pen.minX() - 2;
        int maxX = pen.maxX() + 2;
        int minZ = pen.minZ() - 2;
        int maxZ = pen.maxZ() + 2;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Only inspect the exterior ring (outside interior and outside fence perimeter).
                if (pen.containsBlockColumn(x, z)) continue;
                if (pen.isProtectedPerimeter(x, y, z)) continue;
                boolean nearBox = x >= pen.minX() - 2 && x <= pen.maxX() + 2
                        && z >= pen.minZ() - 2 && z <= pen.maxZ() + 2;
                if (!nearBox) continue;

                // Must be adjacent to a fence/perimeter block (so the player is right at the pen edge).
                boolean adjacentToPerimeter =
                        pen.isProtectedPerimeter(x + 1, y, z)
                                || pen.isProtectedPerimeter(x - 1, y, z)
                                || pen.isProtectedPerimeter(x, y, z + 1)
                                || pen.isProtectedPerimeter(x, y, z - 1);
                if (!adjacentToPerimeter) continue;

                // Prefer spots NOT directly in front of a gate.
                boolean adjacentToGate =
                        pen.isGate(x + 1, y, z) || pen.isGate(x - 1, y, z)
                                || pen.isGate(x, y, z + 1) || pen.isGate(x, y, z - 1);

                BlockPos cand = findStandableY(mod, x, y, z);
                if (cand == null) continue;

                double ddx = (cand.getX() + 0.5) - targetX;
                double ddz = (cand.getZ() + 0.5) - targetZ;
                double score = ddx * ddx + ddz * ddz + (adjacentToGate ? 4.0 : 0.0);
                if (score < bestDistSq) {
                    bestDistSq = score;
                    best = cand;
                }
            }
        }

        if (best != null) {
            return best;
        }
        return new BlockPos(pen.minX() - 2, pen.minY(), (pen.minZ() + pen.maxZ()) / 2);
    }

    private static BlockPos findStandableY(AltoClef mod, int x, int baseY, int z) {
        int[] offsets = {0, 1, -1};
        for (int dy : offsets) {
            BlockPos feet = new BlockPos(x, baseY + dy, z);
            if (!mod.getChunkTracker().isChunkLoaded(feet)) continue;
            BlockState feetState = mod.getWorld().getBlockState(feet);
            BlockState headState = mod.getWorld().getBlockState(feet.up());
            BlockState belowState = mod.getWorld().getBlockState(feet.down());
            if (PenDetector.isFenceOrWall(feetState) || PenDetector.isFenceOrWall(headState)) continue;
            if (!isAirOrNonSolid(feet, feetState) || !isAirOrNonSolid(feet.up(), headState)) continue;
            if (!isAirOrNonSolid(feet.down(), belowState) && !PenDetector.isFenceOrWall(belowState)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean isAirOrNonSolid(BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) return true;
        return !WorldHelper.isSolidBlock(pos);
    }

    private void pathToBlock(AltoClef mod, BlockPos goalPos, int range, long tick) {
        if (goalPos == null) return;
        double distSq = mod.getPlayer().getBlockPos().getSquaredDistance(goalPos);
        if (distSq <= (double) range * range + 0.25) {
            cancelPathingIfSafe(mod);
            return;
        }
        boolean goalChanged = activeMoveGoal == null || !activeMoveGoal.equals(goalPos);
        boolean active = mod.getClientBaritone().getCustomGoalProcess().isActive();
        if (!active || (goalChanged && tick - activeMoveGoalSetTick >= 10L)) {
            activeMoveGoal = goalPos.toImmutable();
            activeMoveGoalSetTick = tick;
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(goalPos, range));
        }
    }

    private static void cancelPathingIfSafe(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
    }

    private static void deequipWheat(AltoClef mod) {
        if (mod.getPlayer() != null && mod.getPlayer().getMainHandStack().isOf(Items.WHEAT)) {
            mod.getSlotHandler().forceDeequip(stack -> stack.isOf(Items.WHEAT));
        }
    }

    private static boolean isFenceGate(AltoClef mod, BlockPos pos) {
        if (mod == null || mod.getWorld() == null || !mod.getChunkTracker().isChunkLoaded(pos)) {
            return false;
        }
        return mod.getWorld().getBlockState(pos).getBlock() instanceof FenceGateBlock;
    }

    private static boolean isPlainCow(Entity entity) {
        if (entity == null) return false;
        //#if MC >= 260000
        //$$ return entity instanceof Cow;
        //#else
        return entity.getClass() == CowEntity.class;
        //#endif
    }

    private static boolean isBabyCow(Entity entity) {
        //#if MC >= 260000
        //$$ return entity instanceof Cow cow && cow.isBaby();
        //#else
        return entity instanceof CowEntity cow && cow.isBaby();
        //#endif
    }

    private static long currentGameTick(AltoClef mod) {
        if (mod.getWorld() != null) {
            return mod.getWorld().getTime();
        }
        return 0L;
    }

    private void syncTerminalTaskResult() {
        HusbandryOutcome out = session.outcome();
        if (out.isSuccess()) {
            if (getExplicitResult() != TaskResult.SUCCESS) {
                Debug.logMessage("BREED COMPLETE: " + out);
                succeed();
            }
        } else if (out.isFailure()) {
            if (getExplicitResult() != TaskResult.FAILURE) {
                FailureReason reason = out.failureReason() != null ? out.failureReason() : FailureReason.UNKNOWN;
                Debug.logWarning("BREED FAILED: " + out);
                setDebugState("Breed failed: " + out.detail());
                fail(reason, out.detail(), false);
            }
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof BreedCowsTask task) {
            return request.equals(task.request) && pen.equals(task.pen);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Breed " + request.describe() + " in pen " + pen.describe();
    }
}
