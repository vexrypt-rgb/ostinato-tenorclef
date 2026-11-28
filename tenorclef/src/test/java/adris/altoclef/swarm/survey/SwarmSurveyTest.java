package adris.altoclef.swarm.survey;

import adris.altoclef.swarm.Assignment;
import adris.altoclef.swarm.AssignmentState;
import adris.altoclef.swarm.SwarmCoordinator;
import adris.altoclef.swarm.SwarmLedger.Type;
import adris.altoclef.swarm.AgentLifecycle;
import adris.altoclef.swarm.SwarmMessage;
import adris.altoclef.swarm.survey.SurveyHarness.Node;
import adris.altoclef.swarm.survey.SurveyHarness.ScriptedScout;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The survey objective through the real Scheduler/SwarmCoordinator/SwarmWorker path, with scripted scouts. */
class SwarmSurveyTest {
    static final SurveyArea AREA_8x4 = new SurveyArea("OVERWORLD", 0, 0, 7, 3);       // two 4x4 regions
    static final SurveyArea AREA_8x8 = new SurveyArea("OVERWORLD", 0, 0, 7, 7);       // four 4x4 regions

    static Set<Long> heldChunks(SurveyBoard b) {
        return new HashSet<>(b.chunks(SurveyBoard.ChunkState.CLAIMED));
    }

    @Test
    void twoScoutsAreGivenDistinctBoundedRegions() {
        SurveyHarness h = new SurveyHarness();
        h.join("A", 0, 0);
        h.join("B", 112, 0);
        SurveyBoard b = h.leader.startSurvey(AREA_8x4, h.clock);
        assertEquals(2, b.regionCount());
        assertNotNull(b.holder(0));
        assertNotNull(b.holder(1));
        assertNotEquals(b.holder(0), b.holder(1), "one region per scout");
        assertEquals("A", b.holder(0), "the scout standing at the west edge takes the west region");
        assertEquals("B", b.holder(1));
        SurveyBoard.Snapshot s = b.snapshot();
        assertEquals(32, s.claimed());
        assertEquals(0, s.unknown());
        assertEquals(2, s.regionsClaimed());
        // the two claimed sets are disjoint and each is at most one bounded region
        assertEquals(32, heldChunks(b).size());
    }

    @Test
    void observedChunksFromBothScoutsMergeIntoOneCompleteSurvey() {
        SurveyHarness h = new SurveyHarness();
        h.join("A", 0, 0);
        h.join("B", 112, 0);
        SurveyBoard b = h.leader.startSurvey(AREA_8x8, h.clock);
        assertTrue(h.runUntilDone(b, 200, 500), "survey must finish");
        SurveyBoard.Snapshot s = b.snapshot();
        assertEquals(64, s.observed());
        assertEquals(0, s.claimed() + s.unknown() + s.unreachable());
        assertEquals(4, s.regionsObserved());
        assertEquals(1, h.leader.ledger().count(Type.SURVEY_COMPLETE));
        assertEquals(1.0, b.metrics().coverage(), 1e-9);
        assertTrue(b.metrics().timeToCoverage(0.95) > 0);
        // the leader never takes a scout's word: each region succeeded only after merged observations settled it
        assertEquals(4, h.leader.ledger().count(Type.TASK_SUCCEEDED));
    }

