package adris.altoclef.compose;

import adris.altoclef.compose.DemoStationBindings.StationPos;
import adris.altoclef.tasks.replenish.ContainerSlotSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-Java state machine that observes block interactions, container screen open/close transitions,
 * and confirmed slot/inventory snapshots during an explicit recording session to infer semantic
 * {@link DemoStep} actions ({@code WITHDRAW} / {@code DEPOSIT}).
 * <p>
 * Key safety invariants:
 * <ul>
 *     <li>Initial container-sync packets (where container contents populate with zero player/cursor change)
 *     refresh the open baseline rather than producing phantom deposits.</li>
 *     <li>Pure inspection or internal slot rearrangement (net zero delta) produces no step.</li>
 *     <li>Any ambiguous transfer (multiple items moved, conservation mismatch between container and player
 *     deltas, unsettled cursor on close, or unidentified container block) is explicitly recorded as
 *     {@link DemoStep.Action#UNRESOLVED} until the player edits and confirms it.</li>
 * </ul>
 */
public final class DemoRecorder {

    public enum State {
        IDLE,
        RECORDING,
        REVIEWING
    }

    /** Number of initial open ticks during which pure container-sync packets refresh the baseline. */
    public static final int SYNC_SETTLE_TICKS = 5;
    /** Max age of a block interaction to associate it with a newly opened container screen. */
    public static final long INTERACT_WINDOW_MS = 5_000L;

    private State state = State.IDLE;
    private String draftName = "draft";
    private final List<DemoStep> steps = new ArrayList<>();
    private final List<StationPos> stepObservedPositions = new ArrayList<>();
    private final List<String> stationHints = new ArrayList<>();
    private int nextHintIndex = 0;
    private DemoStationBindings referenceBindings;
    private final Map<StationPos, String> sessionStationNames = new LinkedHashMap<>();
    private int autoStationCounter = 0;

    // Last observed block interaction
    private StationPos lastInteractPos;
    private String lastInteractBlockType = "";
    private long lastInteractMs = 0L;

    // Active open container visit
    private boolean screenOpen = false;
    private int openSyncId = -1;
    private StationPos openPos;
    private String openBlockType = "";
    private int openTicks = 0;
    private boolean baselineLocked = false;
    private ContainerSlotSnapshot baseline;
    private ContainerSlotSnapshot lastSnapshot;

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isRecording() {
        return state == State.RECORDING;
    }

    public synchronized boolean isScreenOpen() {
        return screenOpen;
    }

    public synchronized int openSyncId() {
        return openSyncId;
    }

    /** Starts a fresh explicit recording session. */
    public synchronized void start(List<String> optionalHints, DemoStationBindings existingBindings) {
        state = State.RECORDING;
        draftName = "draft";
        steps.clear();
        stepObservedPositions.clear();
        stationHints.clear();
        if (optionalHints != null) {
            for (String h : optionalHints) {
                String d = DemoStep.normalizeStationDisplay(h);
                if (!d.isEmpty()) {
                    DemoStep.normalizeStationKey(d); // validate upfront
                    stationHints.add(d);
                }
            }
        }
        nextHintIndex = 0;
        referenceBindings = existingBindings;
        sessionStationNames.clear();
        autoStationCounter = 0;
        clearInteractionState();
        clearOpenVisitState();
    }

    /** Loads an existing routine into REVIEWING state for inspection or editing. */
    public synchronized void loadForReview(DemoRoutine routine) {
        state = State.REVIEWING;
        draftName = routine.name();
        steps.clear();
        steps.addAll(routine.steps());
        stepObservedPositions.clear();
        for (int i = 0; i < steps.size(); i++) {
            stepObservedPositions.add(null);
        }
        clearInteractionState();
        clearOpenVisitState();
    }

    /** Cancels recording or clears transient open state. */
    public synchronized void cancel() {
        clearInteractionState();
        clearOpenVisitState();
        if (state == State.RECORDING) {
            state = steps.isEmpty() ? State.IDLE : State.REVIEWING;
        }
    }

    /** Records an ordinary client right-click block interaction on a storage container block. */
    public synchronized void onBlockInteract(String dimension, int x, int y, int z, String blockType, long nowMs) {
        if (state != State.RECORDING) {
            return;
        }
        lastInteractPos = new StationPos(dimension, x, y, z);
        lastInteractBlockType = blockType == null ? "" : blockType.trim().toLowerCase(Locale.ROOT);
        lastInteractMs = nowMs;
    }

    /**
     * Marks a container screen open. If {@code fallbackPos} is supplied (e.g. from ContainerSubTracker or
     * crosshair when BlockInteractEvent wasn't seen), uses it only if no fresh interaction exists.
     */
    public synchronized void onScreenOpened(int syncId,
                                            StationPos fallbackPos,
                                            String fallbackBlockType,
                                            long nowMs) {
        if (state != State.RECORDING) {
            return;
        }
        if (screenOpen && openSyncId == syncId) {
            return;
        }
        if (screenOpen) {
            finalizeOpenVisit();
        }
        screenOpen = true;
        openSyncId = syncId;
        if (lastInteractPos != null && (nowMs - lastInteractMs) <= INTERACT_WINDOW_MS) {
            openPos = lastInteractPos;
            openBlockType = lastInteractBlockType;
        } else if (fallbackPos != null) {
            openPos = fallbackPos;
            openBlockType = fallbackBlockType == null ? "" : fallbackBlockType.trim().toLowerCase(Locale.ROOT);
        } else {
            openPos = null;
            openBlockType = "";
        }
        openTicks = 0;
        baselineLocked = false;
        baseline = null;
        lastSnapshot = null;
    }

    /** Notes an explicit slot click event so the baseline locks immediately against subsequent changes. */
    public synchronized void onSlotClickObserved() {
        if (state == State.RECORDING && screenOpen && baseline != null) {
            baselineLocked = true;
        }
    }

    /**
     * Observes the live client-visible container snapshot on each tick while a container screen is open.
     */
    public synchronized void onContainerTick(int syncId, ContainerSlotSnapshot snapshot, long nowMs) {
        if (state != State.RECORDING || snapshot == null) {
            return;
        }
        if (!screenOpen || openSyncId != syncId) {
            onScreenOpened(syncId, null, "", nowMs);
        }
        openTicks++;
        if (baseline == null) {
            baseline = snapshot;
            lastSnapshot = snapshot;
            return;
        }
        if (!baselineLocked) {
            boolean playerChanged = !playerCounts(baseline).equals(playerCounts(snapshot));
            boolean cursorChanged = !baseline.cursorEmpty() || !snapshot.cursorEmpty();
            if (playerChanged || cursorChanged) {
                baselineLocked = true;
            } else if (openTicks <= SYNC_SETTLE_TICKS && !containerCounts(baseline).equals(containerCounts(snapshot))) {
                // Server initial container contents packet arrived while player/cursor stayed untouched.
                baseline = snapshot;
            } else if (openTicks > SYNC_SETTLE_TICKS) {
                baselineLocked = true;
            }
        }
        lastSnapshot = snapshot;
    }

    /** Finalizes the open container visit when the screen closes. */
    public synchronized Optional<DemoStep> onScreenClosed(long nowMs) {
        if (state != State.RECORDING || !screenOpen) {
            return Optional.empty();
        }
        return finalizeOpenVisit();
    }

    /** Stops recording, finalizes any open container visit, and transitions to REVIEWING. */
    public synchronized DemoRoutine stopRecording() {
        if (state == State.RECORDING) {
            if (screenOpen) {
                finalizeOpenVisit();
            }
            state = State.REVIEWING;
        }
        clearInteractionState();
        clearOpenVisitState();
        return currentDraft();
    }

    public synchronized DemoRoutine currentDraft() {
        return new DemoRoutine(draftName, steps);
    }

    public synchronized List<DemoStep> steps() {
        return Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /** Returns the observed container position for a 1-based step index, if recorded in this session. */
    public synchronized Optional<StationPos> observedPosForStep(int oneBasedIndex) {
        int idx = oneBasedIndex - 1;
        if (idx < 0 || idx >= stepObservedPositions.size()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stepObservedPositions.get(idx));
    }

    /**
     * Binds all container positions observed during this recording session under their current
     * (possibly renamed) station names.
     */
    public synchronized int bindObservedStations(DemoStationBindings bindings) {
        if (bindings == null) {
            return 0;
        }
        int bound = 0;
        for (int i = 0; i < steps.size(); i++) {
            DemoStep step = steps.get(i);
            StationPos pos = i < stepObservedPositions.size() ? stepObservedPositions.get(i) : null;
            if (pos != null && !step.stationKey().isEmpty()) {
                bindings.bind(step.stationName(), pos.dimension(), pos.x(), pos.y(), pos.z());
                bound++;
            }
        }
        return bound;
    }

    public synchronized DemoStep editStep(int oneBasedIndex,
                                          DemoStep.Action action,
                                          String itemId,
                                          Integer count,
                                          String stationName,
                                          boolean confirm) {
        int idx = validateStepIndex(oneBasedIndex);
        DemoStep updated = steps.get(idx).withEdit(action, itemId, count, stationName, confirm);
        steps.set(idx, updated);
        StationPos pos = stepObservedPositions.get(idx);
        if (pos != null && !updated.stationName().isEmpty()) {
            sessionStationNames.put(pos, updated.stationName());
        }
        return updated;
    }

    public synchronized DemoStep renameStation(int oneBasedIndex, String newStationName) {
        int idx = validateStepIndex(oneBasedIndex);
        DemoStep updated = steps.get(idx).withStation(newStationName);
        steps.set(idx, updated);
        StationPos pos = stepObservedPositions.get(idx);
        if (pos != null) {
            sessionStationNames.put(pos, updated.stationName());
        }
        return updated;
    }

    public synchronized DemoStep removeStep(int oneBasedIndex) {
        int idx = validateStepIndex(oneBasedIndex);
        stepObservedPositions.remove(idx);
        return steps.remove(idx);
    }

    private int validateStepIndex(int oneBasedIndex) {
        int idx = oneBasedIndex - 1;
        if (idx < 0 || idx >= steps.size()) {
            throw new IllegalArgumentException("Step #" + oneBasedIndex + " does not exist (valid: 1.." + steps.size() + ")");
        }
        return idx;
    }

    private Optional<DemoStep> finalizeOpenVisit() {
        StationPos pos = this.openPos;
        String blockType = this.openBlockType;
        ContainerSlotSnapshot start = this.baseline;
        ContainerSlotSnapshot end = this.lastSnapshot;
        clearOpenVisitState();

        if (start == null || end == null) {
            return Optional.empty();
        }

        Map<String, Integer> startContainer = containerCounts(start);
        Map<String, Integer> endContainer = containerCounts(end);
        Map<String, Integer> startPlayer = playerCounts(start);
        Map<String, Integer> endPlayer = playerCounts(end);

        Set<String> allItems = new LinkedHashSet<>();
        allItems.addAll(startContainer.keySet());
        allItems.addAll(endContainer.keySet());
        allItems.addAll(startPlayer.keySet());
        allItems.addAll(endPlayer.keySet());

        Map<String, Integer> containerDeltas = new LinkedHashMap<>();
        Map<String, Integer> playerDeltas = new LinkedHashMap<>();
        for (String item : allItems) {
            int dc = endContainer.getOrDefault(item, 0) - startContainer.getOrDefault(item, 0);
            int dp = endPlayer.getOrDefault(item, 0) - startPlayer.getOrDefault(item, 0);
            if (dc != 0 || dp != 0) {
                containerDeltas.put(item, dc);
                playerDeltas.put(item, dp);
            }
        }

        // Case 1: Unsettled cursor when container closed -> always ambiguous.
        if (!end.cursorEmpty()) {
            String station = resolveOrAssignStationName(pos, blockType);
            String reason = "Container closed while holding " + end.cursorCount() + "x " + end.cursorItemId()
                    + " on cursor; confirm intended transfer";
            DemoStep step = DemoStep.unresolved(station, blockType, reason);
            appendStep(step, pos);
            return Optional.of(step);
        }

        // Case 2: Pure inspection / internal slot shuffling (zero net transfer) -> ignore cleanly.
        if (containerDeltas.isEmpty()) {
            return Optional.empty();
        }

        String station = resolveOrAssignStationName(pos, blockType);

        // Case 3: Exactly one item moved cleanly with exact container <-> player conservation.
        if (containerDeltas.size() == 1) {
            String item = containerDeltas.keySet().iterator().next();
            int dc = containerDeltas.get(item);
            int dp = playerDeltas.get(item);
            if (dc != 0 && dc == -dp) {
                DemoStep.Action action = dc < 0 ? DemoStep.Action.WITHDRAW : DemoStep.Action.DEPOSIT;
                int qty = Math.abs(dc);
                if (pos == null) {
                    DemoStep step = DemoStep.unresolvedCandidate(action, item, qty, station, blockType,
                            "Interacted container block position could not be identified; bind/confirm station");
                    appendStep(step, null);
                    return Optional.of(step);
                }
                DemoStep step = DemoStep.resolved(action, item, qty, station, blockType);
                appendStep(step, pos);
                return Optional.of(step);
            }
            // Conservation mismatch (e.g., item dropped on ground or external hopper/player interference).
            String reason = "Conservation mismatch for " + item + ": container delta " + signed(dc)
                    + " did not match inventory delta " + signed(dp);
            DemoStep step = DemoStep.unresolved(station, blockType, reason);
            appendStep(step, pos);
            return Optional.of(step);
        }

        // Case 4: Multiple items transferred in one container visit -> explicitly UNRESOLVED.
        StringBuilder details = new StringBuilder("Multiple items changed in one container visit: ");
        boolean first = true;
        for (String item : containerDeltas.keySet()) {
            if (!first) details.append("; ");
            first = false;
            int dc = containerDeltas.get(item);
            int dp = playerDeltas.get(item);
            if (dc != 0 && dc == -dp) {
                details.append(dc < 0 ? "withdraw " : "deposit ").append(Math.abs(dc)).append("x ").append(item);
            } else {
                details.append(item).append(" (container ").append(signed(dc))
                        .append(", inventory ").append(signed(dp)).append(")");
            }
        }
        DemoStep step = DemoStep.unresolved(station, blockType, details.toString());
        appendStep(step, pos);
        return Optional.of(step);
    }

    private void appendStep(DemoStep step, StationPos pos) {
        steps.add(step);
        stepObservedPositions.add(pos);
    }

    private String resolveOrAssignStationName(StationPos pos, String blockType) {
        if (pos != null) {
            // 1. Same or adjacent double-chest half already visited in this recording session.
            String existing = sessionStationNames.get(pos);
            if (existing != null) {
                return existing;
            }
            for (Map.Entry<StationPos, String> entry : sessionStationNames.entrySet()) {
                if (entry.getKey().isSameOrAdjacentHorizontal(pos)) {
                    return entry.getValue();
                }
            }
            // 2. Existing station binding registered at this position (e.g. player bound "pantry" / "serving barrel").
            if (referenceBindings != null) {
                Optional<DemoStationBindings.Binding> bound = referenceBindings.findByPos(pos);
                if (bound.isPresent()) {
                    sessionStationNames.put(pos, bound.get().displayName());
                    return bound.get().displayName();
                }
            }
        }
        // 3. Explicit hint passed to @teach start <source> <dest>.
        if (nextHintIndex < stationHints.size()) {
            String hinted = stationHints.get(nextHintIndex++);
            if (pos != null) {
                sessionStationNames.put(pos, hinted);
            }
            return hinted;
        }
        // 4. Clean editable default derived from container block type.
        autoStationCounter++;
        String base = (blockType == null || blockType.isBlank()) ? "station" : blockType;
        String fallback = base + "_" + autoStationCounter;
        if (pos != null) {
            sessionStationNames.put(pos, fallback);
        }
        return fallback;
    }

    private void clearInteractionState() {
        lastInteractPos = null;
        lastInteractBlockType = "";
        lastInteractMs = 0L;
    }

    private void clearOpenVisitState() {
        screenOpen = false;
        openSyncId = -1;
        openPos = null;
        openBlockType = "";
        openTicks = 0;
        baselineLocked = false;
        baseline = null;
        lastSnapshot = null;
    }

    private static Map<String, Integer> containerCounts(ContainerSlotSnapshot snap) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ContainerSlotSnapshot.SlotEntry s : snap.slots()) {
            if (s.container() && !s.isEmpty()) {
                map.merge(s.itemId(), s.count(), Integer::sum);
            }
        }
        return map;
    }

    private static Map<String, Integer> playerCounts(ContainerSlotSnapshot snap) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ContainerSlotSnapshot.SlotEntry s : snap.slots()) {
            if (!s.container() && !s.isEmpty()) {
                map.merge(s.itemId(), s.count(), Integer::sum);
            }
        }
        return map;
    }

    private static String signed(int n) {
        return n > 0 ? "+" + n : Integer.toString(n);
    }
}
