package adris.altoclef.swarm.survey;

import adris.altoclef.swarm.AgentLifecycle;
import adris.altoclef.swarm.SwarmCoordinator;
import adris.altoclef.swarm.SwarmLedger;
import adris.altoclef.swarm.SwarmLedger.Type;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failover and unreachable-territory behaviour on the simulated chunk world (a simulation, not a live Fabric fixture).
 * "Independent verification" compares the leader's merged board with the simulator's ground truth about which chunks the
 * bots' clients really had loaded, which the swarm code never reads.
 */
class SurveyFailoverTest {
    static final int VIEW = 6;

    static SwarmCoordinator.Config cfg() {
        SwarmCoordinator.Config c = new SwarmCoordinator.Config();
        c.heartbeatTimeoutMs = 15_000;
        c.offerTimeoutMs = 10_000;
        return c;
    }

    static Set<Long> chunksOf(SurveyArea a) {
        Set<Long> out = new HashSet<>();
        for (int x = a.minCx(); x <= a.maxCx(); x++) for (int z = a.minCz(); z <= a.maxCz(); z++) out.add(SurveyGrid.pack(x, z));
        return out;
    }

    @Test
    void disconnectingOneScoutMidRunIsRecoveredBySurvivorAndIndependentlyVerified() {
        SurveySim.Scenario sc = SurveySim.fiveScenarios(VIEW).get(0); // open flat 24x24, scouts start at opposite corners
        SurveySim.Coordinated run = new SurveySim.Coordinated(sc, cfg(), 2);
        SurveyBoard board = run.board;
        StringBuilder log = new StringBuilder();

        // run until ~40% is observed, then cut scout A off with no goodbye
        while (run.oracle.coverage() < 0.40) run.step();
        long tCut = run.clock[0];
        SurveySim.Bot botA = run.bots.get(0), botB = run.bots.get(1);
        String heldByA = null;
        for (int r = 0; r < board.regionCount(); r++) if ("scoutA".equals(board.holder(r))) heldByA = "region " + r;
        assertNotNull(heldByA, "A holds a region at the moment it is cut off");
        int regionA = Integer.parseInt(heldByA.substring(7));
        double aTravelAtCut = botA.totalTravel, bTravelAtCut = botB.totalTravel;
        long observedAtCut = board.snapshot().observed();
        run.disconnect("scoutA");
        log.append(String.format("t=%ds cut A at %.0f%% (board observed %d), A held %s%n", (tCut - run.oracle.t0) / 1000, 100 * run.oracle.coverage(), observedAtCut, heldByA));

        // 1. the claim is still on the board until the heartbeat window runs out, then it expires
        run.step();
        assertEquals("scoutA", board.holder(regionA), "claim not yet stale one step after the cut");
        long tExpired = -1;
        for (int i = 0; i < 120 && tExpired < 0; i++) {
            run.step();
            if (run.leader.agent("scoutA").lifecycle() == AgentLifecycle.DISCONNECTED) tExpired = run.clock[0];
        }
        assertTrue(tExpired > 0, "A must be written off");
        assertTrue(tExpired - tCut >= 15_000 && tExpired - tCut <= 20_000, "expires after the heartbeat timeout: " + (tExpired - tCut) + "ms");
        assertNotEquals("scoutA", board.holder(regionA), "stale claim is gone");

        // 2. remaining reachable work is visibly shown, with exact counts
        SurveyBoard.Snapshot mid = board.snapshot();
        assertTrue(mid.unknown() + mid.claimed() > 0, "work remains");
        assertEquals(mid.total(), mid.observed() + mid.claimed() + mid.unknown() + mid.unreachable());
        String shown = String.join("\n", board.describe());
        assertTrue(shown.contains("observed " + mid.observed() + "/" + mid.total()), shown);
        assertTrue(shown.contains("#") && (shown.contains("c") || shown.contains(".")), "map shows observed and remaining ground:\n" + shown);
        assertEquals(board.chunks(SurveyBoard.ChunkState.CLAIMED).size() + board.chunks(SurveyBoard.ChunkState.UNKNOWN).size(),
                mid.claimed() + mid.unknown(), "the lists a player can read equal the counts");
        log.append(String.format("t=%ds A written off (%.0fs after the cut); remaining observed=%d claimed=%d unknown=%d%n",
                (tExpired - run.oracle.t0) / 1000, (tExpired - tCut) / 1000.0, mid.observed(), mid.claimed(), mid.unknown()));

        // 3. the unfinished region is reassigned to the surviving scout
        assertEquals(1, run.leader.ledger().count(Type.AGENT_DISCONNECTED));
        long tReassigned = -1;
        for (int i = 0; i < 40 && tReassigned < 0; i++) {
            if ("scoutB".equals(board.holder(regionA))) tReassigned = run.clock[0];
            else run.step();
        }
        // B may already be busy elsewhere: it picks A's region up as soon as it is free
        for (int i = 0; i < 400 && tReassigned < 0; i++) {
            run.step();
            if ("scoutB".equals(board.holder(regionA))) tReassigned = run.clock[0];
            if (board.done()) break;
        }
        assertTrue(tReassigned > 0 || board.state(regionA) == SurveyBoard.RegionState.OBSERVED, "A's region reaches B");
        assertTrue(run.leader.ledger().count(Type.TASK_REASSIGNED) >= 1);

        // 4. everything reachable is completed by the survivor alone
        while (!board.done() && run.clock[0] - run.oracle.t0 < 3_600_000) run.step();
        assertTrue(board.done(), String.join("\n", board.describe()));
        long tDone = run.clock[0];
        assertEquals(sc.area().chunkCount(), board.snapshot().observed());
        assertEquals(0, board.snapshot().unreachable());
        assertEquals(aTravelAtCut, botA.totalTravel, 1e-9, "the dead scout did not move again");
        assertTrue(botB.totalTravel > bTravelAtCut + 100, "the survivor did the rest of the walking");
        log.append(String.format("t=%ds survey complete by scoutB alone; B travelled %.0f blocks after the cut%n", (tDone - run.oracle.t0) / 1000, botB.totalTravel - bTravelAtCut));

        // 5. independent verification against ground truth the swarm never reads
        Set<Long> boardObserved = new HashSet<>(board.chunks(SurveyBoard.ChunkState.OBSERVED));
        assertTrue(run.oracle.observed.containsAll(boardObserved), "every chunk the board calls observed was really seen by a client");
        assertEquals(chunksOf(sc.area()), run.oracle.observed, "ground truth: every chunk of the area was seen");
        assertEquals(SurveySim.feasible(sc.world(), sc.area(), 0, 0), run.oracle.observed, "and that is exactly the feasible set");
        assertEquals(chunksOf(sc.area()), boardObserved, "the board agrees");
        assertTrue(board.metrics().timeToCoverage(0.95) > 0);
        for (int r = 0; r < board.regionCount(); r++) assertTrue(board.attempts(r) <= 3, "bounded attempts, region " + r);
        System.out.println("FAILOVER (simulated):\n" + log);
    }

