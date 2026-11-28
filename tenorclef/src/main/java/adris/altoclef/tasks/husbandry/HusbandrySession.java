package adris.altoclef.tasks.husbandry;

import adris.altoclef.tasksystem.FailureReason;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java persistent session and policy engine for cow pen husbandry.
 * <p>
 * Survives {@code SingleTaskChain} {@code interrupt -> reset -> resume} cycles so breeding cooldowns,
 * pending-birth reconciliation, retry bounds, and metrics remain coherent, while every decision is
 * anchored to actual client-visible pen observations.
 */
public final class HusbandrySession {

    /** Vanilla adult cow breeding cooldown after producing a calf (5 minutes = 6000 ticks). */
    public static final long BREEDING_COOLDOWN_TICKS = 6_000L;
    /** Vanilla love-mode duration after feeding wheat (30 seconds = 600 ticks). */
    public static final long IN_LOVE_WINDOW_TICKS = 600L;
    /** Time to wait after feeding the second cow for the calf to spawn before reconciling/retrying. */
    public static final long BIRTH_WAIT_TIMEOUT_TICKS = 320L;
    /** Ticks to wait for wheat count to drop after sending an interactEntity packet. */
    public static final long FEED_CONFIRM_WAIT_TICKS = 10L;
    /** Backoff applied to a cow that did not consume wheat (e.g. already on cooldown before task start). */
    public static final long PROBE_BACKOFF_TICKS = 200L;
    /** Grace window when a previously seen cow temporarily disappears from client entity tracking. */
    public static final long OBSERVATION_STABILITY_TICKS = 15L;
    /** Bounded ticks allowed while the pen remains unloaded before failing cleanly. */
    public static final long MAX_UNLOADED_TICKS = 400L;
    /** Maximum consecutive unconfirmed feed interactions before failing cleanly. */
    public static final int MAX_CONSECUTIVE_FAILED_FEEDS = 12;
    /** Maximum birth-wait timeouts without observed growth before failing cleanly. */
    public static final int MAX_BIRTH_WAIT_TIMEOUTS = 4;

    public enum Phase {
        RECOUNT_AND_DECIDE,
        FEED_FIRST,
        FEED_SECOND,
        WAIT_FOR_BIRTH,
        WAIT_COOLDOWN,
        FROZEN_UNCERTAIN
    }

    public enum ActionKind {
        COMPLETE,
        FAIL,
        APPROACH_PEN,
        FREEZE_WAIT,
        FEED_COW,
        WAIT_FEED_CONFIRM,
        WAIT_FOR_BIRTH,
        WAIT_COOLDOWN
    }

    public record Decision(ActionKind kind,
                           int targetCowId,
                           long waitRemainingTicks,
                           String detail) {
        public static Decision complete(String detail) {
            return new Decision(ActionKind.COMPLETE, -1, 0L, detail);
        }

        public static Decision fail(String detail) {
            return new Decision(ActionKind.FAIL, -1, 0L, detail);
        }

        public static Decision approachPen(String detail) {
            return new Decision(ActionKind.APPROACH_PEN, -1, 0L, detail);
        }

        public static Decision freezeWait(String detail) {
            return new Decision(ActionKind.FREEZE_WAIT, -1, 0L, detail);
        }

        public static Decision feedCow(int cowId, String detail) {
            return new Decision(ActionKind.FEED_COW, cowId, 0L, detail);
        }

        public static Decision waitFeedConfirm(int cowId, String detail) {
            return new Decision(ActionKind.WAIT_FEED_CONFIRM, cowId, 0L, detail);
        }

        public static Decision waitForBirth(long remaining, String detail) {
            return new Decision(ActionKind.WAIT_FOR_BIRTH, -1, Math.max(0L, remaining), detail);
        }

        public static Decision waitCooldown(long remaining, String detail) {
            return new Decision(ActionKind.WAIT_COOLDOWN, -1, Math.max(0L, remaining), detail);
        }
    }

    private final HusbandryRequest request;
    private HusbandryOutcome outcome;
    private Phase phase = Phase.RECOUNT_AND_DECIDE;

