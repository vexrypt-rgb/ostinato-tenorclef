package adris.altoclef.tasks.husbandry;

import adris.altoclef.tasksystem.FailureReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HusbandrySessionTest {

    private static CowObservation adult(int id) {
        return new CowObservation(id, new UUID(0L, id), 2.5, 64.0, 2.5, false, true);
    }

    private static CowObservation calf(int id) {
        return new CowObservation(id, new UUID(0L, id), 2.5, 64.0, 2.5, true, true);
    }

    private static CowObservation.PenSnapshot snap(long tick, int wheat, List<CowObservation> cows) {
        return new CowObservation.PenSnapshot(tick, true, true, wheat, cows);
    }

    @Test
    void passCaseGrowsTwoAdultsToSixAndNeverOvershootsThroughExtraCooldown() {
        HusbandrySession session = new HusbandrySession(HusbandryRequest.growCows(6));
        List<CowObservation> herd = new ArrayList<>(List.of(adult(1), adult(2)));
        int wheat = 20;
        long tick = 0L;
        int nextCowId = 3;

        // Simulate 4 breeding cycles (2 -> 3 -> 4 -> 5 -> 6) under vanilla 6000-tick cooldowns.
        for (int cycle = 0; cycle < 4; cycle++) {
            // 1. Feed first cow
            var d1 = session.evaluate(snap(tick, wheat, herd));
            assertEquals(HusbandrySession.ActionKind.FEED_COW, d1.kind(), "cycle " + cycle + " first feed");
            session.recordFeedAttempt(d1.targetCowId(), wheat, tick);
            wheat--;
            tick += 2;

            // 2. Confirm first feed -> immediately instructs feeding second cow
            var d2 = session.evaluate(snap(tick, wheat, herd));
            assertEquals(HusbandrySession.ActionKind.FEED_COW, d2.kind(), "cycle " + cycle + " second feed");
            assertNotEquals(d1.targetCowId(), d2.targetCowId(), "must feed two distinct adults");
            session.recordFeedAttempt(d2.targetCowId(), wheat, tick);
            wheat--;
            tick += 2;

            // 3. Confirm second feed -> WAIT_FOR_BIRTH (must NOT feed another cow!)
            var dWait = session.evaluate(snap(tick, wheat, herd));
            assertEquals(HusbandrySession.ActionKind.WAIT_FOR_BIRTH, dWait.kind());

            // Wait 40 ticks before calf spawns; verify zero feeding while waiting.
            tick += 40;
            assertEquals(HusbandrySession.ActionKind.WAIT_FOR_BIRTH, session.evaluate(snap(tick, wheat, herd)).kind());

            // 4. Calf spawns!
            herd.add(calf(nextCowId++));
            tick += 1;
            var dAfterBirth = session.evaluate(snap(tick, wheat, herd));

            if (herd.size() < 6) {
                // Adults are on breeding cooldown -> must wait, not feed!
                assertEquals(HusbandrySession.ActionKind.WAIT_COOLDOWN, dAfterBirth.kind());
                assertEquals(cycle + 1, session.birthsObserved());
                assertEquals((cycle + 1) * 2, session.wheatConsumed());

                // Mid-cooldown check: still waiting, zero feeds.
                tick += 3000;
                assertEquals(HusbandrySession.ActionKind.WAIT_COOLDOWN, session.evaluate(snap(tick, wheat, herd)).kind());

                // Cooldown elapses:
                tick += 3005;
            } else {
                // Reached 6 -> immediate COMPLETE!
                assertEquals(HusbandrySession.ActionKind.COMPLETE, dAfterBirth.kind());
            }
        }

        assertTrue(session.isTerminal());
        assertTrue(session.outcome().isSuccess());
        assertEquals(HusbandryOutcome.Status.SUCCESS, session.outcome().status());
        assertEquals(6, session.outcome().observedCount());
        assertEquals(4, session.birthsObserved());
        assertEquals(8, session.wheatConsumed());
        assertEquals(12, wheat);

        // Continue observing through one full additional breeding-cooldown opportunity (6500 ticks):
        // verify zero further feedings and zero overshoot.
        for (long dt = 0; dt <= 6500; dt += 500) {
            var post = session.evaluate(snap(tick + dt, wheat, herd));
            assertEquals(HusbandrySession.ActionKind.COMPLETE, post.kind());
        }
        assertEquals(12, wheat, "zero wheat consumed after reaching target");
        assertEquals(6, herd.size(), "zero overshoot");
    }

    @Test
    void interruptionBetweenPairFeedAndBirthReconcilesWithoutOvershoot() {
        HusbandrySession session = new HusbandrySession(HusbandryRequest.growCows(6));
        // Start with 2 adults + 3 calves = 5 cows (one birth away from target 6).
        List<CowObservation> herd = new ArrayList<>(List.of(adult(1), adult(2), calf(3), calf(4), calf(5)));
        int wheat = 10;
        long tick = 100L;

        // Feed cow 1
        var d1 = session.evaluate(snap(tick, wheat, herd));
        assertEquals(HusbandrySession.ActionKind.FEED_COW, d1.kind());
        session.recordFeedAttempt(d1.targetCowId(), wheat, tick);
        wheat--;
        tick += 2;

        // Feed cow 2
        var d2 = session.evaluate(snap(tick, wheat, herd));
        assertEquals(HusbandrySession.ActionKind.FEED_COW, d2.kind());
        session.recordFeedAttempt(d2.targetCowId(), wheat, tick);
        wheat--;
        tick += 2;

        // Now in WAIT_FOR_BIRTH
        assertEquals(HusbandrySession.ActionKind.WAIT_FOR_BIRTH, session.evaluate(snap(tick, wheat, herd)).kind());

        // INTERRUPT occurs before the calf is observed!
        session.recordInterrupt();
        assertEquals(1, session.interruptCount());

        // While interrupted, the calf is born (herd becomes 6).
        herd.add(calf(6));
        tick += 60;

        // Resume: session reconciles actual pen count, records the birth, and completes with zero overshoot!
        var resumed = session.evaluate(snap(tick, wheat, herd));
        assertEquals(HusbandrySession.ActionKind.COMPLETE, resumed.kind());
        assertTrue(session.outcome().isSuccess());
        assertEquals(6, session.outcome().observedCount());
        assertEquals(1, session.birthsObserved());
        assertEquals(2, session.wheatConsumed());
        assertEquals(8, wheat, "no extra wheat consumed on resume");
    }

    @Test
    void interruptionDuringHalfFedPairExpiresStaleLoveModeCleanly() {
        HusbandrySession session = new HusbandrySession(HusbandryRequest.growCows(6));
        List<CowObservation> herd = new ArrayList<>(List.of(adult(1), adult(2)));
        int wheat = 10;
        long tick = 0L;

        // Feed first cow
        var d1 = session.evaluate(snap(tick, wheat, herd));
        session.recordFeedAttempt(d1.targetCowId(), wheat, tick);
        wheat--;
        tick += 2;
        assertEquals(HusbandrySession.ActionKind.FEED_COW, session.evaluate(snap(tick, wheat, herd)).kind());

        // Interrupt for longer than IN_LOVE_WINDOW_TICKS (600 ticks) with no calf born
        session.recordInterrupt();
        tick += HusbandrySession.IN_LOVE_WINDOW_TICKS + 50;

        // Resume: does NOT assume a birth happened; cleanly starts a fresh pair!
        var resumed = session.evaluate(snap(tick, wheat, herd));
        assertEquals(HusbandrySession.ActionKind.FEED_COW, resumed.kind());
        assertEquals(0, session.birthsObserved());
        assertFalse(session.isTerminal());
    }

    @Test
    void emptyFeedAndInsufficientPairFeedReportFeedShortageWithoutFalseCompletion() {
        // Case A: 0 wheat
        HusbandrySession sZero = new HusbandrySession(HusbandryRequest.growCows(6));
        List<CowObservation> herd = List.of(adult(1), adult(2));
        var dZero = sZero.evaluate(snap(0L, 0, herd));
        assertEquals(HusbandrySession.ActionKind.FAIL, dZero.kind());
        assertTrue(sZero.isTerminal());
        assertFalse(sZero.outcome().isSuccess());
        assertEquals(HusbandryOutcome.Status.FEED_SHORTAGE, sZero.outcome().status());
        assertEquals(FailureReason.RESOURCE_MISSING, sZero.outcome().failureReason());

        // Case B: only 1 wheat (not enough for a pair -> fails cleanly without wasting the single wheat)
        HusbandrySession sOne = new HusbandrySession(HusbandryRequest.growCows(6));
        var dOne = sOne.evaluate(snap(0L, 1, herd));
        assertEquals(HusbandrySession.ActionKind.FAIL, dOne.kind());
        assertEquals(HusbandryOutcome.Status.FEED_SHORTAGE, sOne.outcome().status());
        assertEquals(0, sOne.wheatConsumed());
    }

    @Test
    void unloadedPenAndLostEntityObservationFreezeBreedingDecisions() {
        HusbandrySession session = new HusbandrySession(HusbandryRequest.growCows(6));
        List<CowObservation> herd = List.of(adult(1), adult(2));

        // Unloaded chunks -> FREEZE_WAIT (never feeds!)
        var unloaded = session.evaluate(new CowObservation.PenSnapshot(10L, false, true, 16, herd));
        assertEquals(HusbandrySession.ActionKind.FREEZE_WAIT, unloaded.kind());
        assertEquals(HusbandrySession.Phase.FROZEN_UNCERTAIN, session.phase());

        // Out of observation range -> APPROACH_PEN (never feeds!)
        var far = session.evaluate(new CowObservation.PenSnapshot(11L, true, false, 16, herd));
        assertEquals(HusbandrySession.ActionKind.APPROACH_PEN, far.kind());

        // Establish stable count of 2 cows
        var normal = session.evaluate(snap(12L, 16, herd));
        assertEquals(HusbandrySession.ActionKind.FEED_COW, normal.kind());

        // Cow #2 temporarily disappears from client tracking -> FREEZE_WAIT during stability window!
        var flicker = session.evaluate(snap(13L, 16, List.of(adult(1))));
        assertEquals(HusbandrySession.ActionKind.FREEZE_WAIT, flicker.kind());
        assertFalse(session.isTerminal());

        // Cow #2 reappears -> resumes feeding cleanly!
        var recovered = session.evaluate(snap(15L, 16, herd));
        assertEquals(HusbandrySession.ActionKind.FEED_COW, recovered.kind());
    }

    @Test
    void unconfirmedFeedAppliesBackoffAndBoundsRetries() {
        HusbandrySession session = new HusbandrySession(HusbandryRequest.growCows(6));
        List<CowObservation> herd = List.of(adult(1), adult(2));
        long tick = 0L;

        for (int i = 0; i < HusbandrySession.MAX_CONSECUTIVE_FAILED_FEEDS; i++) {
            var d = session.evaluate(snap(tick, 10, herd));
            if (d.kind() == HusbandrySession.ActionKind.WAIT_COOLDOWN) {
                tick += d.waitRemainingTicks() + 1;
                d = session.evaluate(snap(tick, 10, herd));
            }
            assertEquals(HusbandrySession.ActionKind.FEED_COW, d.kind());
            session.recordFeedAttempt(d.targetCowId(), 10, tick);
            // Wheat does NOT drop -> confirm timeout expires
            tick += HusbandrySession.FEED_CONFIRM_WAIT_TICKS;
            var after = session.evaluate(snap(tick, 10, herd));
            if (i == HusbandrySession.MAX_CONSECUTIVE_FAILED_FEEDS - 1) {
                assertEquals(HusbandrySession.ActionKind.FAIL, after.kind());
                assertEquals(HusbandryOutcome.Status.INTERACTION_FAILED, session.outcome().status());
            } else {
                assertNotEquals(HusbandrySession.ActionKind.FAIL, after.kind());
            }
            tick += 1;
        }
    }

    @Test
    void penBoundsContainmentAndPerimeterProtection() {
        Set<Long> interior = Set.of(
                PenBounds.packXZ(0, 0), PenBounds.packXZ(1, 0),
                PenBounds.packXZ(0, 1), PenBounds.packXZ(1, 1));
        PenBounds.BlockKey gate = new PenBounds.BlockKey(0, 64, 2);
        PenBounds.BlockKey fence = new PenBounds.BlockKey(1, 64, 2);
        PenBounds pen = new PenBounds(0, 64, 0, 1, 64, 1, interior, Set.of(gate, fence), Set.of(gate));

        // Interior cow is contained; outside cow is NOT contained.
        assertTrue(pen.containsPoint(0.5, 64.0, 0.5));
        assertFalse(pen.containsPoint(2.5, 64.0, 0.5));
        assertFalse(pen.containsPoint(0.5, 64.0, 2.5));

        // Fence, gate, and floor directly beneath fence/gate are protected from breaking.
        assertTrue(pen.isProtectedPerimeter(1, 64, 2));
        assertTrue(pen.isProtectedPerimeter(1, 63, 2));
        assertTrue(pen.isProtectedPerimeter(0, 64, 2));
        assertTrue(pen.isGate(0, 64, 2));
        assertFalse(pen.isGate(1, 64, 2));
        // Placement inside/on perimeter is protected to prevent jump-out ramps.
        assertTrue(pen.isProtectedPlacement(0, 64, 0));
        assertTrue(pen.isProtectedPlacement(1, 64, 2));
    }
}
