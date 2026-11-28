package adris.altoclef.compose;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.compose.DemoStationBindings.Binding;
import adris.altoclef.compose.DemoStationBindings.StationPos;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockInteractEvent;
import adris.altoclef.eventbus.events.ScreenOpenEvent;
import adris.altoclef.eventbus.events.SlotClickChangedEvent;
import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.tasks.replenish.ContainerSlotSnapshot;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the live teach-by-demonstration recording session, editable draft routine, station bindings,
 * saved routine store, and verified replay lifecycle (executed through {@link CompositionService}).
 */
public final class DemoService {

    public static final DemoService INSTANCE = new DemoService();

    private final DemoRecorder recorder = new DemoRecorder();
    private final DemoRoutineStore store = DemoRoutineStore.standard();
    private final DemoStationBindings bindings = DemoStationBindings.standard();

    private boolean eventsInstalled = false;
    private DemoRoutineTask lastRoutineTask;
    private CompositionRun lastRun;

    private DemoService() {
    }

    public DemoRecorder recorder() {
        return recorder;
    }

    public DemoRoutineStore store() {
        return store;
    }

    public DemoStationBindings bindings() {
        return bindings;
    }

    public synchronized DemoRoutineTask lastRoutineTask() {
        return lastRoutineTask;
    }

    public synchronized CompositionRun lastRun() {
        return lastRun;
    }

