package baritone.swarm.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Ostinato: Pure JDK live blueprint comparison and scoped repair state machine.
 * <ul>
 *   <li>Compares only active blueprint cells against client-visible observations.</li>
 *   <li>Preserves legacy name-only matching when a cell has no explicit properties.</li>
 *   <li>Surfaces unsupported explicit state requirements as {@link Blueprint.DiscrepancyKind#UNSUPPORTED}
 *       so neither the bot nor editor silently marks them complete.</li>
 *   <li>Waits {@link #DEFAULT_SETTLE_TICKS} after a repaired cell matches before rereading and confirming,
 *       never treating placement clicks or builder exit alone as success.</li>
 *   <li>Bounds retries per cell ({@link #DEFAULT_MAX_RETRIES}) and honors cancellation.</li>
 *   <li>Immediately invalidates stale pending targets when a live edit removes or changes a cell.</li>
 * </ul>
 */
public final class BlueprintRepairSession {

    public static final int DEFAULT_SETTLE_TICKS = 5;
    public static final int DEFAULT_MAX_RETRIES = 3;

    public record CellObservation(boolean loaded, String block, Map<String, String> properties) {
        public CellObservation {
            block = block == null ? "air" : BlockStateSpec.baseBlockId(block);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        public static CellObservation unloaded() {
            return new CellObservation(false, "air", Map.of());
        }

        public static CellObservation air() {
            return new CellObservation(true, "air", Map.of());
        }

        public static CellObservation of(String block, Map<String, String> properties) {
            return new CellObservation(true, block, properties);
        }

        public boolean isAir() {
            return block.isEmpty() || "air".equals(block) || "cave_air".equals(block) || "void_air".equals(block);
        }
    }

    @FunctionalInterface
    public interface WorldReader {
        CellObservation read(int x, int y, int z);
    }

    public record Comparison(long version, int total, List<int[]> matched, List<Blueprint.Discrepancy> discrepancies) {
        public Comparison {
            matched = matched == null ? List.of() : List.copyOf(matched);
            discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
        }

        public List<Blueprint.Discrepancy> wrongState() {
            List<Blueprint.Discrepancy> out = new ArrayList<>();
            for (Blueprint.Discrepancy d : discrepancies) {
                if (d.kind() == Blueprint.DiscrepancyKind.WRONG_STATE) {
                    out.add(d);
                }
            }
            return List.copyOf(out);
        }

        public List<Blueprint.Discrepancy> unsupported() {
            List<Blueprint.Discrepancy> out = new ArrayList<>();
            for (Blueprint.Discrepancy d : discrepancies) {
                if (d.kind() == Blueprint.DiscrepancyKind.UNSUPPORTED) {
                    out.add(d);
                }
            }
            return List.copyOf(out);
        }

        public boolean allMatched() {
            return total > 0 && matched.size() == total && discrepancies.isEmpty();
        }
    }

    /** Static unsupported-property scan (works offline in the editor without a live world). */
    public static List<Blueprint.Discrepancy> unsupportedCells(Blueprint bp) {
        if (bp == null || bp.size() == 0) {
            return List.of();
        }
        List<Blueprint.Discrepancy> out = new ArrayList<>();
        for (Blueprint.Cell c : bp.cells()) {
            List<String> reasons = BlockStateSpec.unsupportedReasons(c.block(), c.properties());
            if (!reasons.isEmpty()) {
                out.add(new Blueprint.Discrepancy(c.x(), c.y(), c.z(), Blueprint.DiscrepancyKind.UNSUPPORTED,
                        c.spec(), "-", String.join("; ", reasons)));
            }
        }
        return List.copyOf(out);
    }

    public static Comparison compare(Blueprint bp, WorldReader world) {
        return compare(bp, world, null);
    }

    /**
     * Compares every cell of {@code bp} against {@code world}. {@code extraUnsupportedValidator}, when non-null,
     * may return an additional unsupported reason from Minecraft's live block-state registry.
     */
    public static Comparison compare(Blueprint bp, WorldReader world,
                                     Function<Blueprint.Cell, String> extraUnsupportedValidator) {
        if (bp == null || bp.size() == 0) {
            return new Comparison(bp == null ? 0 : bp.version(), 0, List.of(), List.of());
        }
        List<int[]> matched = new ArrayList<>();
        List<Blueprint.Discrepancy> discrepancies = new ArrayList<>();
        for (Blueprint.Cell c : bp.cells()) {
            List<String> reasons = BlockStateSpec.unsupportedReasons(c.block(), c.properties());
            String extraReason = extraUnsupportedValidator == null ? null : extraUnsupportedValidator.apply(c);
            if (!reasons.isEmpty() || (extraReason != null && !extraReason.isBlank())) {
                String combined = !reasons.isEmpty() ? String.join("; ", reasons) : extraReason;
                if (!reasons.isEmpty() && extraReason != null && !extraReason.isBlank()
                        && !combined.contains(extraReason)) {
                    combined = combined + "; " + extraReason;
                }
                discrepancies.add(new Blueprint.Discrepancy(c.x(), c.y(), c.z(),
                        Blueprint.DiscrepancyKind.UNSUPPORTED, c.spec(), "-", combined));
                continue;
            }
            CellObservation obs = world == null ? CellObservation.unloaded() : world.read(c.x(), c.y(), c.z());
            if (obs == null || !obs.loaded()) {
                discrepancies.add(new Blueprint.Discrepancy(c.x(), c.y(), c.z(),
                        Blueprint.DiscrepancyKind.UNLOADED, c.spec(), "-", "chunk not loaded"));
                continue;
            }
            if (BlockStateSpec.matches(c.block(), c.properties(), obs.block(), obs.properties())) {
                matched.add(new int[]{c.x(), c.y(), c.z()});
                continue;
            }
            if (obs.isAir()) {
                discrepancies.add(new Blueprint.Discrepancy(c.x(), c.y(), c.z(),
                        Blueprint.DiscrepancyKind.MISSING, c.spec(), "air", "missing block"));
                continue;
            }
            if (c.block().equals(obs.block())) {
                Map<String, String> rel = BlockStateSpec.relevantActualProperties(c.properties(), obs.properties());
                String actualSpec = BlockStateSpec.format(obs.block(), rel);
                String diff = BlockStateSpec.mismatchReason(c.properties(), obs.properties());
                discrepancies.add(new Blueprint.Discrepancy(c.x(), c.y(), c.z(),
                        Blueprint.DiscrepancyKind.WRONG_STATE, c.spec(), actualSpec, diff));
            } else {
                discrepancies.add(new Blueprint.Discrepancy(c.x(), c.y(), c.z(),
                        Blueprint.DiscrepancyKind.WRONG_BLOCK, c.spec(), obs.block(),
                        "found " + obs.block() + " (want " + c.block() + ")"));
            }
        }
        return new Comparison(bp.version(), bp.size(), matched, discrepancies);
    }

    public enum SessionState {
        ACTIVE,
        SUCCEEDED,
        PARTIAL,
        FAILED,
        CANCELLED
    }

    public enum CellPhase {
        PENDING,
        SETTLING,
        CONFIRMED,
        FAILED
    }

    public enum ActionKind {
        NONE,
        START_BUILDER,
        STOP_BUILDER
    }

    public record Action(ActionKind kind, Set<Long> targetCells) {
        public static final Action NONE = new Action(ActionKind.NONE, Set.of());
        public static final Action STOP_BUILDER = new Action(ActionKind.STOP_BUILDER, Set.of());

        public static Action startBuilder(Set<Long> cells) {
            return new Action(ActionKind.START_BUILDER, Collections.unmodifiableSet(new LinkedHashSet<>(cells)));
        }
    }

    private static final class Target {
        Blueprint.Cell cell;
        CellPhase phase = CellPhase.PENDING;
        int attempts;
        int settleRemaining;
        String lastReason = "";

        Target(Blueprint.Cell cell) {
            this.cell = cell;
        }
    }

    private final String blueprintName;
    private long blueprintVersion;
    private final int settleTicks;
    private final int maxRetries;
    private final Map<Long, Target> targets = new LinkedHashMap<>();
    private final List<Blueprint.Discrepancy> unsupportedRequested = new ArrayList<>();
    private SessionState state = SessionState.ACTIVE;
    private boolean needsBuilderRestart;
    private boolean builderStartedOnce;

    public BlueprintRepairSession(Blueprint bp, Set<Long> requestedKeys, boolean wrongStateOnly, WorldReader world) {
        this(bp, requestedKeys, wrongStateOnly, world, null, DEFAULT_SETTLE_TICKS, DEFAULT_MAX_RETRIES);
    }

    public BlueprintRepairSession(Blueprint bp, Set<Long> requestedKeys, boolean wrongStateOnly, WorldReader world,
                                  Function<Blueprint.Cell, String> extraUnsupportedValidator,
                                  int settleTicks, int maxRetries) {
        if (bp == null) {
            throw new IllegalArgumentException("blueprint is required");
        }
        this.blueprintName = bp.name();
        this.blueprintVersion = bp.version();
        this.settleTicks = Math.max(1, settleTicks);
        this.maxRetries = Math.max(1, maxRetries);

        Comparison initial = compare(bp, world, extraUnsupportedValidator);
        Map<Long, Blueprint.Discrepancy> byKey = new LinkedHashMap<>();
        for (Blueprint.Discrepancy d : initial.discrepancies()) {
            byKey.put(Blueprint.cellKey(d.x(), d.y(), d.z()), d);
        }

        if (requestedKeys != null && !requestedKeys.isEmpty()) {
            for (Long k : requestedKeys) {
                Blueprint.Cell cell = findCell(bp, k);
                if (cell == null) {
                    continue;
                }
                Blueprint.Discrepancy d = byKey.get(k);
                if (d != null && d.kind() == Blueprint.DiscrepancyKind.UNSUPPORTED) {
                    unsupportedRequested.add(d);
                    continue;
                }
                if (d == null) {
                    // Already matching right now; still verify via settle only if requested? No need to rebuild a matched cell.
                    continue;
                }
                if (wrongStateOnly && d.kind() != Blueprint.DiscrepancyKind.WRONG_STATE) {
                    continue;
                }
                targets.put(k, new Target(cell));
            }
        } else {
            for (Blueprint.Discrepancy d : initial.discrepancies()) {
                long k = Blueprint.cellKey(d.x(), d.y(), d.z());
                if (d.kind() == Blueprint.DiscrepancyKind.UNSUPPORTED) {
                    unsupportedRequested.add(d);
                    continue;
                }
                if (d.kind() == Blueprint.DiscrepancyKind.UNLOADED) {
                    continue;
                }
                if (wrongStateOnly && d.kind() != Blueprint.DiscrepancyKind.WRONG_STATE) {
                    continue;
                }
                Blueprint.Cell cell = findCell(bp, k);
                if (cell != null) {
                    targets.put(k, new Target(cell));
                }
            }
        }

        if (targets.isEmpty()) {
            state = unsupportedRequested.isEmpty() ? SessionState.SUCCEEDED : SessionState.PARTIAL;
        } else {
            needsBuilderRestart = true;
        }
    }

    private static Blueprint.Cell findCell(Blueprint bp, long key) {
        for (Blueprint.Cell c : bp.cells()) {
            if (Blueprint.cellKey(c.x(), c.y(), c.z()) == key) {
                return c;
            }
        }
        return null;
    }

    public String blueprintName() {
        return blueprintName;
    }

    public long blueprintVersion() {
        return blueprintVersion;
    }

    public SessionState state() {
        return state;
    }

    public boolean isActive() {
        return state == SessionState.ACTIVE;
    }

    /** Returns the keys of targets currently pending or settling. */
    public Set<Long> pendingKeys() {
        Set<Long> out = new LinkedHashSet<>();
        for (Map.Entry<Long, Target> e : targets.entrySet()) {
            if (e.getValue().phase == CellPhase.PENDING || e.getValue().phase == CellPhase.SETTLING) {
                out.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** Returns the keys of targets confirmed after neighbor-update settling and reread. */
    public Set<Long> confirmedKeys() {
        Set<Long> out = new LinkedHashSet<>();
        for (Map.Entry<Long, Target> e : targets.entrySet()) {
            if (e.getValue().phase == CellPhase.CONFIRMED) {
                out.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public Set<Long> failedKeys() {
        Set<Long> out = new LinkedHashSet<>();
        for (Map.Entry<Long, Target> e : targets.entrySet()) {
            if (e.getValue().phase == CellPhase.FAILED) {
                out.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public List<Blueprint.Discrepancy> failedDiscrepancies() {
        List<Blueprint.Discrepancy> out = new ArrayList<>();
        for (Target t : targets.values()) {
            if (t.phase == CellPhase.FAILED) {
                out.add(new Blueprint.Discrepancy(t.cell.x(), t.cell.y(), t.cell.z(),
                        Blueprint.DiscrepancyKind.REPAIR_FAILED, t.cell.spec(), "-",
                        t.lastReason.isEmpty() ? "exceeded " + maxRetries + " repair attempts" : t.lastReason));
            }
        }
        return List.copyOf(out);
    }

    public void cancel() {
        if (state == SessionState.ACTIVE) {
            state = SessionState.CANCELLED;
            targets.clear();
            needsBuilderRestart = false;
        }
    }

    /**
     * Immediately reconciles pending repair targets against an updated live blueprint.
     * Any removed cell is dropped immediately; any changed cell is invalidated (removed from stale work and updated
     * or dropped if now unsupported) so the bot never restores a removed cell or places an outdated state.
     *
     * @return true if the active repair target set changed and the builder must be restarted or stopped immediately.
     */
    public boolean reconcile(Blueprint updated) {
        return reconcile(updated, null);
    }

    public boolean reconcile(Blueprint updated, Function<Blueprint.Cell, String> extraUnsupportedValidator) {
        if (state != SessionState.ACTIVE) {
            return false;
        }
        if (updated == null) {
            cancel();
            return true;
        }
        blueprintVersion = updated.version();
        boolean changed = false;
        List<Long> toRemove = new ArrayList<>();
        for (Map.Entry<Long, Target> e : targets.entrySet()) {
            long k = e.getKey();
            Target t = e.getValue();
            if (t.phase == CellPhase.CONFIRMED || t.phase == CellPhase.FAILED) {
                Blueprint.Cell now = updated.at(t.cell.x(), t.cell.y(), t.cell.z());
                if (now == null || !now.equals(t.cell)) {
                    toRemove.add(k);
                }
                continue;
            }
            Blueprint.Cell now = updated.at(t.cell.x(), t.cell.y(), t.cell.z());
            if (now == null) {
                // Live edit removed this target cell: immediately drop so bot never restores it.
                toRemove.add(k);
                changed = true;
                continue;
            }
            if (!now.equals(t.cell)) {
                // Live edit modified this target cell: invalidate stale attempt immediately.
                List<String> unsup = BlockStateSpec.unsupportedReasons(now.block(), now.properties());
                String extra = extraUnsupportedValidator == null ? null : extraUnsupportedValidator.apply(now);
                if (!unsup.isEmpty() || (extra != null && !extra.isBlank())) {
                    toRemove.add(k);
                    unsupportedRequested.add(new Blueprint.Discrepancy(now.x(), now.y(), now.z(),
                            Blueprint.DiscrepancyKind.UNSUPPORTED, now.spec(), "-",
                            !unsup.isEmpty() ? String.join("; ", unsup) : extra));
                } else {
                    t.cell = now;
                    t.phase = CellPhase.PENDING;
                    t.settleRemaining = 0;
                    t.attempts = 0;
                    t.lastReason = "updated by live edit";
                }
                changed = true;
            }
        }
        for (Long k : toRemove) {
            targets.remove(k);
        }
        if (changed) {
            if (activePendingOrSettling().isEmpty()) {
                finishState();
                needsBuilderRestart = false;
            } else {
                needsBuilderRestart = true;
            }
        }
        return changed;
    }

    /**
     * Advances the repair state machine by one tick using client-visible world observations.
     */
    public Action tick(WorldReader world, boolean builderActive, boolean builderPaused) {
        if (state == SessionState.CANCELLED) {
            return builderActive ? Action.STOP_BUILDER : Action.NONE;
        }
        if (state != SessionState.ACTIVE) {
            return Action.NONE;
        }

        // First, inspect every active target cell against client-visible world state.
        Set<Long> stillPending = new LinkedHashSet<>();
        int settlingCount = 0;
        for (Map.Entry<Long, Target> e : targets.entrySet()) {
            long key = e.getKey();
            Target t = e.getValue();
            if (t.phase == CellPhase.CONFIRMED || t.phase == CellPhase.FAILED) {
                continue;
            }
            CellObservation obs = world == null ? CellObservation.unloaded() : world.read(t.cell.x(), t.cell.y(), t.cell.z());
            boolean matched = obs != null && obs.loaded()
                    && BlockStateSpec.matches(t.cell.block(), t.cell.properties(), obs.block(), obs.properties());
            if (matched) {
                if (t.phase == CellPhase.PENDING) {
                    t.phase = CellPhase.SETTLING;
                    t.settleRemaining = settleTicks;
                    settlingCount++;
                } else if (t.phase == CellPhase.SETTLING) {
                    t.settleRemaining--;
                    if (t.settleRemaining <= 0) {
                        // Neighbor updates settled and reread still matches: confirmed!
                        t.phase = CellPhase.CONFIRMED;
                    } else {
                        settlingCount++;
                    }
                }
            } else {
                if (t.phase == CellPhase.SETTLING) {
                    // Neighbor update or server correction flipped state back after placement: retry bounded!
                    t.phase = CellPhase.PENDING;
                    t.settleRemaining = 0;
                    t.attempts++;
                    t.lastReason = describeObservation(t.cell, obs);
                    if (t.attempts >= maxRetries) {
                        t.phase = CellPhase.FAILED;
                        continue;
                    }
                    needsBuilderRestart = true;
                } else {
                    t.lastReason = describeObservation(t.cell, obs);
                }
                stillPending.add(key);
            }
        }

        if (stillPending.isEmpty() && settlingCount == 0) {
            finishState();
            return builderActive ? Action.STOP_BUILDER : Action.NONE;
        }

        if (stillPending.isEmpty()) {
            // Every remaining target is in SETTLING: wait for settle window to finish before completing.
            return builderActive ? Action.STOP_BUILDER : Action.NONE;
        }

        // If a live edit invalidated targets, restart builder immediately on the narrowed target set.
        if (needsBuilderRestart) {
            needsBuilderRestart = false;
            builderStartedOnce = true;
            return Action.startBuilder(stillPending);
        }

        // Builder finished or paused while pending cells still mismatch -> bounded retry!
        if (!builderActive || builderPaused) {
            Set<Long> retryable = new LinkedHashSet<>();
            for (Long key : stillPending) {
                Target t = targets.get(key);
                if (builderStartedOnce) {
                    t.attempts++;
                }
                if (t.attempts >= maxRetries) {
                    t.phase = CellPhase.FAILED;
                    if (t.lastReason.isEmpty()) {
                        t.lastReason = "failed after " + maxRetries + " attempts";
                    }
                } else {
                    retryable.add(key);
                }
            }
            if (retryable.isEmpty()) {
                if (settlingCount == 0) {
                    finishState();
                }
                return builderActive ? Action.STOP_BUILDER : Action.NONE;
            }
            builderStartedOnce = true;
            return Action.startBuilder(retryable);
        }

        return Action.NONE;
    }

    private Set<Long> activePendingOrSettling() {
        Set<Long> out = new LinkedHashSet<>();
        for (Map.Entry<Long, Target> e : targets.entrySet()) {
            if (e.getValue().phase == CellPhase.PENDING || e.getValue().phase == CellPhase.SETTLING) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private void finishState() {
        boolean anyFailed = !failedKeys().isEmpty();
        boolean anyConfirmed = !confirmedKeys().isEmpty();
        if (anyFailed && !anyConfirmed) {
            state = SessionState.FAILED;
        } else if (anyFailed || !unsupportedRequested.isEmpty()) {
            state = SessionState.PARTIAL;
        } else {
            state = SessionState.SUCCEEDED;
        }
    }

    private static String describeObservation(Blueprint.Cell expected, CellObservation obs) {
        if (obs == null || !obs.loaded()) {
            return "chunk not loaded";
        }
        if (obs.isAir()) {
            return "cell is air";
        }
        if (expected.block().equals(obs.block())) {
            String diff = BlockStateSpec.mismatchReason(expected.properties(), obs.properties());
            return diff.isEmpty() ? "state mismatch" : diff;
        }
        return "found " + obs.block() + " (want " + expected.block() + ")";
    }

    public String statusNote() {
        int total = targets.size();
        int conf = confirmedKeys().size();
        int fail = failedKeys().size();
        int unsup = unsupportedRequested.size();
        return switch (state) {
            case ACTIVE -> "repairing " + conf + "/" + total
                    + (unsup > 0 ? " (" + unsup + " unsupported)" : "");
            case SUCCEEDED -> "repaired " + conf + "/" + total;
            case PARTIAL -> "repaired " + conf + "/" + total
                    + (fail > 0 ? ", " + fail + " failed" : "")
                    + (unsup > 0 ? ", " + unsup + " unsupported" : "");
            case FAILED -> "repair failed (" + fail + "/" + total + ")";
            case CANCELLED -> "repair cancelled";
        };
    }

    /** Formats a summary note for general blueprint status that never falsely claims "done". */
    public static String summaryNote(Comparison cmp, boolean building, BlueprintRepairSession repair) {
        if (repair != null) {
            if (repair.isActive()) {
                return repair.statusNote();
            }
            if (repair.state() == SessionState.CANCELLED) {
                return "repair cancelled";
            }
            if (repair.state() == SessionState.FAILED || repair.state() == SessionState.PARTIAL) {
                return repair.statusNote();
            }
        }
        if (cmp == null || cmp.total() == 0) {
            return "empty";
        }
        if (cmp.allMatched()) {
            return "done";
        }
        int unsup = 0, wrongState = 0, wrongBlock = 0, missing = 0, unloaded = 0;
        for (Blueprint.Discrepancy d : cmp.discrepancies()) {
            switch (d.kind()) {
                case UNSUPPORTED -> unsup++;
                case WRONG_STATE -> wrongState++;
                case WRONG_BLOCK -> wrongBlock++;
                case MISSING -> missing++;
                case UNLOADED -> unloaded++;
                case REPAIR_FAILED -> {}
            }
        }
        List<String> parts = new ArrayList<>();
        if (building) {
            parts.add("building");
        }
        if (wrongState > 0) {
            parts.add(wrongState + " wrong-state");
        }
        if (wrongBlock > 0) {
            parts.add(wrongBlock + " wrong-block");
        }
        if (missing > 0) {
            parts.add(missing + " missing");
        }
        if (unsup > 0) {
            parts.add(unsup + " unsupported");
        }
        if (unloaded > 0) {
            parts.add(unloaded + " unloaded");
        }
        return parts.isEmpty() ? "idle" : String.join(", ", parts);
    }
}
