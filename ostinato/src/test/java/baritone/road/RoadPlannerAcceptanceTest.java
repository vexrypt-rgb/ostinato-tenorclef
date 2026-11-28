/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.road;

import baritone.road.ConstraintFailure.Kind;
import baritone.road.RoadCorridorFixtures.Fixture;
import baritone.road.RoadCorridorFixtures.SyntheticWorld;
import baritone.road.RoadProbe.CellKind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Comprehensive unit and acceptance test suite for Ostinato's terrain road planner, builder access planner,
 * physical verifier, protected-plot guards, infeasibility diagnostics, and baseline comparison across the 5 held-out
 * 40–80-block corridors.
 */
public class RoadPlannerAcceptanceTest {

    @Test
    public void fiveHeldOutCorridorsAcceptanceAndBaselineComparison() {
        List<Fixture> fixtures = RoadCorridorFixtures.heldOutFive();
        assertEquals("Expected exactly 5 held-out corridors", 5, fixtures.size());

        int feasiblePassed = 0;
        int infeasibleVerified = 0;
        StringBuilder table = new StringBuilder();
        table.append("\n=== TERRAIN ROAD ACCEPTANCE & BASELINE COMPARISON (5 HELD-OUT CORRIDORS) ===\n");
        table.append(String.format(Locale.ROOT,
                "%-36s | %-8s | %-11s | %-9s | %-10s | %-11s | %-9s | %-10s | %s%n",
                "Fixture", "Expected", "Road Placed", "Road ms", "Road Walk",
                "Base Placed", "Base ms", "Base Walk", "Notes"));
        table.append("-".repeat(138)).append('\n');

        for (Fixture f : fixtures) {
            RoadSpec spec = f.spec();
            SyntheticWorld world = f.world();
            Map<Long, CellKind> protectedBefore = RoadVerifier.snapshotProtectedZones(spec, world);

            // 1. Evaluate baseline ("simply widening the original walking route") on an isolated overlay.
            WidenedWalkingBaseline.BaselineResult baseline = WidenedWalkingBaseline.evaluate(spec, world);

            // 2. Plan with RoadPlanner (survey bounded corridor, compare candidates, check access & budget).
            RoadPlanner.PlanResult plan = RoadPlanner.plan(spec, world);

            // Survey work must stay strictly bounded to the requested corridor.
            assertNotNull("Corridor survey must be present for " + f.id(), plan.survey());
            int expectedCols = (spec.maxCorridorX() - spec.minCorridorX() + 1)
                    * (spec.maxCorridorZ() - spec.minCorridorZ() + 1);
            assertEquals("Survey must stay strictly bounded to corridor", expectedCols, plan.survey().surveyedColumns);

            if (f.expectedFeasible()) {
                assertTrue("Expected feasible corridor " + f.id() + " failed: " + plan.failures(), plan.feasible());
                RoadAlignment chosen = plan.chosen();
                assertNotNull(chosen);
                assertTrue("Candidate comparison should evaluate alignments", !plan.candidates().isEmpty());
                assertTrue("Placed blocks must respect 48-block budget on " + f.id() + ": " + chosen.placedBlockCount(),
                        chosen.placedBlockCount() <= spec.maxPlacedBlocks());
                assertTrue("Builder access plan must be feasible on " + f.id(), chosen.accessPlan().feasible());

                // Verify preview text includes footprint and bill of materials.
                String preview = plan.formatPreview();
                assertTrue("Preview must include Bill of Materials", preview.contains("Bill of Materials"));
                assertTrue("Preview must include protectedEdits=0", preview.contains("protectedEdits=0"));

                // 3. Execute the planned BuilderAccessPlan step-by-step as an ordinary player (verifying every support face & stand).
                int placedCount = executeAccessPlanWithOrdinaryPlayerInvariants(spec, world, chosen.accessPlan());
                assertEquals("Actual placed blocks must equal planned BOM", chosen.placedBlockCount(), placedCount);
                assertTrue("Actual placed blocks must be <= 48", placedCount <= spec.maxPlacedBlocks());

                // 4. Independent second traveler bidirectional no-jump walkability & protected-plot audit.
                RoadVerifier.VerificationReport report = RoadVerifier.verify(spec, chosen, world, protectedBefore, placedCount);
                assertTrue("Post-build verification failed on " + f.id() + ": " + report.summary(), report.passed());
                assertEquals("Zero edits allowed inside protected plot on " + f.id(), 0, report.protectedPlotEdits());
                assertTrue("Forward no-jump traversal must pass on " + f.id(), report.traversal().forwardReachable());
                assertTrue("Reverse no-jump traversal must pass on " + f.id(), report.traversal().reverseReachable());
                assertTrue("Completed road must be continuously 2-wide on " + f.id(), report.traversal().twoWideContinuous());

                feasiblePassed++;
                table.append(String.format(Locale.ROOT,
                        "%-36s | %-8s | %3d / %-5d | %6.2f ms | %-10s | %3d / %-5d | %6.2f ms | %-10s | %s%n",
                        f.id(), "FEASIBLE",
                        placedCount, spec.maxPlacedBlocks(), plan.plannerTimeMs(), "PASS (2x)",
                        baseline.requiredPlacedBlocks(), spec.maxPlacedBlocks(), baseline.plannerTimeMs(),
                        baseline.bidirectionalNoJumpPass() ? "PASS (2x)" : "FAIL",
                        baseline.failureNote().isEmpty() ? "both passed" : "base: " + baseline.failureNote()));
            } else {
                assertFalse("Expected infeasible corridor " + f.id() + " unexpectedly succeeded", plan.feasible());
                assertNull("Infeasible plan must not choose an alignment", plan.chosen());
                for (Kind expectedKind : f.expectedFailureKinds()) {
                    assertTrue("Infeasible corridor " + f.id() + " must report " + expectedKind
                            + ", got " + plan.failures(), plan.hasFailure(expectedKind));
                }
                // Zero partial unauthorized edits on infeasible attempt.
                for (Map.Entry<Long, CellKind> e : protectedBefore.entrySet()) {
                    long k = e.getKey();
                    assertEquals("Zero edits inside protected plot on infeasible corridor",
                            e.getValue(), world.get(ProtectedZone.x(k), ProtectedZone.y(k), ProtectedZone.z(k)));
                }
                infeasibleVerified++;
                table.append(String.format(Locale.ROOT,
                        "%-36s | %-8s | %3d / %-5d | %6.2f ms | %-10s | %3d / %-5d | %6.2f ms | %-10s | %s%n",
                        f.id(), "INFEAS.",
                        0, spec.maxPlacedBlocks(), plan.plannerTimeMs(), "REJECTED",
                        baseline.requiredPlacedBlocks(), spec.maxPlacedBlocks(), baseline.plannerTimeMs(),
                        baseline.bidirectionalNoJumpPass() ? "PASS (2x)" : "FAIL",
                        "blockers=" + plan.failures().stream().map(d -> d.kind().name()).toList()));
            }
        }

        System.out.println(table);
        assertTrue("Pass criteria requires >= 4 feasible corridors passing", feasiblePassed >= 4);
        assertTrue("Pass criteria requires >= 1 infeasible corridor reporting exact blocker", infeasibleVerified >= 1);
    }