    @Test
    void territoryNoScoutCanSeeIsMarkedUnreachableWithAReasonAndTheSurveyEnds() {
        // a 30x30 area with an 18x18 lake in the middle: the middle chunks are more than a view radius from any shore
        SurveySim.Scenario sc = SurveySim.scenario("lake-island 30x30", 0, 0, 30, 30, VIEW, 10,
                (x, z) -> (x >= 6 && x <= 23 && z >= 6 && z <= 23) ? -1 : 10, SurveySim.at(0, 0), SurveySim.at(29, 29));
        Set<Long> feasible = SurveySim.feasible(sc.world(), sc.area(), 0, 0);
        Set<Long> unobservable = chunksOf(sc.area());
        unobservable.removeAll(feasible);
        assertFalse(unobservable.isEmpty(), "the scenario really has unobservable chunks");

        SurveySim.Coordinated run = new SurveySim.Coordinated(sc, cfg(), 2);
        SurveyBoard board = run.board;
        run.run(3_600_000, true);
        assertTrue(board.done(), "bounded: the survey ends instead of retrying forever\n" + String.join("\n", board.describe()));
        Set<Long> unreachable = new HashSet<>(board.chunks(SurveyBoard.ChunkState.UNREACHABLE));
        assertEquals(unobservable, unreachable, "exactly the chunks nobody can see are marked unreachable");
        assertEquals(feasible, new HashSet<>(board.chunks(SurveyBoard.ChunkState.OBSERVED)), "everything else is observed");
        assertTrue(run.oracle.observed.equals(feasible), "ground truth agrees");
        for (int r = 0; r < board.regionCount(); r++) {
            if (board.state(r) != SurveyBoard.RegionState.UNREACHABLE) continue;
            assertTrue(board.reason(r).contains("NO_PATH") || board.reason(r).contains("CHUNK_NOT_LOADED"), "reason: " + board.reason(r));
            assertTrue(board.attempts(r) <= 2, "bounded tries per region: " + board.attempts(r));
        }
        assertTrue(run.leader.ledger().count(Type.SURVEY_UNREACHABLE) >= 1);
        int events = run.leader.ledger().all().size();
        for (int i = 0; i < 400; i++) run.step();
        assertEquals(events, run.leader.ledger().all().size(), "once settled nothing keeps happening");
        System.out.println("UNREACHABLE (simulated): " + board.snapshot() + " reasons: " + board.reason(
                java.util.stream.IntStream.range(0, board.regionCount()).filter(r -> board.state(r) == SurveyBoard.RegionState.UNREACHABLE).findFirst().orElse(0)));
    }

