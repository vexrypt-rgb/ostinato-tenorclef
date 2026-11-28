/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.IWorldData;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IRoadProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.process.ProcessOutcome;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import baritone.road.BuilderAccessPlan;
import baritone.road.ConstraintFailure;
import baritone.road.ProtectedZone;
import baritone.road.RoadAlignment;
import baritone.road.RoadPlanner;
import baritone.road.RoadProbe;
import baritone.road.RoadSchematic;
import baritone.road.RoadSpec;
import baritone.road.RoadSpecParser;
import baritone.road.RoadVerifier;
import baritone.road.RoadWorldProbe;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Ostinato process that owns terrain road survey, candidate-alignment preview, guarded staged execution through
 * {@link BuilderProcess}, and post-build physical verification against client-visible terrain.
 */
public final class RoadBuildProcess extends BaritoneProcessHelper implements IRoadProcess {

    public static final int MAX_STAGE_RETRIES = 3;
    public static final int STAGE_STALL_TICKS = 400;
    public static final int STAGE_TRAVEL_TICKS = 600;

    private enum Phase {
        IDLE,
        PREVIEW_READY,
        APPROACH_STAGE,
        STAGE_BUILDING,
        VERIFYING
    }

    private Phase phase = Phase.IDLE;
    private ProcessOutcome outcome = ProcessOutcome.NONE;
    private RoadPlanner.PlanResult lastPlan;
    private RoadVerifier.VerificationReport lastVerification;
    private Map<Long, RoadProbe.CellKind> preProtectedSnapshot = Map.of();

    private BlockState deckBlockState = Blocks.COBBLESTONE.defaultBlockState();
    private int stageIndex;
    private int stageRetries;
    private int totalRetries;
    private int stageTicks;
    private int stageStallTicks;
    private int stageLastRemaining = Integer.MAX_VALUE;
    private int travelTicks;
    private int actualPlacedBlocks;
    private String summary = "Road idle";
    private String lastError = "";

    // Restorable guards & settings so protected plots are never edited and settings stay clean on exit/cancel.
    private AltoClefSettings.CellGuard prevOstinatoGuard;
    private Predicate<BlockPos> installedPlaceAvoider;
    private Predicate<BlockPos> installedBreakAvoider;
    private boolean guardsInstalled;
    private boolean restoreAllowInventory;
    private boolean restoreBuildIgnoreExisting;
    private boolean restoreOkIfWater;
    private boolean restorePlaceInFluidSource;
    private boolean restorePlaceInFluidFlow;

    public RoadBuildProcess(Baritone baritone) {
        super(baritone);
    }

    public RoadPlanner.PlanResult lastPlan() {
        return lastPlan;
    }

    public RoadVerifier.VerificationReport lastVerification() {
        return lastVerification;
    }

    @Override
    public ProcessOutcome outcome() {
        return outcome;
    }

    @Override
    public String plan(String specText) {
        if (ctx.world() == null || ctx.entity() == null) {
            return "Road plan failed: no loaded client world";
        }
        if (isBuilding()) {
            cancel();
        }
        RoadSpec spec;
        try {
            spec = parseSpec(RoadSpecParser.tokenize(specText));
        } catch (IllegalArgumentException e) {
            outcome = outcome.accept().end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.INVALIDATED_GOAL);
            lastError = e.getMessage();
            return "Road plan error: " + e.getMessage();
        }

        RoadWorldProbe probe = new RoadWorldProbe(ctx.world());
        RoadPlanner.PlanResult result = RoadPlanner.plan(spec, probe);
        lastPlan = result;
        lastVerification = null;
        lastError = "";