    /**
     * Simulates an ordinary player executing every op in {@link BuilderAccessPlan} strictly in order, asserting:
     * 1. Zero edits inside any protected zone.
     * 2. Stand cell is currently walkable, non-protected, and within ordinary reach (distSqr <= 9).
     * 3. Every PLACE op has an existing solid adjacent support face (cardinal neighbor or block below) at click time.
     * 4. Every PLACE op only writes into a replaceable cell (air/plant/fluid) and every CLEAR op removes a breakable block.
     */
    private static int executeAccessPlanWithOrdinaryPlayerInvariants(
            RoadSpec spec,
            SyntheticWorld world,
            BuilderAccessPlan accessPlan) {
        int placed = 0;
        for (BuilderAccessPlan.BuildOp op : accessPlan.operations()) {
            assertFalse("Refusing edit inside protected zone at (" + op.x() + "," + op.y() + "," + op.z() + ")",
                    spec.isProtected(op.x(), op.y(), op.z()));
            assertFalse("Stand cell must never lie inside protected zone at (" + op.standX() + "," + op.standZ() + ")",
                    spec.isProtectedColumn(op.standX(), op.standZ()));

            // Verify stand cell is currently safe walkable ground at deckY+1.
            assertTrue("Stand floor must be solid full block before op " + op,
                    world.get(op.standX(), op.standY() - 1, op.standZ()).isWalkableFullDeck());
            assertTrue("Stand feet must be clear before op " + op,
                    world.get(op.standX(), op.standY(), op.standZ()).isPassableHeadroom());
            assertTrue("Stand head must be clear before op " + op,
                    world.get(op.standX(), op.standY() + 1, op.standZ()).isPassableHeadroom());

            int dx = op.x() - op.standX();
            int dz = op.z() - op.standZ();
            assertTrue("Target out of ordinary reach from stand for op " + op,
                    dx * dx + dz * dz <= BuilderAccessPlan.SAFE_STAND_DIST_SQR);

            if (op.kind() == BuilderAccessPlan.OpKind.CLEAR) {
                CellKind before = world.get(op.x(), op.y(), op.z());
                assertTrue("CLEAR target must be clearable obstacle, was " + before + " at " + op,
                        before.isClearableObstacle());
                world.set(op.x(), op.y(), op.z(), CellKind.AIR);
            } else {
                // Must not stand inside the block being placed.
                assertFalse("Cannot stand inside placement column " + op,
                        op.standX() == op.x() && op.standZ() == op.z());
                CellKind before = world.get(op.x(), op.y(), op.z());
                assertTrue("PLACE target must be replaceable, was " + before + " at " + op,
                        before.isReplaceableForDeck());
                CellKind support = world.get(op.supportX(), op.supportY(), op.supportZ());
                assertTrue("PLACE support face must be solid at click time, was " + support + " for " + op,
                        support.canPlaceAgainst());
                int manhattanSupport = Math.abs(op.x() - op.supportX())
                        + Math.abs(op.y() - op.supportY())
                        + Math.abs(op.z() - op.supportZ());
                assertEquals("Support block must be face-adjacent to placed deck block", 1, manhattanSupport);
                world.set(op.x(), op.y(), op.z(), CellKind.SOLID_FULL);
                placed++;
            }
        }
        return placed;
    }