    @Test
    void stoppingMidRunStopsTheScoutsAndHonoursCancellation() {
        SurveySim.Scenario sc = SurveySim.fiveScenarios(VIEW).get(0);
        SurveySim.Coordinated run = new SurveySim.Coordinated(sc, cfg(), 2);
        while (run.oracle.coverage() < 0.3) run.step();
        assertTrue(run.leader.stopSurvey(run.board.id(), run.clock[0]));
        run.step();
        run.step();
        for (SurveySim.Bot b : run.bots) assertFalse(b.moving(), "scout walks no further after cancel");
        long observed = run.board.snapshot().observed();
        double travel = run.bots.get(0).totalTravel + run.bots.get(1).totalTravel;
        for (int i = 0; i < 100; i++) run.step();
        assertEquals(observed, run.board.snapshot().observed());
        assertEquals(travel, run.bots.get(0).totalTravel + run.bots.get(1).totalTravel, 1e-9);
        assertEquals(0, run.board.snapshot().claimed());
    }

    @Test
    void theSameInputsGiveTheSameRun() {
        SurveySim.Scenario sc = SurveySim.fiveScenarios(VIEW).get(3);
        SurveySim.Run a = new SurveySim.Coordinated(sc, cfg(), 2).run(3_600_000, true);
        SurveySim.Run b = new SurveySim.Coordinated(sc, cfg(), 2).run(3_600_000, true);
        assertEquals(a, b);
    }

    @Test
    void ledgerExplainsTheFailoverInOrder() {
        SurveySim.Scenario sc = SurveySim.fiveScenarios(VIEW).get(1);
        SurveySim.Coordinated run = new SurveySim.Coordinated(sc, cfg(), 2);
        while (run.oracle.coverage() < 0.3) run.step();
        run.disconnect("scoutB");
        while (!run.board.done()) run.step();
        List<SwarmLedger.Event> ev = run.leader.ledger().all();
        int disc = -1, reassigned = -1, complete = -1;
        for (int i = 0; i < ev.size(); i++) {
            if (ev.get(i).type() == Type.AGENT_DISCONNECTED && disc < 0) disc = i;
            if (ev.get(i).type() == Type.TASK_REASSIGNED && reassigned < 0) reassigned = i;
            if (ev.get(i).type() == Type.SURVEY_COMPLETE) complete = i;
        }
        assertTrue(disc >= 0 && reassigned > disc && complete > reassigned, "disconnect, then reassignment, then completion");
    }
}