    @Test
    void claimedObservedAndUnknownAreReadableMidRun() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        a.scout.perPoll = 1;
        SurveyBoard b = h.leader.startSurvey(AREA_8x8, h.clock); // one scout, four regions
        h.run(8, 500);
        SurveyBoard.Snapshot s = b.snapshot();
        assertTrue(s.observed() > 0, "some observed");
        assertTrue(s.claimed() > 0, "some claimed");
        assertTrue(s.unknown() > 0, "some unknown, nobody holds them yet");
        assertEquals(64, s.observed() + s.claimed() + s.unknown() + s.unreachable());
        String text = String.join("\n", b.describe());
        assertTrue(text.contains("CLAIMED by A") && text.contains("UNKNOWN"), text);
    }

    @Test
    void disconnectedScoutsClaimExpiresAndOnlyTheRemainderIsReassigned() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        Node b = h.join("B", 112, 0);
        a.scout.perPoll = 1;                                     // A is slow, so it is partway through its region when it drops
        SurveyBoard board = h.leader.startSurvey(AREA_8x4, h.clock);
        assertEquals("A", board.holder(0));
        h.run(6, 500);
        h.offline.add("A");                                      // vanishes: no goodbye, no more heartbeats or observations
        Set<Long> knownBeforeExpiry = new HashSet<>(board.chunks(SurveyBoard.ChunkState.OBSERVED));
        assertTrue(knownBeforeExpiry.stream().anyMatch(k -> board.grid().regionOf(SurveyGrid.unpackX(k), SurveyGrid.unpackZ(k)) == 0),
                "the leader had already merged some of A's chunks");
        assertEquals("A", board.holder(0), "claim is still held until the heartbeat window passes");

        h.run(14, 500);                                          // past heartbeatTimeoutMs (5 s) with room for B's own region
        assertEquals(AgentLifecycle.DISCONNECTED, h.leader.agent("A").lifecycle());
        assertNotEquals("A", board.holder(0), "the stale claim is gone");
        assertTrue(h.runUntilDone(board, 80, 500), "the survivor must finish what is left");

        assertEquals(1, h.leader.ledger().count(Type.AGENT_DISCONNECTED));
        assertEquals(1, h.leader.ledger().count(Type.TASK_REASSIGNED));
        assertEquals(32, board.snapshot().observed());
        Assignment westRegion = h.leader.assignments().get(0);
        assertFalse(westRegion.excluded().contains("A"), "a scout that dropped out is not blamed");
        assertEquals(2, westRegion.attempts());
        assertEquals(AssignmentState.SUCCEEDED, westRegion.state());

        // B's second offer is exactly A's region minus what the leader had already merged, not the whole region again
        Set<Long> secondOffer = b.scout.offered.get(1);
        assertFalse(secondOffer.isEmpty());
        assertTrue(secondOffer.size() < 16, "partial progress was kept: " + secondOffer.size());
        for (long k : secondOffer) assertFalse(knownBeforeExpiry.contains(k), "never re-offers a chunk already observed");
        assertEquals(16 - knownBeforeExpiry.stream().filter(k -> board.grid().regionOf(SurveyGrid.unpackX(k), SurveyGrid.unpackZ(k)) == 0).count(),
                secondOffer.size());
    }

    @Test
    void aScoutThatHeartbeatsButNeitherMovesNorObservesLosesItsClaim() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        h.join("B", 112, 0);
        a.scout.mode = ScriptedScout.Mode.FREEZE;
        SurveyBoard b = h.leader.startSurvey(AREA_8x4, h.clock);
        assertEquals("A", b.holder(0));
        h.run(30, 500);                                          // 15 s < stall window of 20 s
        assertEquals("A", b.holder(0), "not yet stale");
        h.run(30, 500);                                          // past it
        assertEquals(1, h.leader.ledger().count(Type.SURVEY_CLAIM_EXPIRED));
        assertTrue(a.scout.cancelled, "the stalled scout is told to stop");
        assertTrue(h.runUntilDone(b, 80, 500));
        assertTrue(h.leader.assignments().get(0).excluded().contains("A"), "stalling is blamed");
        assertEquals(32, b.snapshot().observed());
    }

    @Test
    void unreachableTerritoryIsMarkedWithReasonAfterBoundedRetriesAndNeverLoops() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        Node b = h.join("B", 112, 0);
        for (Node n : List.of(a, b)) {
            n.scout.unreachableRegions.add(0);                   // neither scout can ever get to the west region
            n.scout.failReason = "UNREACHABLE:NO_PATHx9";
        }
        SurveyBoard board = h.leader.startSurvey(AREA_8x4, h.clock);
        assertTrue(h.runUntilDone(board, 120, 500), String.join("\n", board.describe()));

        assertEquals(SurveyBoard.RegionState.UNREACHABLE, board.state(0));
        assertEquals(SurveyBoard.RegionState.OBSERVED, board.state(1), "the reachable region is complete");
        assertTrue(board.reason(0).contains("NO_PATH"), "clear reason: " + board.reason(0));
        assertEquals(2, board.attempts(0), "one try per capable scout, then it stops");
        assertEquals(2, board.failures(0).size());
        assertEquals(16, board.snapshot().unreachable());
        assertEquals(16, board.snapshot().observed());
        assertEquals(1, h.leader.ledger().count(Type.SURVEY_UNREACHABLE));
        assertEquals(1, h.leader.ledger().count(Type.SURVEY_COMPLETE));
        // no spinning: with everything settled, more time sends nothing and records nothing
        int events = h.leader.ledger().all().size();
        int sent = h.toAgents;
        h.run(200, 500);
        assertEquals(events, h.leader.ledger().all().size(), "idle survey generates no ledger noise");
        assertEquals(sent, h.toAgents, "idle survey sends no messages");
        assertEquals(2, a.scout.starts + b.scout.starts - 1 - 0, "A and B each started the doomed region once; B also started its own");
    }

    @Test
    void aSingleScoutThatCannotReachARegionGivesItUpAtOnceWithTheReason() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        a.scout.unreachableRegions.add(0);
        a.scout.failReason = "UNREACHABLE:CHUNK_NOT_LOADEDx16";
        SurveyBoard board = h.leader.startSurvey(AREA_8x4, h.clock);
        assertTrue(h.runUntilDone(board, 80, 500));
        assertEquals(SurveyBoard.RegionState.UNREACHABLE, board.state(0));
        assertTrue(board.reason(0).contains("CHUNK_NOT_LOADED"), board.reason(0));
        assertEquals(1, board.attempts(0), "no capable scout left, so no second try");
        assertEquals(SurveyBoard.RegionState.OBSERVED, board.state(1));
        assertEquals(16, board.snapshot().observed());
        assertEquals(16, board.snapshot().unreachable());
    }

    @Test
    void claimingSuccessWithoutObservationsIsNotVerified() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        a.scout.mode = ScriptedScout.Mode.CLAIM_WITHOUT_LOOKING;
        SurveyBoard b = h.leader.startSurvey(AREA_8x4, h.clock);
        h.run(3, 500);
        assertTrue(h.leader.ledger().count(Type.TASK_UNVERIFIED) >= 1);
        assertEquals(0, b.snapshot().observed(), "nothing was observed, so nothing is marked observed");
        assertNotEquals(SurveyBoard.RegionState.OBSERVED, b.state(0));
    }

    @Test
    void observationsFromAnotherDimensionAreIgnoredAndLogged() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        a.scout.dim = "NETHER";                                  // this client is in the wrong dimension
        SurveyBoard b = h.leader.startSurvey(AREA_8x4, h.clock);
        h.run(6, 500);
        assertEquals(0, b.snapshot().observed());
        assertTrue(h.leader.ledger().count(Type.SURVEY_REJECTED) >= 1);
        assertTrue(b.rejectedReports() >= 1);
    }

    @Test
    void chunksSeenOutsideTheReportersOwnRegionStillCountAndCloseOpenRegions() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        a.scout.perPoll = 0;                                     // A sits on region 0 and sees nothing
        SurveyBoard board = h.leader.startSurvey(AREA_8x8, h.clock); // four regions, one scout: three stay open
        assertEquals(3, board.snapshot().regionsUnknown());
        // another client (not a scout, so it is never given survey work) reports every chunk of the still-open region 3
        h.join("B", 112, 112, java.util.EnumSet.of(adris.altoclef.swarm.Capability.CAN_CRAFT));
        h.leader.onMessage("B", SwarmMessage.of("cov", "a", "none", "sv", board.id(), "dim", "OVERWORLD", "m", "3:ffff"), h.clock);
        assertEquals(16, board.snapshot().observed());
        assertEquals(SurveyBoard.RegionState.OBSERVED, board.state(3));
        h.run(2, 500);
        Assignment region3 = h.leader.assignments().stream().filter(x -> x.objective.survey().region() == 3).findFirst().orElseThrow();
        assertEquals(AssignmentState.SUCCEEDED, region3.state(), "closed without anyone walking there");
        assertNull(region3.agentId());
        assertEquals(0, a.scout.offered.stream().filter(s -> s.contains(SurveyGrid.pack(4, 4))).count(), "never offered");
    }

    @Test
    void stoppingASurveyCallsOffTheWorkAndKeepsWhatWasObserved() {
        SurveyHarness h = new SurveyHarness();
        Node a = h.join("A", 0, 0);
        a.scout.perPoll = 1;
        SurveyBoard b = h.leader.startSurvey(AREA_8x8, h.clock);
        h.run(6, 500);
        long seen = b.snapshot().observed();
        assertTrue(seen > 0);
        assertTrue(h.leader.stopSurvey(b.id(), h.clock));
        assertTrue(a.scout.cancelled);
        assertEquals(seen, b.snapshot().observed());
        assertEquals(0, b.snapshot().claimed());
        for (Assignment as : h.leader.assignments()) assertTrue(as.state().isTerminal());
        h.run(20, 500);
        assertEquals(seen, b.snapshot().observed(), "nothing is assigned after a stop");
        assertFalse(h.leader.stopSurvey(b.id(), h.clock), "already stopped");
    }

    @Test
    void coordinationWorkIsProportionalToRegionsNotToTime() {
        SurveyHarness h = new SurveyHarness();
        h.join("A", 0, 0);
        h.join("B", 112, 0);
        SurveyBoard b = h.leader.startSurvey(AREA_8x8, h.clock);
        assertTrue(h.runUntilDone(b, 200, 500));
        int regions = b.regionCount();
        int toAgents = h.toAgents;
        assertTrue(toAgents <= 3 * regions, "leader->scout messages " + toAgents + " for " + regions + " regions");
        h.run(2_000, 500);
        assertEquals(toAgents, h.toAgents, "an idle, finished survey costs nothing per tick");
    }

    @Test
    void oversizedAreasAndTooManySurveysAreRefused() {
        SurveyHarness h = new SurveyHarness();
        assertThrows(IllegalArgumentException.class, () -> h.leader.startSurvey(new SurveyArea("OVERWORLD", 0, 0, 500, 500), h.clock));
        for (int i = 0; i < 4; i++) h.leader.startSurvey(new SurveyArea("OVERWORLD", i * 100, 0, i * 100 + 7, 3), h.clock);
        assertThrows(IllegalStateException.class, () -> h.leader.startSurvey(AREA_8x4, h.clock));
    }

    @Test
    void jobsStatusSummarisesASurveyInOneLine() {
        SurveyHarness h = new SurveyHarness();
        h.join("A", 0, 0);
        h.leader.startSurvey(AREA_8x8, h.clock);
        List<String> lines = h.leader.status();
        assertEquals(1, lines.stream().filter(l -> l.startsWith("survey ")).count());
        assertEquals(0, lines.stream().filter(l -> l.startsWith("task ")).count(), "regions are not listed as separate tasks");
    }
}
