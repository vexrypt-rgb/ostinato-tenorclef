package adris.altoclef.swarm.survey;

import adris.altoclef.swarm.SwarmCoordinator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paired A/B trials on a simulated chunk world (NOT a live Fabric fixture). Same two bots, same start positions, same
 * view radius and movement physics in both arms; only the controller differs:
 * A = the coordinated survey through the real SwarmCoordinator/SwarmWorker/SurveyScout,
 * B = the existing ExploreProcess logic run independently on each bot from its own start (opposite ends).
 */
class SurveyAbTrialTest {
    static final int VIEW = 6;

    static SwarmCoordinator.Config cfg() {
        SwarmCoordinator.Config c = new SwarmCoordinator.Config();
        c.heartbeatTimeoutMs = 15_000;
        c.offerTimeoutMs = 10_000;
        return c;
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    /** Sensitivity: the live chat link beats at 12 s, not the headless 2 s. Coverage still has to beat the baseline. */
    @Test
    void liveLikeHeartbeatStillBeatsBaseline() {
        List<Double> aDup = new ArrayList<>(), bDup = new ArrayList<>(), aT = new ArrayList<>(), bT = new ArrayList<>();
        StringBuilder t = new StringBuilder("12 s heartbeat (live chat link rate):\n");
        for (SurveySim.Scenario sc : SurveySim.fiveScenarios(VIEW)) {
            SurveySim.Coordinated a = new SurveySim.Coordinated(sc, cfg(), 2, 12_000);
            SurveySim.Run ra = a.run(3_600_000, true);
            SurveySim.Run rb = SurveySim.runBaseline(sc, 3_600_000);
            t.append(String.format(Locale.ROOT, "  %-24s coordinated t95=%.0fs dup=%.0f | baseline t95=%.0fs dup=%.0f%n", sc.name(), ra.t95Sec(), ra.dupTravelAt95(), rb.t95Sec(), rb.dupTravelAt95()));
            aDup.add(ra.dupTravelAt95()); bDup.add(rb.dupTravelAt95()); aT.add(ra.t95Sec()); bT.add(rb.t95Sec());
        }
        t.append(String.format(Locale.ROOT, "  MEDIAN dup %.0f vs %.0f, t95 %.0f vs %.0f%n", median(aDup), median(bDup), median(aT), median(bT)));
        System.out.println(t);
        assertTrue(median(aDup) < median(bDup), t.toString());
        assertTrue(median(aT) <= median(bT), t.toString());
    }

    /**
     * Control for the harness itself. With ONE bot there is nobody to coordinate with, so any gap here is the baseline's
     * lack of an area bound (its spiral also walks the chunks outside the marked area), not coordination. It is printed
     * so the A/B numbers are not over-read; the only assertion is that one coordinated scout does not do worse.
     */
    @Test
    void oneBotControlShowsHowMuchOfTheGapIsJustTheAreaBound() {
        SurveySim.Scenario sc = SurveySim.fiveScenarios(VIEW).get(0);
        SurveySim.Scenario solo = new SurveySim.Scenario(sc.name() + " (1 bot)", sc.world(), sc.area(), List.of(sc.starts().get(0)));
        SurveySim.Run ra = new SurveySim.Coordinated(solo, cfg(), 1).run(3_600_000, true);
        SurveySim.Run rb = SurveySim.runBaseline(solo, 3_600_000);
        System.out.printf(Locale.ROOT, "1-bot control %s: coordinated t95=%.0fs dup=%.0f | baseline t95=%.0fs dup=%.0f off=%.0f%n",
                solo.name(), ra.t95Sec(), ra.dupTravelAt95(), rb.t95Sec(), rb.dupTravelAt95(), rb.offAreaAt95());
        assertTrue(ra.finalCoverage() >= 0.999);
        assertTrue(ra.t95Sec() <= rb.t95Sec(), "one coordinated scout is not slower than one explore");
    }

    @Test
    void coordinatedSurveyBeatsOppositeStartExploreOnDuplicateTravelWithoutSlowerCoverage() {
        List<Double> aDup = new ArrayList<>(), bDup = new ArrayList<>(), aT = new ArrayList<>(), bT = new ArrayList<>();
        List<Double> aWaste = new ArrayList<>(), bWaste = new ArrayList<>();
        StringBuilder table = new StringBuilder();
        table.append(String.format(Locale.ROOT, "%-24s | %-18s | %8s %9s %9s %9s | %8s %9s %9s%n", "scenario", "arm", "t95 (s)", "dup@95", "off@95", "tot@95", "tFull", "dupFull", "totFull"));
        for (SurveySim.Scenario sc : SurveySim.fiveScenarios(VIEW)) {
            SurveySim.Coordinated a = new SurveySim.Coordinated(sc, cfg(), 2);
            SurveySim.Run ra = a.run(3_600_000, true);
            SurveySim.Run rb = SurveySim.runBaseline(sc, 3_600_000);
            SurveySim.Run ri = SurveySim.runIndependentBounded(sc, 3_600_000);
            for (SurveySim.Run r : List.of(ra, rb, ri)) {
                table.append(String.format(Locale.ROOT, "%-24s | %-18s | %8.0f %9.0f %9.0f %9.0f | %8.0f %9.0f %9.0f  cov=%.3f%n", sc.name(), r.arm(),
                        r.t95Sec(), r.dupTravelAt95(), r.offAreaAt95(), r.totalTravelAt95(), r.tFullSec(), r.dupTravelFull(), r.totalTravelFull(), r.finalCoverage()));
            }
            assertTrue(ra.t95Sec() > 0 && rb.t95Sec() > 0, sc.name() + ": both arms must reach 95%");
            aDup.add(ra.dupTravelAt95()); bDup.add(rb.dupTravelAt95());
            aT.add(ra.t95Sec()); bT.add(rb.t95Sec());
            aWaste.add(ra.dupTravelAt95() + ra.offAreaAt95()); bWaste.add(rb.dupTravelAt95() + rb.offAreaAt95());
        }
        table.append(String.format(Locale.ROOT, "MEDIAN duplicate travel @95%%: coordinated %.0f vs baseline %.0f blocks%n", median(aDup), median(bDup)));
        table.append(String.format(Locale.ROOT, "MEDIAN dup+offArea travel @95%%: coordinated %.0f vs baseline %.0f blocks%n", median(aWaste), median(bWaste)));
        table.append(String.format(Locale.ROOT, "MEDIAN time to 95%%: coordinated %.0f s vs baseline %.0f s%n", median(aT), median(bT)));
        System.out.println(table);
        assertTrue(median(aDup) < median(bDup), "median duplicate travel must be lower:\n" + table);
        assertTrue(median(aT) <= median(bT), "median time to 95% must not increase:\n" + table);
    }
}