        if (!result.feasible()) {
            phase = Phase.IDLE;
            outcome = outcome.accept().end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.REJECTED);
            summary = "Road infeasible: " + formatFailures(result.failures());
            return result.formatPreview();
        }

        BlockState resolvedDeck = resolveDeckBlock(spec.deckBlockId());
        if (resolvedDeck == null) {
            phase = Phase.IDLE;
            outcome = outcome.accept().end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.INVALIDATED_GOAL);
            lastError = "Unknown or non-full deck block '" + spec.deckBlockId() + "'";
            return "Road plan error: " + lastError;
        }
        deckBlockState = resolvedDeck;
        int stocked = countStockedBlocks(deckBlockState.getBlock());
        phase = Phase.PREVIEW_READY;
        outcome = outcome.accept();
        summary = "Road preview ready: placed=" + result.chosen().placedBlockCount()
                + "/" + spec.maxPlacedBlocks() + " stocked=" + stocked;
        return result.formatPreview()
                + String.format(Locale.ROOT, "\n  Inventory check: %d x %s stocked (%s) — run '#road accept' to build",
                stocked, spec.deckBlockId(),
                stocked >= result.chosen().placedBlockCount() ? "READY" : "SHORT " + (result.chosen().placedBlockCount() - stocked));
    }

    @Override
    public String preview() {
        if (lastPlan == null) {
            return "No road plan staged. Run '#road plan <from> <to> [options]' first.";
        }
        return lastPlan.formatPreview();
    }

    @Override
    public String accept() {
        if (ctx.world() == null || ctx.entity() == null) {
            return "Road build failed: no loaded client world";
        }
        if (lastPlan == null || !lastPlan.feasible() || lastPlan.chosen() == null) {
            outcome = outcome.accept().end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.REJECTED);
            return "No feasible road plan to accept. Run '#road plan <from> <to>' first.";
        }
        RoadSpec spec = lastPlan.spec();
        RoadAlignment chosen = lastPlan.chosen();

        if (chosen.placedBlockCount() > spec.maxPlacedBlocks()) {
            outcome = outcome.end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.REJECTED);
            lastError = ConstraintFailure.Kind.BUDGET_EXCEEDED + ": " + chosen.placedBlockCount() + " > " + spec.maxPlacedBlocks();
            return "Refusing to build: " + lastError;
        }

        BlockState resolvedDeck = resolveDeckBlock(spec.deckBlockId());
        if (resolvedDeck == null) {
            outcome = outcome.end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.INVALIDATED_GOAL);
            lastError = "Unknown deck block '" + spec.deckBlockId() + "'";
            return "Refusing to build: " + lastError;
        }
        deckBlockState = resolvedDeck;

        int stocked = countStockedBlocks(deckBlockState.getBlock());
        if (stocked < chosen.placedBlockCount()) {
            outcome = outcome.end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.MISSING_RESOURCE);
            lastError = ConstraintFailure.Kind.INSUFFICIENT_MATERIALS + ": need " + chosen.placedBlockCount()
                    + " x " + spec.deckBlockId() + ", inventory has " + stocked;
            return "Refusing to build (zero world edits): " + lastError;
        }

        RoadWorldProbe probe = new RoadWorldProbe(ctx.world());
        preProtectedSnapshot = RoadVerifier.snapshotProtectedZones(spec, probe);
        installGuards(spec);

        stageIndex = 0;
        stageRetries = 0;
        totalRetries = 0;
        stageTicks = 0;
        stageStallTicks = 0;
        stageLastRemaining = Integer.MAX_VALUE;
        travelTicks = 0;
        actualPlacedBlocks = 0;
        lastVerification = null;
        lastError = "";
        outcome = (outcome.terminal() || outcome.request() == 0 ? outcome.accept() : outcome).running();

        List<List<BuilderAccessPlan.BuildOp>> stages = chosen.accessPlan().stages();
        if (stages.isEmpty()) {
            phase = Phase.VERIFYING;
            summary = "Road already complete; verifying";
            return "Accepted road plan (0 edits needed); verifying world state.";
        }
        phase = Phase.APPROACH_STAGE;
        summary = String.format(Locale.ROOT, "Road building %s->%s (0/%d stages, budget %d/%d)",
                spec.startLabel(), spec.endLabel(), stages.size(), chosen.placedBlockCount(), spec.maxPlacedBlocks());
        return "Accepted road plan: building " + stages.size() + " stage(s), "
                + chosen.placedBlockCount() + "/" + spec.maxPlacedBlocks() + " placed blocks, "
                + chosen.clearedBlockCount() + " clears, planner="
                + String.format(Locale.ROOT, "%.2fms", lastPlan.plannerTimeMs());
    }

    @Override
    public String planAndBuild(String specText) {
        String previewText = plan(specText);
        if (lastPlan == null || !lastPlan.feasible()) {
            return previewText;
        }
        String acceptText = accept();
        return previewText + "\n" + acceptText;
    }

    @Override
    public String verify() {
        if (ctx.world() == null) {
            return "Road verify failed: no loaded client world";
        }
        if (lastPlan == null || lastPlan.chosen() == null) {
            return "Road verify: no planned alignment available";
        }
        RoadWorldProbe probe = new RoadWorldProbe(ctx.world());
        RoadVerifier.VerificationReport report = RoadVerifier.verify(
                lastPlan.spec(), lastPlan.chosen(), probe, preProtectedSnapshot, actualPlacedBlocks);
        lastVerification = report;
        return report.summary();
    }

    @Override
    public String status() {
        if (lastPlan == null) {
            return "Road " + phase + " outcome=" + outcome + (lastError.isEmpty() ? "" : " error=" + lastError);
        }
        RoadAlignment chosen = lastPlan.chosen();
        int stages = (chosen == null || chosen.accessPlan() == null) ? 0 : chosen.accessPlan().stages().size();
        return String.format(Locale.ROOT,
                "Road %s outcome=%s stage=%d/%d placed=%d/%d retries=%d planner=%.2fms%s%s",
                phase, outcome, Math.min(stageIndex, stages), stages,
                actualPlacedBlocks, lastPlan.spec().maxPlacedBlocks(),
                totalRetries, lastPlan.plannerTimeMs(),
                lastVerification == null ? "" : " | " + lastVerification.summary(),
                lastError.isEmpty() ? "" : " | err=" + lastError);
    }

    @Override
    public String cancel() {
        boolean wasBuilding = isBuilding();
        phase = Phase.IDLE;
        if (wasBuilding && baritone.getBuilderProcess().isActive()) {
            baritone.getBuilderProcess().onLostControl();
        }
        cleanupGuards();
        if (wasBuilding) {
            outcome = outcome.end(ProcessOutcome.State.CANCELLED, ProcessOutcome.Reason.CANCELLED);
            summary = "Road cancelled";
            return "Road build cancelled (guards released)";
        }
        lastPlan = null;
        if (!outcome.terminal() && outcome.request() != 0) {
            outcome = outcome.end(ProcessOutcome.State.CANCELLED, ProcessOutcome.Reason.CANCELLED);
        }
        summary = "Road idle";
        return "Road plan cleared";
    }

    public boolean isBuilding() {
        return phase == Phase.APPROACH_STAGE || phase == Phase.STAGE_BUILDING || phase == Phase.VERIFYING;
    }

    @Override
    public boolean isActive() {
        if (!isBuilding() || ctx.entity() == null || ctx.world() == null || lastPlan == null || lastPlan.chosen() == null) {
            return false;
        }
        if (phase == Phase.STAGE_BUILDING) {
            if (baritone.getBuilderProcess().isActive()) {
                stageTicks++;
                if (stageTicks % 20 == 0) {
                    int remaining = countRemainingStageOps(currentStage());
                    if (remaining < stageLastRemaining) {
                        stageLastRemaining = remaining;
                        stageStallTicks = 0;
                    } else if ((stageStallTicks += 20) >= STAGE_STALL_TICKS) {
                        // Bounded stall recovery: leave STAGE_BUILDING before cancelling builder to avoid re-entrancy.
                        phase = Phase.APPROACH_STAGE;
                        baritone.getBuilderProcess().onLostControl();
                        onStageFinished();
                        return isBuilding();
                    }
                }
                return false;
            }
            onStageFinished();
            return isBuilding();
        }
        return true;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!isBuilding() || lastPlan == null || lastPlan.chosen() == null) {
            return new PathingCommand((Goal) null, PathingCommandType.DEFER);
        }

        if (phase == Phase.VERIFYING) {
            finishAndVerify();
            return new PathingCommand((Goal) null, PathingCommandType.DEFER);
        }

        if (phase == Phase.APPROACH_STAGE) {
            List<List<BuilderAccessPlan.BuildOp>> stages = lastPlan.chosen().accessPlan().stages();
            if (stageIndex >= stages.size()) {
                phase = Phase.VERIFYING;
                return new PathingCommand((Goal) null, PathingCommandType.DEFER);
            }
            List<BuilderAccessPlan.BuildOp> stage = stages.get(stageIndex);
            BuilderAccessPlan.BuildOp first = stage.get(0);
            BlockPos stand = new BlockPos(first.standX(), first.standY(), first.standZ());
            BetterBlockPos feet = ctx.playerFeet();
            double dx = feet.x - stand.getX();
            double dz = feet.z - stand.getZ();
            if (Math.abs(feet.y - stand.getY()) > 2 || dx * dx + dz * dz > 16.0) {
                if (++travelTicks <= STAGE_TRAVEL_TICKS) {
                    summary = String.format(Locale.ROOT, "Road stage %d/%d approaching %s",
                            stageIndex + 1, stages.size(), stand.toShortString());
                    return new PathingCommand(new GoalNear(stand, 2), PathingCommandType.SET_GOAL_AND_PATH);
                }
            }
            travelTicks = 0;
            startCurrentStage(stage);
        }

        return new PathingCommand((Goal) null, PathingCommandType.DEFER);
    }

    private List<BuilderAccessPlan.BuildOp> currentStage() {
        List<List<BuilderAccessPlan.BuildOp>> stages = lastPlan.chosen().accessPlan().stages();
        return stageIndex < stages.size() ? stages.get(stageIndex) : List.of();
    }

    private void startCurrentStage(List<BuilderAccessPlan.BuildOp> stage) {
        RoadSpec spec = lastPlan.spec();
        int stagePlaces = 0;
        for (BuilderAccessPlan.BuildOp op : stage) {
            if (op.kind() == BuilderAccessPlan.OpKind.PLACE) stagePlaces++;
        }
        if (actualPlacedBlocks + stagePlaces > spec.maxPlacedBlocks()) {
            failBuild("Budget guard triggered before stage " + (stageIndex + 1) + ": "
                    + (actualPlacedBlocks + stagePlaces) + " > " + spec.maxPlacedBlocks());
            return;
        }

        RoadSchematic schematic = RoadSchematic.ofStage(spec, stage, deckBlockState);
        stageTicks = 0;
        stageStallTicks = 0;
        stageLastRemaining = countRemainingStageOps(stage);
        phase = Phase.STAGE_BUILDING;
        summary = String.format(Locale.ROOT, "Road building stage %d/%d (%d ops)",
                stageIndex + 1, lastPlan.chosen().accessPlan().stages().size(), stage.size());
        baritone.getBuilderProcess().build("road-stage-" + (stageIndex + 1), schematic, schematic.origin);
    }

    private void onStageFinished() {
        List<BuilderAccessPlan.BuildOp> stage = currentStage();
        int remaining = countRemainingStageOps(stage);
        if (remaining == 0) {
            for (BuilderAccessPlan.BuildOp op : stage) {
                if (op.kind() == BuilderAccessPlan.OpKind.PLACE) {
                    actualPlacedBlocks++;
                }
            }
            stageIndex++;
            stageRetries = 0;
            List<List<BuilderAccessPlan.BuildOp>> stages = lastPlan.chosen().accessPlan().stages();
            phase = stageIndex >= stages.size() ? Phase.VERIFYING : Phase.APPROACH_STAGE;
            return;
        }

        if (++stageRetries <= MAX_STAGE_RETRIES) {
            totalRetries++;
            phase = Phase.APPROACH_STAGE;
            summary = String.format(Locale.ROOT, "Road stage %d retry %d/%d (remaining=%d)",
                    stageIndex + 1, stageRetries, MAX_STAGE_RETRIES, remaining);
            return;
        }

        failBuild("Stage " + (stageIndex + 1) + " did not complete after " + MAX_STAGE_RETRIES
                + " retries (" + remaining + " op(s) remaining)");
    }

    private void finishAndVerify() {
        RoadWorldProbe probe = new RoadWorldProbe(ctx.world());
        RoadVerifier.VerificationReport report = RoadVerifier.verify(
                lastPlan.spec(), lastPlan.chosen(), probe, preProtectedSnapshot, actualPlacedBlocks);
        lastVerification = report;
        cleanupGuards();
        phase = Phase.IDLE;
        if (report.passed()) {
            outcome = outcome.end(ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE);
            summary = "Road complete: " + report.summary();
            logDirect(summary);
        } else {
            outcome = outcome.end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.WORLD_CHANGED);
            lastError = report.summary();
            summary = "Road verification failed: " + report.summary();
            logDirect(summary);
        }
    }

    private void failBuild(String reason) {
        if (baritone.getBuilderProcess().isActive()) {
            baritone.getBuilderProcess().onLostControl();
        }
        cleanupGuards();
        phase = Phase.IDLE;
        outcome = outcome.end(ProcessOutcome.State.FAILED, ProcessOutcome.Reason.UNREACHABLE);
        lastError = reason;
        summary = "Road failed: " + reason;
        logDirect(summary);
    }

    private int countRemainingStageOps(List<BuilderAccessPlan.BuildOp> stage) {
        if (ctx.world() == null) return stage.size();
        RoadWorldProbe probe = new RoadWorldProbe(ctx.world());
        int rem = 0;
        for (BuilderAccessPlan.BuildOp op : stage) {
            RoadProbe.CellKind k = probe.get(op.x(), op.y(), op.z());
            if (op.kind() == BuilderAccessPlan.OpKind.CLEAR) {
                if (!k.isPassableHeadroom()) rem++;
            } else {
                if (!k.isWalkableFullDeck()) rem++;
            }
        }
        return rem;
    }

    private void installGuards(RoadSpec spec) {
        cleanupGuards();
        AltoClefSettings acs = AltoClefSettings.getInstance();
        prevOstinatoGuard = acs.ostinatoGuard();
        AltoClefSettings.CellGuard prev = prevOstinatoGuard;
        acs.setOstinatoGuard((x, y, z) -> spec.isProtected(x, y, z) || (prev != null && prev.test(x, y, z)));

        Predicate<BlockPos> protPred = pos -> spec.isProtected(pos.getX(), pos.getY(), pos.getZ());
        installedPlaceAvoider = protPred;
        installedBreakAvoider = protPred;
        acs.avoidBlockPlace(installedPlaceAvoider);
        acs.avoidBlockBreak(installedBreakAvoider);

        if (!Baritone.settings().allowInventory.value) {
            Baritone.settings().allowInventory.value = true;
            restoreAllowInventory = true;
        }
        if (Baritone.settings().buildIgnoreExisting.value) {
            Baritone.settings().buildIgnoreExisting.value = false;
            restoreBuildIgnoreExisting = true;
        }
        if (Baritone.settings().okIfWater.value) {
            Baritone.settings().okIfWater.value = false;
            restoreOkIfWater = true;
        }
        if (!Baritone.settings().allowPlaceInFluidsSource.value) {
            Baritone.settings().allowPlaceInFluidsSource.value = true;
            restorePlaceInFluidSource = true;
        }
        if (!Baritone.settings().allowPlaceInFluidsFlow.value) {
            Baritone.settings().allowPlaceInFluidsFlow.value = true;
            restorePlaceInFluidFlow = true;
        }
        guardsInstalled = true;
    }

    private void cleanupGuards() {
        if (!guardsInstalled) {
            return;
        }
        AltoClefSettings acs = AltoClefSettings.getInstance();
        acs.setOstinatoGuard(prevOstinatoGuard);
        prevOstinatoGuard = null;
        if (installedPlaceAvoider != null) {
            acs.getPlaceAvoiders().remove(installedPlaceAvoider);
            installedPlaceAvoider = null;
        }
        if (installedBreakAvoider != null) {
            acs.getBreakAvoiders().remove(installedBreakAvoider);
            installedBreakAvoider = null;
        }
        if (restoreAllowInventory) {
            Baritone.settings().allowInventory.value = false;
            restoreAllowInventory = false;
        }
        if (restoreBuildIgnoreExisting) {
            Baritone.settings().buildIgnoreExisting.value = true;
            restoreBuildIgnoreExisting = false;
        }
        if (restoreOkIfWater) {
            Baritone.settings().okIfWater.value = true;
            restoreOkIfWater = false;
        }
        if (restorePlaceInFluidSource) {
            Baritone.settings().allowPlaceInFluidsSource.value = false;
            restorePlaceInFluidSource = false;
        }
        if (restorePlaceInFluidFlow) {
            Baritone.settings().allowPlaceInFluidsFlow.value = false;
            restorePlaceInFluidFlow = false;
        }
        guardsInstalled = false;
    }

    private RoadSpec parseSpec(List<String> tokens) {
        RoadSpecParser.EndpointResolver endpointResolver = token -> {
            if ("here".equalsIgnoreCase(token) && ctx.entity() != null) {
                BetterBlockPos feet = ctx.playerFeet();
                return new int[]{feet.x, feet.y, feet.z};
            }
            IWorldData worldData = baritone.getWorldProvider().getCurrentWorld();
            if (worldData != null && worldData.getWaypoints() != null) {
                for (IWaypoint wp : worldData.getWaypoints().getAllWaypoints()) {
                    if (wp.getName() != null && wp.getName().equalsIgnoreCase(token)) {
                        BetterBlockPos loc = wp.getLocation();
                        return new int[]{loc.x, loc.y, loc.z};
                    }
                }
            }
            return null;
        };

        RoadSpecParser.SelectionResolver selectionResolver = () -> {
            List<ProtectedZone> zones = new ArrayList<>();
            ISelection[] sels = baritone.getSelectionManager().getSelections();
            if (sels != null) {
                for (int i = 0; i < sels.length; i++) {
                    BetterBlockPos min = sels[i].min();
                    BetterBlockPos max = sels[i].max();
                    zones.add(ProtectedZone.box("sel" + (i + 1), min.x, min.y, min.z, max.x, max.y, max.z));
                }
            }
            return zones;
        };

        return RoadSpecParser.parse(tokens, endpointResolver, selectionResolver);
    }

    private static BlockState resolveDeckBlock(String blockId) {
        String id = blockId == null ? RoadSpec.DEFAULT_DECK_BLOCK_ID : blockId.trim().toLowerCase(Locale.ROOT);
        Identifier ident = id.contains(":") ? Identifier.tryParse(id) : Identifier.withDefaultNamespace(id);
        if (ident == null) {
            return null;
        }
        Optional<? extends Holder.Reference<Block>> holder = BuiltInRegistries.BLOCK.get(ident);
        if (holder.isEmpty()) {
            return null;
        }
        Block block = holder.get().value();
        if (block == Blocks.AIR) {
            return null;
        }
        return block.defaultBlockState();
    }

    private int countStockedBlocks(Block block) {
        if (ctx.entity() == null) return 0;
        Item target = block.asItem();
        int count = 0;
        var inv = ctx.entity().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem && stack.is(target)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static String formatFailures(List<ConstraintFailure.Detail> failures) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(failures.get(i).kind());
        }
        return sb.toString();
    }

    @Override
    public void onLostControl() {
        if (isBuilding()) {
            if (baritone.getBuilderProcess().isActive()) {
                baritone.getBuilderProcess().onLostControl();
            }
            cleanupGuards();
            phase = Phase.IDLE;
            outcome = outcome.end(ProcessOutcome.State.CANCELLED, ProcessOutcome.Reason.OWNERSHIP_LOST);
            summary = "Road cancelled";
        }
    }

    @Override
    public String displayName0() {
        return summary;
    }
}
