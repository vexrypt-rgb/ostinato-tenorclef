package adris.altoclef.tasks.brewing;

import adris.altoclef.tasksystem.FailureReason;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent pure-Java session ledger for a brewing job.
 * <p>
 * Survives {@code SingleTaskChain} {@code interrupt -> reset -> resume} cycles so metrics,
 * retry bounds, and terminal outcomes stay coherent, while stage progression is ALWAYS derived
 * from actual live observed stand/inventory contents.
 */
public final class BrewingSession {

    public static final int MAX_OPEN_RETRIES = 6;
    public static final int MAX_STALLED_CLICKS = 8;
    public static final int MAX_SUPPLY_CYCLES = 6;
    public static final int MAX_BREW_START_WAIT_TICKS = 100;  // 5s for brewing to begin once ingredient+fuel present
    public static final int MAX_BREW_STALL_TICKS = 600;       // 30s without brewTicks changing (vanilla brew is 20s)

    private final BrewRequest request;
    private BrewingOutcome outcome;

    private int stage1Loads = 0;
    private int stage2Loads = 0;
    private int fuelLoads = 0;
    private int interruptCount = 0;

    private int openRetries = 0;
    private int supplyCycles = 0;

    /** Known supply counts observed in nearby supply containers keyed by canonical block key. */
    private final Map<String, SupplyContainerSnapshot.SupplyCounts> inspectedContainers = new LinkedHashMap<>();

    public BrewingSession(BrewRequest request) {
        this.request = Objects.requireNonNull(request, "request");
        this.outcome = BrewingOutcome.running(request.targetCount());
    }

    public BrewRequest request() {
        return request;
    }

    public BrewingOutcome outcome() {
        return outcome;
    }

    public boolean isTerminal() {
        return outcome.isTerminal();
    }

    public int stage1Loads() {
        return stage1Loads;
    }

    public int stage2Loads() {
        return stage2Loads;
    }

    public int fuelLoads() {
        return fuelLoads;
    }

    public int interruptCount() {
        return interruptCount;
    }

    public int openRetries() {
        return openRetries;
    }

    public int supplyCycles() {
        return supplyCycles;
    }

    public void recordInterrupt() {
        interruptCount++;
    }

    // —— Order baseline: "make N" counts potions produced beyond what the player already held at start. ——

    private int baselineFinalInInventory = -1;
    private boolean standClaimed = false;

    /** Records how many exact final potions the player held when the job first started (once per session). */
    public void initBaselineIfNeeded(int finalPotionsInInventoryNow) {
        if (baselineFinalInInventory < 0) {
            baselineFinalInInventory = Math.max(0, finalPotionsInInventoryNow);
        }
    }

    public boolean hasBaseline() {
        return baselineFinalInInventory >= 0;
    }

    public int baselineFinalInInventory() {
        return Math.max(0, baselineFinalInInventory);
    }

    /** Exact final-potion count the player's inventory must reach for the job to be complete. */
    public int targetInventoryCount() {
        return baselineFinalInInventory() + request.targetCount();
    }

    public int producedSoFar(int finalPotionsInInventoryNow) {
        return Math.max(0, finalPotionsInInventoryNow - baselineFinalInInventory());
    }

    /**
     * The stand is "claimed" once this session has inspected it idle and found only a consistent state of the
     * requested recipe chain. Survives interrupt/reset so an in-progress brew we started is never mistaken for
     * a foreign batch, while a fresh session refuses to touch a stand that is already brewing.
     */
    public boolean isStandClaimed() {
        return standClaimed;
    }

    public void claimStand() {
        standClaimed = true;
    }

    public void recordStage1Load() {
        stage1Loads++;
    }

    public void recordStage2Load() {
        stage2Loads++;
    }

    public void recordFuelLoad() {
        fuelLoads++;
    }

    public boolean incrementOpenRetriesExceeded() {
        openRetries++;
        return openRetries > MAX_OPEN_RETRIES;
    }