    // Metrics
    private int lastObservedCount = 0;
    private int birthsObserved = 0;
    private int wheatConsumed = 0;
    private int interruptCount = 0;

    // Per-adult cooldown and probe-backoff tracking (keyed by entityId)
    private final Map<Integer, Long> breedingCooldownUntil = new HashMap<>();
    private final Map<Integer, Long> probeBackoffUntil = new HashMap<>();

    // Active pair state
    private int firstCowId = -1;
    private int secondCowId = -1;
    private long firstFedTick = -1L;
    private long secondFedTick = -1L;
    private int countBeforePair = -1;
    private final Set<Integer> knownCowIdsBeforePair = new HashSet<>();

    // Pending feed confirmation state
    private int pendingFeedCowId = -1;
    private int pendingWheatBefore = -1;
    private long pendingFeedSentTick = -1L;

    // Observation stability & bounded retry state
    private int lastStableCount = 0;
    private long countDropStartedTick = -1L;
    private long unloadedTicks = 0L;
    private int consecutiveFailedFeeds = 0;
    private int birthWaitTimeouts = 0;

    public HusbandrySession(HusbandryRequest request) {
        this.request = Objects.requireNonNull(request, "request");
        this.outcome = HusbandryOutcome.running(0, request.targetCount(), 0, 0);
    }

    public HusbandryRequest request() {
        return request;
    }

