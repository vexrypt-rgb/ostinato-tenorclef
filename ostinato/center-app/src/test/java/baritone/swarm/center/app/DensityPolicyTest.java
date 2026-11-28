package baritone.swarm.center.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: unit tests for viewport-relative {@link DensityPolicy} thresholds, hysteresis, and operator overrides.
 */
public class DensityPolicyTest {

    @Test
    public void smallVsLargeViewportChangesTierForSameBotCount() {
        DensityPolicy policy = DensityPolicy.DEFAULT;

        // Small viewport (640x400): 1 card column, 1 card row -> cardsCap = 2; rosterRows = 11 -> rosterCap = 27
        int smallCardsCap = policy.cardsCapacity(640, 400);
        int smallRosterCap = policy.rosterCapacity(640, 400);
        // Large viewport (1920x960): 5 card columns, 3 card rows -> cardsCap = 30; rosterRows = 26 -> rosterCap = 65
        int largeCardsCap = policy.cardsCapacity(1920, 960);
        int largeRosterCap = policy.rosterCapacity(1920, 960);

        assertTrue("large viewport must hold more cards than small viewport", largeCardsCap > smallCardsCap);
        assertTrue("large viewport must hold more roster rows than small viewport", largeRosterCap > smallRosterCap);

        // Same bot count (8): ROSTER on small viewport, CARDS on large viewport
        assertEquals(DensityPolicy.Tier.ROSTER, policy.evaluate(8, 640, 400, DensityPolicy.Override.AUTO));
        assertEquals(DensityPolicy.Tier.CARDS, policy.evaluate(8, 1920, 960, DensityPolicy.Override.AUTO));

        // Same bot count (40): CLUSTER on small viewport, ROSTER on large viewport
        assertEquals(DensityPolicy.Tier.CLUSTER, policy.evaluate(40, 640, 400, DensityPolicy.Override.AUTO));
        assertEquals(DensityPolicy.Tier.ROSTER, policy.evaluate(40, 1920, 960, DensityPolicy.Override.AUTO));

        // Standard 1280x860 wall viewport (~1256x694): 11 bots stays in CARDS, 20 in ROSTER, 55 in CLUSTER
        assertEquals(DensityPolicy.Tier.CARDS, policy.evaluate(11, 1256, 694, DensityPolicy.Override.AUTO));
        assertEquals(DensityPolicy.Tier.ROSTER, policy.evaluate(20, 1256, 694, DensityPolicy.Override.AUTO));
        assertEquals(DensityPolicy.Tier.CLUSTER, policy.evaluate(55, 1256, 694, DensityPolicy.Override.AUTO));
    }

    @Test
    public void hysteresisPreventsFlickerNearBoundaries() {
        DensityPolicy policy = DensityPolicy.DEFAULT;
        int w = 1256;
        int h = 694;
        int cardsCap = policy.cardsCapacity(w, h);
        int cardsMargin = policy.hysteresisMargin(cardsCap);
        int rosterCap = policy.rosterCapacity(w, h);
        int rosterMargin = policy.hysteresisMargin(rosterCap);

        assertTrue(cardsMargin >= 1);
        assertTrue(rosterMargin >= 1);

        // Exceed cards capacity by 1 -> switches CARDS -> ROSTER
        DensityPolicy.Tier t1 = policy.evaluate(cardsCap + 1, w, h, DensityPolicy.Override.AUTO, DensityPolicy.Tier.CARDS);
        assertEquals(DensityPolicy.Tier.ROSTER, t1);

        // Drop right back to cardsCap (within hysteresis band) -> stays in ROSTER (no flicker)
        DensityPolicy.Tier t2 = policy.evaluate(cardsCap, w, h, DensityPolicy.Override.AUTO, t1);
        assertEquals(DensityPolicy.Tier.ROSTER, t2);

        // Drop clearly below cardsCap - margin -> returns cleanly to CARDS
        DensityPolicy.Tier t3 = policy.evaluate(Math.max(1, cardsCap - cardsMargin), w, h,
                DensityPolicy.Override.AUTO, t2);
        assertEquals(DensityPolicy.Tier.CARDS, t3);

        // Exceed roster capacity by 1 -> switches ROSTER -> CLUSTER
        DensityPolicy.Tier t4 = policy.evaluate(rosterCap + 1, w, h, DensityPolicy.Override.AUTO, DensityPolicy.Tier.ROSTER);
        assertEquals(DensityPolicy.Tier.CLUSTER, t4);

        // Drop right back to rosterCap (within hysteresis band) -> stays in CLUSTER
        DensityPolicy.Tier t5 = policy.evaluate(rosterCap, w, h, DensityPolicy.Override.AUTO, t4);
        assertEquals(DensityPolicy.Tier.CLUSTER, t5);

        // Drop clearly below rosterCap - margin -> returns cleanly to ROSTER
        DensityPolicy.Tier t6 = policy.evaluate(rosterCap - rosterMargin, w, h, DensityPolicy.Override.AUTO, t5);
        assertEquals(DensityPolicy.Tier.ROSTER, t6);
    }

    @Test
    public void manualOverridesAlwaysWinAndZeroBotsStaysCardsInAuto() {
        DensityPolicy policy = DensityPolicy.DEFAULT;
        assertEquals(DensityPolicy.Tier.CARDS, policy.evaluate(0, 400, 300, DensityPolicy.Override.AUTO));
        assertEquals(DensityPolicy.Tier.CARDS, policy.evaluate(500, 640, 400, DensityPolicy.Override.CARDS));
        assertEquals(DensityPolicy.Tier.ROSTER, policy.evaluate(2, 1920, 1080, DensityPolicy.Override.ROSTER));
        assertEquals(DensityPolicy.Tier.CLUSTER, policy.evaluate(2, 1920, 1080, DensityPolicy.Override.CLUSTER));

        // Zero/unlaid-out viewport falls back to default dimensions safely
        assertEquals(DensityPolicy.Tier.CARDS, policy.evaluate(3, 0, 0, DensityPolicy.Override.AUTO));
        assertEquals(DensityPolicy.Tier.CLUSTER, policy.evaluate(200, 0, 0, DensityPolicy.Override.AUTO));

        assertEquals(DensityPolicy.Override.CARDS, DensityPolicy.Override.parse("cards"));
        assertEquals(DensityPolicy.Override.ROSTER, DensityPolicy.Override.parse("roster"));
        assertEquals(DensityPolicy.Override.CLUSTER, DensityPolicy.Override.parse("cluster"));
        assertEquals(DensityPolicy.Override.AUTO, DensityPolicy.Override.parse("unknown"));
    }
}