    public void resetOpenRetries() {
        openRetries = 0;
    }

    public boolean incrementSupplyCyclesExceeded() {
        supplyCycles++;
        return supplyCycles > MAX_SUPPLY_CYCLES;
    }

    public void recordInspectedContainer(String containerKey, SupplyContainerSnapshot.SupplyCounts counts) {
        inspectedContainers.put(containerKey, counts == null ? SupplyContainerSnapshot.SupplyCounts.ZERO : counts);
    }

    public boolean hasInspectedContainer(String containerKey) {
        return inspectedContainers.containsKey(containerKey);
    }

    public void clearInspectedContainers() {
        inspectedContainers.clear();
    }

    public Map<String, SupplyContainerSnapshot.SupplyCounts> inspectedContainers() {
        return Collections.unmodifiableMap(inspectedContainers);
    }

    public SupplyContainerSnapshot.SupplyCounts totalInspectedContainerSupplies() {
        SupplyContainerSnapshot.SupplyCounts total = SupplyContainerSnapshot.SupplyCounts.ZERO;
        for (SupplyContainerSnapshot.SupplyCounts c : inspectedContainers.values()) {
            total = total.plus(c);
        }
        return total;
    }

    public void markSuccess(int producedCount, String detail) {
        if (isTerminal()) return;
        this.outcome = BrewingOutcome.success(
                producedCount, request.targetCount(), stage1Loads, stage2Loads, fuelLoads, detail);
    }

    public void markConflict(int producedCount, String detail) {
        if (isTerminal()) return;
        this.outcome = BrewingOutcome.failure(
                BrewingOutcome.Status.STAND_OCCUPIED, FailureReason.PRECONDITION_FAILED,
                producedCount, request.targetCount(), stage1Loads, stage2Loads, fuelLoads, detail);
    }

    public void markMissingFuel(int producedCount, String detail) {
        if (isTerminal()) return;
        this.outcome = BrewingOutcome.failure(
                BrewingOutcome.Status.MISSING_FUEL, FailureReason.RESOURCE_MISSING,
                producedCount, request.targetCount(), stage1Loads, stage2Loads, fuelLoads, detail);
    }

    public void markMissingSupplies(int producedCount, String detail) {
        if (isTerminal()) return;
        this.outcome = BrewingOutcome.failure(
                BrewingOutcome.Status.MISSING_SUPPLIES, FailureReason.RESOURCE_MISSING,
                producedCount, request.targetCount(), stage1Loads, stage2Loads, fuelLoads, detail);
    }

    public void markStandUnavailable(int producedCount, String detail) {
        if (isTerminal()) return;
        this.outcome = BrewingOutcome.failure(
                BrewingOutcome.Status.STAND_UNAVAILABLE, FailureReason.TARGET_UNAVAILABLE,
                producedCount, request.targetCount(), stage1Loads, stage2Loads, fuelLoads, detail);
    }

    public void markInventoryFull(int producedCount, String detail) {
        if (isTerminal()) return;
        this.outcome = BrewingOutcome.failure(
                BrewingOutcome.Status.INVENTORY_FULL, FailureReason.INVENTORY_FULL,
                producedCount, request.targetCount(), stage1Loads, stage2Loads, fuelLoads, detail);
    }

    public void markInteractionFailed(int producedCount, FailureReason reason, String detail) {
        if (isTerminal()) return;
        this.outcome = BrewingOutcome.failure(
                BrewingOutcome.Status.INTERACTION_FAILED,
                reason == null ? FailureReason.BACKEND_FAILURE : reason,
                producedCount, request.targetCount(), stage1Loads, stage2Loads, fuelLoads, detail);
    }

    public void markCancelled(int producedCount, String detail) {
        if (isTerminal()) return;
        this.outcome = BrewingOutcome.cancelled(
                producedCount, request.targetCount(), stage1Loads, stage2Loads, fuelLoads, detail);
    }
}