    public HusbandryOutcome outcome() {
        return outcome;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isTerminal() {
        return outcome.isTerminal();
    }

    public int lastObservedCount() {
        return lastObservedCount;
    }

    public int birthsObserved() {
        return birthsObserved;
    }

    public int wheatConsumed() {
        return wheatConsumed;
    }

    public int interruptCount() {
        return interruptCount;
    }

    public void recordInterrupt() {
        interruptCount++;
        // Clear in-flight packet confirmation on interrupt so resume re-evaluates live inventory & herd state cleanly.
        clearPendingFeed();
    }

    public void cancel(String detail) {
        if (!isTerminal()) {
            outcome = HusbandryOutcome.cancelled(lastObservedCount, request.targetCount(),
                    birthsObserved, wheatConsumed, detail);
        }
    }

    /**
     * Records that the runtime task just sent a right-click wheat interaction on {@code cowId}.
     */
    public void recordFeedAttempt(int cowId, int wheatBefore, long gameTick) {
        this.pendingFeedCowId = cowId;
        this.pendingWheatBefore = wheatBefore;
        this.pendingFeedSentTick = gameTick;
    }

    public boolean hasPendingFeedConfirm() {
        return pendingFeedCowId >= 0;
    }

    /**
     * Evaluates one tick's client-visible pen snapshot and returns the next safe action.
     */
    public Decision evaluate(CowObservation.PenSnapshot snap) {
        Objects.requireNonNull(snap, "snap");
        if (isTerminal()) {
            return outcome.isSuccess()
                    ? Decision.complete(outcome.detail())
                    : Decision.fail(outcome.detail());
        }

        long tick = snap.gameTick();

        // 1. Freeze immediately if pen chunks are unloaded or player is too far to observe reliably.
        if (!snap.playerWithinObservationRange()) {
            phase = Phase.FROZEN_UNCERTAIN;
            clearPendingFeed();
            return Decision.approachPen("approaching pen to observe contained cows reliably");
        }
        if (!snap.allChunksLoaded()) {
            phase = Phase.FROZEN_UNCERTAIN;
            clearPendingFeed();
            unloadedTicks++;
            if (unloadedTicks > MAX_UNLOADED_TICKS) {
                return failSession(HusbandryOutcome.Status.PEN_UNAVAILABLE, FailureReason.TARGET_UNAVAILABLE,
                        "pen chunks remained unloaded for " + unloadedTicks + " ticks");
            }
            return Decision.freezeWait("pen chunks not fully loaded; freezing breeding decisions");
        }
        unloadedTicks = 0L;

        int totalNow = snap.totalCount();
        lastObservedCount = totalNow;

        // 2. Always reconcile any newly born calf FIRST (handles normal birth AND post-interrupt resume!).
        if (hasActivePairTracking()) {
            boolean newCalfSeen = hasNewCowSincePairStart(snap);
            boolean countIncreased = countBeforePair >= 0 && totalNow > countBeforePair;
            if (newCalfSeen || countIncreased) {
                int added = countBeforePair >= 0 ? Math.max(1, totalNow - countBeforePair) : 1;
                birthsObserved += added;
                // Record cooldown on whichever parent cows were fed.
                if (firstCowId >= 0) {
                    breedingCooldownUntil.put(firstCowId, tick + BREEDING_COOLDOWN_TICKS);
                }
                if (secondCowId >= 0) {
                    breedingCooldownUntil.put(secondCowId, tick + BREEDING_COOLDOWN_TICKS);
                }
                clearActivePair();
                clearPendingFeed();
                birthWaitTimeouts = 0;
                consecutiveFailedFeeds = 0;
                lastStableCount = totalNow;
                countDropStartedTick = -1L;
                phase = Phase.RECOUNT_AND_DECIDE;
            }
        }

        // 3. Observation stability check: if a previously seen cow temporarily flickers out of client tracking,
        //    freeze breeding decisions for OBSERVATION_STABILITY_TICKS rather than guessing or feeding blindly.
        if (totalNow >= lastStableCount) {
            lastStableCount = totalNow;
            countDropStartedTick = -1L;
        } else {
            if (countDropStartedTick < 0L) {
                countDropStartedTick = tick;
            }
            if ((tick - countDropStartedTick) < OBSERVATION_STABILITY_TICKS) {
                phase = Phase.FROZEN_UNCERTAIN;
                clearPendingFeed();
                return Decision.freezeWait("temporary lost cow observation (" + totalNow + " < " + lastStableCount + "); waiting for stable recount");
            }
            // Count stayed lower past stability window -> accept as authoritative recount.
            lastStableCount = totalNow;
            countDropStartedTick = -1L;
        }

        // 4. Target reached! Stop immediately without ever feeding another cow.
        if (totalNow >= request.targetCount()) {
            clearActivePair();
            clearPendingFeed();
            outcome = HusbandryOutcome.success(totalNow, request.targetCount(), birthsObserved, wheatConsumed,
                    "reached " + totalNow + "/" + request.targetCount() + " contained cows");
            return Decision.complete(outcome.detail());
        }

        // Update running outcome metrics.
        outcome = HusbandryOutcome.running(totalNow, request.targetCount(), birthsObserved, wheatConsumed);

        // 5. Confirm any in-flight feed interaction by observing actual wheat consumption.
        if (pendingFeedCowId >= 0) {
            int consumed = pendingWheatBefore - snap.playerWheatCount();
            if (consumed > 0) {
                wheatConsumed += consumed;
                consecutiveFailedFeeds = 0;
                int fedId = pendingFeedCowId;
                clearPendingFeed();
                if (phase == Phase.FEED_FIRST || firstCowId < 0 || fedId == firstCowId) {
                    firstCowId = fedId;
                    firstFedTick = tick;
                    snapshotPrePairHerdIfNeeded(snap);
                    phase = Phase.FEED_SECOND;
                } else {
                    secondCowId = fedId;
                    secondFedTick = tick;
                    phase = Phase.WAIT_FOR_BIRTH;
                    return Decision.waitForBirth(BIRTH_WAIT_TIMEOUT_TICKS,
                            "pair fed (" + firstCowId + "," + secondCowId + "); waiting for calf");
                }
            } else if ((tick - pendingFeedSentTick) < FEED_CONFIRM_WAIT_TICKS) {
                return Decision.waitFeedConfirm(pendingFeedCowId,
                        "confirming wheat consumption for cow #" + pendingFeedCowId);
            } else {
                // Wheat was NOT consumed -> cow likely on cooldown or click missed; apply backoff.
                int failedCowId = pendingFeedCowId;
                clearPendingFeed();
                probeBackoffUntil.put(failedCowId, tick + PROBE_BACKOFF_TICKS);
                consecutiveFailedFeeds++;
                if (consecutiveFailedFeeds >= MAX_CONSECUTIVE_FAILED_FEEDS) {
                    return failSession(HusbandryOutcome.Status.INTERACTION_FAILED, FailureReason.TIMEOUT,
                            "failed to confirm wheat consumption after " + consecutiveFailedFeeds + " consecutive attempts");
                }
                // Fall through to pick another eligible cow or wait out backoff.
                if (phase == Phase.FEED_FIRST) {
                    firstCowId = -1;
                    phase = Phase.RECOUNT_AND_DECIDE;
                } else if (phase == Phase.FEED_SECOND) {
                    secondCowId = -1;
                }
            }
        }

        // 6. If we are waiting for a calf from a fed pair, DO NOT feed any other cows!
        if (phase == Phase.WAIT_FOR_BIRTH) {
            long elapsed = tick - secondFedTick;
            if (elapsed < BIRTH_WAIT_TIMEOUT_TICKS) {
                return Decision.waitForBirth(BIRTH_WAIT_TIMEOUT_TICKS - elapsed,
                        "waiting for calf from pair (" + firstCowId + "," + secondCowId + ")");
            }
            // Timed out without observing birth -> reconcile and allow bounded retry.
            birthWaitTimeouts++;
            clearActivePair();
            phase = Phase.RECOUNT_AND_DECIDE;
            if (birthWaitTimeouts >= MAX_BIRTH_WAIT_TIMEOUTS) {
                return failSession(HusbandryOutcome.Status.INTERACTION_FAILED, FailureReason.TIMEOUT,
                        "no calf observed after " + birthWaitTimeouts + " breeding pair attempts");
            }
        }

        // 7. Expire stale half-fed pair if love mode elapsed (e.g., across a long interruption).
        if (phase == Phase.FEED_SECOND) {
            if (firstCowId < 0 || (tick - firstFedTick) > IN_LOVE_WINDOW_TICKS || snap.findById(firstCowId) == null) {
                clearActivePair();
                phase = Phase.RECOUNT_AND_DECIDE;
            }
        }

        // 8. Check breeding population viability.
        int adults = snap.adultCount();
        int calves = snap.calfCount();
        if (adults < 2) {
            if (calves == 0) {
                return failSession(HusbandryOutcome.Status.NO_BREEDING_PAIR, FailureReason.TARGET_UNAVAILABLE,
                        "need at least 2 adult cows in pen (found " + adults + " adult(s), 0 calves)");
            }
            phase = Phase.WAIT_COOLDOWN;
            return Decision.waitCooldown(BREEDING_COOLDOWN_TICKS,
                    "waiting for calf to mature (" + adults + " adult, " + calves + " calf/calves)");
        }

        // 9. Continue feeding second cow of an active pair.
        if (phase == Phase.FEED_SECOND) {
            if (snap.playerWheatCount() < 1) {
                return failSession(HusbandryOutcome.Status.FEED_SHORTAGE, FailureReason.RESOURCE_MISSING,
                        "out of wheat before feeding second cow (have 0 wheat, need 1)");
            }
            List<CowObservation> readySecond = eligibleAdults(snap, tick, firstCowId);
            if (readySecond.isEmpty()) {
                long remaining = nextReadyWaitTicks(snap, tick, firstCowId);
                phase = Phase.WAIT_COOLDOWN;
                return Decision.waitCooldown(remaining, "waiting for second eligible adult cow");
            }
            CowObservation target = readySecond.get(0);
            secondCowId = target.entityId();
            return Decision.feedCow(target.entityId(), "feeding second cow #" + target.entityId());
        }

        // 10. Start a new pair: check eligible adults first, then verify wheat >= 2 BEFORE feeding cow #1!
        List<CowObservation> readyAdults = eligibleAdults(snap, tick, -1);
        if (readyAdults.size() < 2) {
            long remaining = nextPairReadyWaitTicks(snap, tick);
            phase = Phase.WAIT_COOLDOWN;
            return Decision.waitCooldown(remaining,
                    "waiting for breeding cooldown (" + readyAdults.size() + "/2 eligible adults)");
        }

        if (snap.playerWheatCount() < 2) {
            return failSession(HusbandryOutcome.Status.FEED_SHORTAGE, FailureReason.RESOURCE_MISSING,
                    "feed shortage: need at least 2 wheat to breed a pair, have " + snap.playerWheatCount());
        }

        snapshotPrePairHerd(snap);
        CowObservation first = readyAdults.get(0);
        firstCowId = first.entityId();
        phase = Phase.FEED_FIRST;
        return Decision.feedCow(first.entityId(), "feeding first cow #" + first.entityId());
    }

    private List<CowObservation> eligibleAdults(CowObservation.PenSnapshot snap, long tick, int excludeCowId) {
        List<CowObservation> eligible = new ArrayList<>();
        for (CowObservation cow : snap.containedCows()) {
            if (!cow.isAdult()) continue;
            if (cow.entityId() == excludeCowId) continue;
            if (breedingCooldownUntil.getOrDefault(cow.entityId(), 0L) > tick) continue;
            if (probeBackoffUntil.getOrDefault(cow.entityId(), 0L) > tick) continue;
            eligible.add(cow);
        }
        eligible.sort(Comparator.comparingInt(CowObservation::entityId));
        return eligible;
    }

    private long nextReadyWaitTicks(CowObservation.PenSnapshot snap, long tick, int excludeCowId) {
        long minWait = Long.MAX_VALUE;
        for (CowObservation cow : snap.containedCows()) {
            if (!cow.isAdult() || cow.entityId() == excludeCowId) continue;
            long readyAt = Math.max(
                    breedingCooldownUntil.getOrDefault(cow.entityId(), 0L),
                    probeBackoffUntil.getOrDefault(cow.entityId(), 0L));
            long wait = Math.max(1L, readyAt - tick);
            if (wait < minWait) minWait = wait;
        }
        return minWait == Long.MAX_VALUE ? PROBE_BACKOFF_TICKS : minWait;
    }

    private long nextPairReadyWaitTicks(CowObservation.PenSnapshot snap, long tick) {
        List<Long> waits = new ArrayList<>();
        for (CowObservation cow : snap.containedCows()) {
            if (!cow.isAdult()) continue;
            long readyAt = Math.max(
                    breedingCooldownUntil.getOrDefault(cow.entityId(), 0L),
                    probeBackoffUntil.getOrDefault(cow.entityId(), 0L));
            waits.add(Math.max(0L, readyAt - tick));
        }
        waits.sort(Long::compareTo);
        if (waits.size() < 2) {
            return BREEDING_COOLDOWN_TICKS;
        }
        return Math.max(1L, waits.get(1));
    }

    private boolean hasActivePairTracking() {
        return phase == Phase.WAIT_FOR_BIRTH || phase == Phase.FEED_SECOND || countBeforePair >= 0;
    }

    private boolean hasNewCowSincePairStart(CowObservation.PenSnapshot snap) {
        if (knownCowIdsBeforePair.isEmpty()) {
            return false;
        }
        for (CowObservation cow : snap.containedCows()) {
            if (cow.alive() && !knownCowIdsBeforePair.contains(cow.entityId())) {
                return true;
            }
        }
        return false;
    }

    private void snapshotPrePairHerd(CowObservation.PenSnapshot snap) {
        knownCowIdsBeforePair.clear();
        for (CowObservation cow : snap.containedCows()) {
            if (cow.alive()) {
                knownCowIdsBeforePair.add(cow.entityId());
            }
        }
        countBeforePair = knownCowIdsBeforePair.size();
    }

    private void snapshotPrePairHerdIfNeeded(CowObservation.PenSnapshot snap) {
        if (countBeforePair < 0) {
            snapshotPrePairHerd(snap);
        }
    }

    private void clearActivePair() {
        firstCowId = -1;
        secondCowId = -1;
        firstFedTick = -1L;
        secondFedTick = -1L;
        countBeforePair = -1;
        knownCowIdsBeforePair.clear();
    }

    private void clearPendingFeed() {
        pendingFeedCowId = -1;
        pendingWheatBefore = -1;
        pendingFeedSentTick = -1L;
    }

    private Decision failSession(HusbandryOutcome.Status status, FailureReason reason, String detail) {
        clearActivePair();
        clearPendingFeed();
        outcome = HusbandryOutcome.failure(status, reason,
                lastObservedCount, request.targetCount(), birthsObserved, wheatConsumed, detail);
        return Decision.fail(detail);
    }
}