    /** Subscribes to client block-interaction, screen-change, and slot-change events once. */
    public synchronized void ensureInstalled(AltoClef mod) {
        if (eventsInstalled) {
            return;
        }
        eventsInstalled = true;
        EventBus.subscribe(BlockInteractEvent.class, evt -> {
            if (!recorder.isRecording() || evt == null || evt.hitResult == null) {
                return;
            }
            AltoClef currentMod = AltoClef.getInstance();
            if (currentMod == null || currentMod.getWorld() == null) {
                return;
            }
            BlockPos pos = evt.hitResult.getBlockPos();
            Block block = currentMod.getWorld().getBlockState(pos).getBlock();
            if (ContainerType.getFromBlock(block) != ContainerType.EMPTY) {
                recorder.onBlockInteract(
                        WorldHelper.getCurrentDimension().name(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        classifyBlockType(block),
                        System.currentTimeMillis());
            }
        });
        EventBus.subscribe(ScreenOpenEvent.class, evt -> {
            if (!recorder.isRecording() || evt == null) {
                return;
            }
            if (!evt.preOpen && evt.screen == null && recorder.isScreenOpen()) {
                Optional<DemoStep> step = recorder.onScreenClosed(System.currentTimeMillis());
                step.ifPresent(s -> Debug.logMessage("TEACH: recorded step #" + recorder.steps().size() + ": " + s.formatSummaryLine(recorder.steps().size())));
            }
        });
        EventBus.subscribe(SlotClickChangedEvent.class, evt -> {
            if (recorder.isRecording()) {
                recorder.onSlotClickObserved();
            }
        });
    }

    /**
     * Polls client-visible container screens on each client tick while recording so 26.3 clients
     * (where ScreenOpenEvent / SlotClickChangedEvent mixins do not fire for every chest/barrel menu)
     * capture every confirmed slot and inventory delta reliably.
     */
    public void onClientTick(AltoClef mod) {
        if (!recorder.isRecording() || mod == null) {
            return;
        }
        PlayerEntity player = mod.getPlayer();
        if (player == null || mod.getWorld() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ScreenHandler handler = player.currentScreenHandler;
        if (DemoContainerSnapshots.isSupportedContainerHandler(handler)) {
            if (!recorder.isScreenOpen() || recorder.openSyncId() != handler.syncId) {
                StationPos fallbackPos = resolveFallbackContainerPos(mod);
                String fallbackType = fallbackPos == null ? ""
                        : classifyBlockType(mod.getWorld().getBlockState(new BlockPos(fallbackPos.x(), fallbackPos.y(), fallbackPos.z())).getBlock());
                recorder.onScreenOpened(handler.syncId, fallbackPos, fallbackType, now);
            }
            ContainerSlotSnapshot snap = DemoContainerSnapshots.capture(handler);
            recorder.onContainerTick(handler.syncId, snap, now);
        } else if (recorder.isScreenOpen()) {
            Optional<DemoStep> step = recorder.onScreenClosed(now);
            step.ifPresent(s -> Debug.logMessage("TEACH: recorded step #" + recorder.steps().size() + ": "
                    + s.formatSummaryLine(recorder.steps().size())));
        }
    }

    public String startRecording(AltoClef mod, List<String> stationHints) {
        ensureInstalled(mod);
        if (CompositionService.INSTANCE.isPerforming()) {
            return "Cannot start recording while a routine/composition is running. Stop it first.";
        }
        recorder.start(stationHints, bindings);
        String hintText = (stationHints == null || stationHints.isEmpty())
                ? ""
                : " with station hints " + stationHints;
        return "Recording started" + hintText
                + ". Perform your container transfers normally, then run '@teach stop'.";
    }

    public String stopRecordingOrReplay(AltoClef mod) {
        if (recorder.isRecording()) {
            DemoRoutine draft = recorder.stopRecording();
            // Bind any observed Site A containers automatically if they aren't already bound under another pos,
            // while still letting the player rename stations or rebind Site B cleanly.
            return "Recording stopped.\n" + draft.formatSummary();
        }
        if (CompositionService.INSTANCE.isPerforming()) {
            CompositionService.INSTANCE.stop(mod);
            return "Stopped active routine replay.";
        }
        return "Nothing active to stop.\n" + recorder.currentDraft().formatSummary();
    }

    public String cancel(AltoClef mod) {
        boolean stoppedAnything = false;
        if (recorder.isRecording()) {
            recorder.cancel();
            stoppedAnything = true;
        }
        if (CompositionService.INSTANCE.isPerforming()) {
            CompositionService.INSTANCE.stop(mod);
            stoppedAnything = true;
        }
        return stoppedAnything ? "Cancelled active teach/replay session." : "Nothing active to cancel.";
    }

    public String summary(AltoClef mod) {
        CompositionService.INSTANCE.reconcile(mod);
        StringBuilder sb = new StringBuilder();
        sb.append("Recorder state: ").append(recorder.state()).append('\n');
        sb.append(recorder.currentDraft().formatSummary());
        CompositionRun run = lastRun;
        if (run != null) {
            sb.append("\nLast replay (").append(run.compositionName).append("): ").append(run.state());
            if (run.failureKind() != CompositionRun.FailureKind.NONE) {
                sb.append(" [").append(run.failureKind()).append("] ").append(run.failureText());
            }
            sb.append(" (").append(run.durationMs()).append("ms)");
        }
        return sb.toString();
    }

    public String editField(int stepIndex, String field, String value) {
        DemoStep updated;
        switch (field.toLowerCase(Locale.ROOT)) {
            case "station" -> updated = recorder.renameStation(stepIndex, value);
            case "item" -> updated = recorder.editStep(stepIndex, null, value, null, null, false);
            case "count" -> {
                int qty;
                try {
                    qty = Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Expected integer count, got '" + value + "'");
                }
                if (qty <= 0) {
                    throw new IllegalArgumentException("Count must be > 0, got " + qty);
                }
                updated = recorder.editStep(stepIndex, null, null, qty, null, false);
            }
            case "action" -> {
                DemoStep.Action act = DemoCommandParser.parseStepAction(value);
                updated = recorder.editStep(stepIndex, act, null, null, null, false);
            }
            default -> throw new IllegalArgumentException("Unknown field '" + field + "'");
        }
        return "Updated step #" + stepIndex + ": " + updated.formatSummaryLine(stepIndex)
                + "\n" + recorder.currentDraft().formatSummary();
    }

    public String editFull(int stepIndex,
                           DemoStep.Action action,
                           int count,
                           String itemId,
                           String stationName,
                           boolean confirm) {
        DemoStep updated = recorder.editStep(stepIndex, action, itemId, count, stationName, confirm);
        return (confirm ? "Confirmed" : "Updated") + " step #" + stepIndex + ": "
                + updated.formatSummaryLine(stepIndex) + "\n" + recorder.currentDraft().formatSummary();
    }

    public String confirmStep(int stepIndex, DemoCommandParser.ParsedCommand parsed) {
        DemoStep updated;
        if (parsed.hasFullStepSpec()) {
            updated = recorder.editStep(stepIndex, parsed.stepAction(), parsed.itemId(),
                    parsed.count(), parsed.stationName(), true);
        } else {
            updated = recorder.editStep(stepIndex, null, null, null, null, true);
        }
        return "Confirmed step #" + stepIndex + ": " + updated.formatSummaryLine(stepIndex)
                + "\n" + recorder.currentDraft().formatSummary();
    }

    public String removeStep(int stepIndex) {
        DemoStep removed = recorder.removeStep(stepIndex);
        return "Removed step #" + stepIndex + " (" + removed.describeShort() + ")\n"
                + recorder.currentDraft().formatSummary();
    }

    public String saveDraft(String routineName) throws IOException {
        if (recorder.isRecording()) {
            recorder.stopRecording();
        }
        DemoRoutine draft = recorder.currentDraft().withName(routineName);
        if (draft.isEmpty()) {
            throw new IllegalArgumentException("No steps recorded to save.");
        }
        if (draft.hasUnresolvedSteps()) {
            throw new IllegalStateException("Refusing to save \"" + draft.name()
                    + "\": steps " + draft.unresolvedIndices()
                    + " are still UNRESOLVED. Edit/confirm them first with '@teach confirm <step#> <withdraw|deposit> <count> <item> <station>'.");
        }
        store.save(draft);
        // Remember observed Site A positions for any station not yet bound, without overriding explicit bindings.
        for (int i = 0; i < draft.steps().size(); i++) {
            DemoStep s = draft.steps().get(i);
            if (bindings.resolve(s.stationName()).isEmpty()) {
                recorder.observedPosForStep(i + 1).ifPresent(pos ->
                        bindings.bind(s.stationName(), pos.dimension(), pos.x(), pos.y(), pos.z()));
            }
        }
        recorder.loadForReview(draft);
        return "Saved routine \"" + draft.name() + "\".\n" + draft.formatSummary();
    }

    public String loadRoutine(String routineName) throws IOException {
        DemoRoutine loaded = store.load(routineName);
        recorder.loadForReview(loaded);
        return "Loaded routine \"" + loaded.name() + "\".\n" + loaded.formatSummary();
    }

    public String listRoutines() {
        List<String> names = store.list();
        return names.isEmpty() ? "No saved routines." : "Saved routines: " + names;
    }

    public String bindStation(AltoClef mod, String stationName, Integer x, Integer y, Integer z) {
        if (mod == null || mod.getWorld() == null || mod.getPlayer() == null) {
            throw new IllegalStateException("Not in a world.");
        }
        BlockPos pos;
        if (x != null && y != null && z != null) {
            pos = new BlockPos(x, y, z);
        } else {
            HitResult hit = BotClientBridge.crosshairTarget();
            if (hit instanceof BlockHitResult bhr) {
                pos = bhr.getBlockPos();
            } else {
                throw new IllegalArgumentException("Look at a storage container or pass explicit coords: @teach bind \""
                        + stationName + "\" <x> <y> <z>");
            }
        }
        if (mod.getChunkTracker().isChunkLoaded(pos)) {
            Block block = mod.getWorld().getBlockState(pos).getBlock();
            if (ContainerType.getFromBlock(block) == ContainerType.EMPTY) {
                throw new IllegalArgumentException("Block at " + pos.toShortString() + " is not a storage container.");
            }
        }
        Dimension dim = WorldHelper.getCurrentDimension();
        Binding bound = bindings.bind(stationName, dim.name(), pos.getX(), pos.getY(), pos.getZ());
        return "Bound station " + bound.describe();
    }

    public String listBindings() {
        Map<String, Binding> all = bindings.all();
        if (all.isEmpty()) {
            return "No stations bound (use '@teach bind <station> <x> <y> <z>').";
        }
        StringBuilder sb = new StringBuilder("Station bindings:");
        for (Binding b : all.values()) {
            sb.append("\n  ").append(b.describe());
        }
        return sb.toString();
    }

    /**
     * Loads a saved routine, resolves every station name through {@link DemoStationBindings},
     * and replays it through {@link CompositionService} using verified transfer steps.
     */
    public synchronized String runRoutine(AltoClef mod, String routineName) throws IOException {
        ensureInstalled(mod);
        if (recorder.isRecording()) {
            return "Refused: recording session is still active. Run '@teach stop' first.";
        }
        DemoRoutine routine = store.load(routineName);
        if (routine.isEmpty()) {
            return "Refused: routine \"" + routine.name() + "\" has no steps.";
        }
        if (routine.hasUnresolvedSteps()) {
            return "Refused: routine \"" + routine.name() + "\" has UNRESOLVED steps " + routine.unresolvedIndices();
        }
        if (!AltoClef.inGame() || mod == null || mod.getWorld() == null) {
            return "Refused: not in a world.";
        }

        List<DemoTransferStepTask> stepTasks = new ArrayList<>(routine.steps().size());
        for (int i = 0; i < routine.steps().size(); i++) {
            DemoStep step = routine.steps().get(i);
            Optional<Binding> opt = bindings.resolve(step.stationName());
            if (opt.isEmpty()) {
                return "Refused: station \"" + step.stationName() + "\" (step #" + (i + 1)
                        + ") is not bound. Bind it with: @teach bind \"" + step.stationName() + "\" <x> <y> <z>";
            }
            StationPos sp = opt.get().pos();
            Dimension dim;
            try {
                dim = Dimension.valueOf(sp.dimension());
            } catch (IllegalArgumentException e) {
                dim = Dimension.OVERWORLD;
            }
            stepTasks.add(new DemoTransferStepTask(i + 1, step, new BlockPos(sp.x(), sp.y(), sp.z()), dim));
        }

        DemoRoutineTask routineTask = new DemoRoutineTask(routine, stepTasks);
        String refused = CompositionService.INSTANCE.performComposition(mod, "demo:" + routine.name(), m -> routineTask);
        if (refused != null) {
            return "Refused: " + refused;
        }
        lastRoutineTask = routineTask;
        lastRun = CompositionService.INSTANCE.current();
        return "Replaying routine \"" + routine.name() + "\" (" + stepTasks.size() + " steps)";
    }

    private static StationPos resolveFallbackContainerPos(AltoClef mod) {
        Optional<BlockPos> last = mod.getItemStorage().getLastBlockPosInteraction();
        if (last.isPresent()) {
            BlockPos p = last.get();
            if (ContainerType.getFromBlock(mod.getWorld().getBlockState(p).getBlock()) != ContainerType.EMPTY) {
                return new StationPos(WorldHelper.getCurrentDimension().name(), p.getX(), p.getY(), p.getZ());
            }
        }
        HitResult hit = BotClientBridge.crosshairTarget();
        if (hit instanceof BlockHitResult bhr) {
            BlockPos p = bhr.getBlockPos();
            if (ContainerType.getFromBlock(mod.getWorld().getBlockState(p).getBlock()) != ContainerType.EMPTY) {
                return new StationPos(WorldHelper.getCurrentDimension().name(), p.getX(), p.getY(), p.getZ());
            }
        }
        return null;
    }

    private static String classifyBlockType(Block block) {
        if (block instanceof BarrelBlock) return "barrel";
        if (block instanceof ChestBlock) return "chest";
        if (block instanceof ShulkerBoxBlock) return "shulker_box";
        if (block instanceof HopperBlock) return "hopper";
        if (block instanceof DispenserBlock) return "dispenser";
        return "container";
    }
}