    @Test
    public void infeasibleProtectedZoneCompleteWallReportsExactConstraint() {
        SyntheticWorld w = new SyntheticWorld(63);
        // Protected plot spans the entire corridor Z range [-30..30], blocking every route.
        ProtectedZone wall = ProtectedZone.columns("sanctuary_wall", 20, -30, 26, 30);
        w.populateProtectedField(wall);
        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, 64, 0, "village", 44, 64, 0, 48, List.of(wall));

        RoadPlanner.PlanResult res = RoadPlanner.plan(spec, w);
        assertFalse(res.feasible());
        assertTrue("Expected PROTECTED_ZONE, got " + res.failures(), res.hasFailure(Kind.PROTECTED_ZONE));
    }

    @Test
    public void infeasibleLowUnbreakableCeilingReportsClearanceBlocked() {
        SyntheticWorld w = new SyntheticWorld(63);
        // Unbreakable bedrock barrier at headroom height across the entire corridor.
        for (int z = -30; z <= 30; z++) {
            w.set(22, 65, z, CellKind.UNBREAKABLE);
        }
        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, 64, 0, "village", 44, 64, 0, 48, List.of());

        RoadPlanner.PlanResult res = RoadPlanner.plan(spec, w);
        assertFalse(res.feasible());
        assertTrue("Expected CLEARANCE_BLOCKED, got " + res.failures(), res.hasFailure(Kind.CLEARANCE_BLOCKED));
    }

    @Test
    public void infeasibleElevationMismatchReportsExactConstraint() {
        SyntheticWorld w = new SyntheticWorld(63);
        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, 64, 0, "village", 44, 66, 0, 48, List.of());

        RoadPlanner.PlanResult res = RoadPlanner.plan(spec, w);
        assertFalse(res.feasible());
        assertTrue("Expected ELEVATION_MISMATCH, got " + res.failures(), res.hasFailure(Kind.ELEVATION_MISMATCH));
    }

    @Test
    public void infeasibleUnloadedStripReportsUnloadedTerrain() {
        SyntheticWorld w = new SyntheticWorld(63);
        for (int z = -30; z <= 30; z++) {
            w.set(22, 63, z, CellKind.UNKNOWN);
            w.set(22, 64, z, CellKind.UNKNOWN);
            w.set(22, 65, z, CellKind.UNKNOWN);
        }
        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, 64, 0, "village", 44, 64, 0, 48, List.of());

        RoadPlanner.PlanResult res = RoadPlanner.plan(spec, w);
        assertFalse(res.feasible());
        assertTrue("Expected UNLOADED_TERRAIN, got " + res.failures(), res.hasFailure(Kind.UNLOADED_TERRAIN));
    }

    @Test
    public void infeasibleBuilderAccessWhenStartSurroundedByWaterReportsAccessBlocked() {
        SyntheticWorld w = new SyntheticWorld(63);
        // Start (0,64,0) sits over water with no solid bank anywhere within radius 4 (cannot stand or click support).
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                w.set(x, 63, z, CellKind.FLUID);
                w.set(x, 62, z, CellKind.FLUID);
            }
        }
        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, 64, 0, "village", 44, 64, 0, 48, List.of());

        RoadPlanner.PlanResult res = RoadPlanner.plan(spec, w);
        assertFalse(res.feasible());
        assertTrue("Expected BUILDER_ACCESS_BLOCKED, got " + res.failures(), res.hasFailure(Kind.BUILDER_ACCESS_BLOCKED));
    }

    @Test
    public void specParserResolvesCoordinatesWaypointsProtectedZonesAndOptions() {
        RoadSpecParser.EndpointResolver resolver = tok -> switch (tok) {
            case "hall" -> new int[]{0, 64, 0};
            case "village" -> new int[]{52, 64, 0};
            case "field" -> new int[]{15, 64, 0};
            default -> null;
        };
        RoadSpec spec = RoadSpecParser.parse(
                RoadSpecParser.tokenize("hall village protect=10,-4:20,4 protect=field:3 budget=48 width=2 block=minecraft:stone_bricks margin=20"),
                resolver,
                () -> List.of(ProtectedZone.box("sel1", 1, 60, 1, 2, 70, 2)));

        assertEquals("hall", spec.startLabel());
        assertEquals("village", spec.endLabel());
        assertEquals(0, spec.startX());
        assertEquals(52, spec.endX());
        assertEquals(48, spec.maxPlacedBlocks());
        assertEquals(2, spec.width());
        assertEquals(20, spec.corridorMargin());
        assertEquals("stone_bricks", spec.deckBlockId());
        assertEquals(2, spec.protectedZones().size());
        assertTrue(spec.isProtected(15, 64, 0));
    }

    @Test
    public void specParserSupportsSelectionProtectedZone() {
        RoadSpec spec = RoadSpecParser.parse(
                RoadSpecParser.tokenize("0,64,0 44,64,0 protect=sel budget=40"),
                null,
                () -> List.of(ProtectedZone.box("sel1", 10, 62, -4, 18, 68, 4)));
        assertEquals(40, spec.maxPlacedBlocks());
        assertEquals(1, spec.protectedZones().size());
        assertTrue(spec.isProtected(14, 64, 0));
        assertFalse(spec.isProtected(14, 70, 0));
    }
}
