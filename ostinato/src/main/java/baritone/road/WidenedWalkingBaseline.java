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

import baritone.road.RoadProbe.CellKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Baseline comparison strategy: compute an ordinary 1-wide walking route (allowing 1-block jumps/descents and wading
 * across streams, like Baritone's standard walker) and simply widen that route to 2 blocks wide.
 * <p>
 * Used in acceptance evaluation to compare materials used, planner time, protected-plot safety, and bidirectional
 * no-jump traversal against {@link RoadPlanner}.
 */
public final class WidenedWalkingBaseline {

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int MAX_SURFACE_SCAN = 6;

    private WidenedWalkingBaseline() {}

    public record BaselineResult(
            boolean walkingRouteFound,
            boolean withinBudget,
            boolean zeroProtectedViolations,
            int requiredPlacedBlocks,
            int appliedPlacedBlocks,
            int clearedBlocks,
            int protectedPlotColumnsClipped,
            int elevationChangesOnRoute,
            double plannerTimeMs,
            RoadVerifier.TraversalResult traversalAfterBuild,
            String failureNote
    ) {
        public boolean bidirectionalNoJumpPass() {
            return withinBudget && zeroProtectedViolations
                    && traversalAfterBuild != null && traversalAfterBuild.bidirectionalNoJumpPass();
        }

        public String oneLineSummary(int budget) {
            return String.format(Locale.ROOT,
                    "baseline: placed=%d/%d cleared=%d protClipped=%d elevSteps=%d time=%.2fms noJump=%s%s",
                    requiredPlacedBlocks, budget, clearedBlocks, protectedPlotColumnsClipped,
                    elevationChangesOnRoute, plannerTimeMs,
                    bidirectionalNoJumpPass() ? "PASS" : "FAIL",
                    failureNote.isEmpty() ? "" : " (" + failureNote + ")");
        }
    }

    /**
     * Evaluates the widened-walking-route baseline on a read-only probe (simulating the edits on an isolated overlay
     * so the caller's world is untouched) and verifies bidirectional no-jump walkability on the resulting terrain.
     */
    public static BaselineResult evaluate(RoadSpec spec, RoadProbe probe) {
        long t0 = System.nanoTime();
        int deckY = spec.deckY();
        int minX = spec.minCorridorX();
        int maxX = spec.maxCorridorX();
        int minZ = spec.minCorridorZ();
        int maxZ = spec.maxCorridorZ();

        // Step 1: Find ordinary 1-wide walking route (allows 1-block jumps up/down & wading through water; avoids protected interior).
        List<long[]> walkPath = findOrdinaryWalkingRoute(spec, probe, deckY, minX, maxX, minZ, maxZ);
        if (walkPath == null) {
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            return new BaselineResult(false, true, true, 0, 0, 0, 0, 0, ms,
                    new RoadVerifier.TraversalResult(false, false, -1, -1, false), "no walking route found");
        }

        // Step 2: Simply widen the 1-wide route to width=2 by adding the right-hand lateral column at each step.
        LinkedHashSet<Long> widenedCols = new LinkedHashSet<>();
        Map<Long, Integer> colFloorY = new HashMap<>();
        int elevChanges = 0;
        int prevY = (int) walkPath.get(0)[1];
        for (int i = 0; i < walkPath.size(); i++) {
            int cx = ProtectedZone.colX(walkPath.get(i)[0]);
            int cz = ProtectedZone.colZ(walkPath.get(i)[0]);
            int fy = (int) walkPath.get(i)[1];
            if (fy != prevY) {
                elevChanges++;
                prevY = fy;
            }
            int dirX = i + 1 < walkPath.size()
                    ? Integer.signum(ProtectedZone.colX(walkPath.get(i + 1)[0]) - cx)
                    : (i > 0 ? Integer.signum(cx - ProtectedZone.colX(walkPath.get(i - 1)[0])) : 1);
            int dirZ = i + 1 < walkPath.size()
                    ? Integer.signum(ProtectedZone.colZ(walkPath.get(i + 1)[0]) - cz)
                    : (i > 0 ? Integer.signum(cz - ProtectedZone.colZ(walkPath.get(i - 1)[0])) : 0);
            // Lateral offset perpendicular to travel direction (right side).
            int latX = -dirZ;
            int latZ = dirX;
            if (latX == 0 && latZ == 0) latZ = 1;

            long c0 = ProtectedZone.colKey(cx, cz);
            long c1 = ProtectedZone.colKey(cx + latX, cz + latZ);
            widenedCols.add(c0);
            colFloorY.putIfAbsent(c0, fy);
            if (cx + latX >= minX && cx + latX <= maxX && cz + latZ >= minZ && cz + latZ <= maxZ) {
                widenedCols.add(c1);
                colFloorY.putIfAbsent(c1, fy);
            }
        }

        // Step 3: Compute edits needed to deck/clear the widened route (respecting its own elevation profile
        // at deckY over water, and keeping existing natural ground elevation on hills — classic naive widening).
        int requiredPlaced = 0;
        int cleared = 0;
        int protClipped = 0;
        Map<Long, CellKind> overlay = new HashMap<>();
        int appliedPlaced = 0;

        for (long col : widenedCols) {
            int cx = ProtectedZone.colX(col);
            int cz = ProtectedZone.colZ(col);
            int targetFloorY = colFloorY.getOrDefault(col, deckY);
            if (spec.isProtectedColumn(cx, cz)) {
                protClipped++;
                continue; // Do not edit inside protected plot during audit; record the clip violation.
            }
            CellKind floor = probe.get(cx, targetFloorY, cz);
            if (!floor.isWalkableFullDeck()) {
                requiredPlaced++;
                if (appliedPlaced < spec.maxPlacedBlocks() && floor != CellKind.HAZARD && floor != CellKind.UNKNOWN) {
                    if (floor == CellKind.SOLID_NON_FULL_CLEARABLE) {
                        cleared++;
                    }
                    overlay.put(ProtectedZone.key(cx, targetFloorY, cz), CellKind.SOLID_FULL);
                    appliedPlaced++;
                }
            }
            for (int dy = 1; dy <= spec.headroom(); dy++) {
                CellKind above = probe.get(cx, targetFloorY + dy, cz);
                if (above.isClearableObstacle()) {
                    cleared++;
                    overlay.put(ProtectedZone.key(cx, targetFloorY + dy, cz), CellKind.AIR);
                }
            }
        }

        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        RoadProbe afterProbe = (x, y, z) -> overlay.getOrDefault(ProtectedZone.key(x, y, z), probe.get(x, y, z));
        RoadVerifier.TraversalResult traversal = RoadVerifier.walkIndependentNoJump(spec, afterProbe);

        boolean withinBudget = requiredPlaced <= spec.maxPlacedBlocks();
        boolean zeroProt = protClipped == 0;
        List<String> notes = new ArrayList<>();
        if (!withinBudget) notes.add("exceeds budget " + requiredPlaced + ">" + spec.maxPlacedBlocks());
        if (!zeroProt) notes.add("clipped protected plot (" + protClipped + " cols)");
        if (elevChanges > 0) notes.add("requires " + elevChanges + " jump/step changes");
        if (!traversal.bidirectionalNoJumpPass()) notes.add("fails no-jump walk");

        return new BaselineResult(
                true, withinBudget, zeroProt,
                requiredPlaced, appliedPlaced, cleared, protClipped, elevChanges,
                ms, traversal, String.join("; ", notes));
    }

    /** Finds a standard 1-wide walking path (1-block jumps/descents + water wading allowed, shortest cost). */
    private static List<long[]> findOrdinaryWalkingRoute(
            RoadSpec spec, RoadProbe probe, int deckY,
            int minX, int maxX, int minZ, int maxZ) {
        int startFloor = surfaceFloorY(probe, spec.startX(), spec.startZ(), deckY);
        int endFloor = surfaceFloorY(probe, spec.endX(), spec.endZ(), deckY);
        if (startFloor == Integer.MIN_VALUE || endFloor == Integer.MIN_VALUE) {
            return null;
        }
        long startKey = ProtectedZone.colKey(spec.startX(), spec.startZ());
        long endKey = ProtectedZone.colKey(spec.endX(), spec.endZ());

        Map<Long, Double> bestG = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> floorMap = new HashMap<>();

        bestG.put(startKey, 0.0);
        floorMap.put(startKey, startFloor);

        PriorityQueue<WalkNode> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        pq.add(new WalkNode(startKey, startFloor, 0.0,
                Math.abs(spec.startX() - spec.endX()) + Math.abs(spec.startZ() - spec.endZ())));

        while (!pq.isEmpty()) {
            WalkNode cur = pq.poll();
            if (cur.g > bestG.getOrDefault(cur.col, Double.POSITIVE_INFINITY) + 1e-9) continue;
            if (cur.col == endKey) {
                List<long[]> path = new ArrayList<>();
                for (Long k = endKey; k != null; k = parent.get(k)) {
                    path.add(new long[]{k, floorMap.getOrDefault(k, deckY)});
                }
                Collections.reverse(path);
                return path;
            }
            int cx = ProtectedZone.colX(cur.col);
            int cz = ProtectedZone.colZ(cur.col);
            for (int[] d : CARDINAL) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                if (spec.isProtectedColumn(nx, nz)) continue;
                int nf = surfaceFloorY(probe, nx, nz, deckY);
                if (nf == Integer.MIN_VALUE) continue;
                int dy = nf - cur.floorY;
                if (dy > 1 || dy < -2) continue; // ordinary player jump (+1) or safe drop (-2)

                CellKind cellAtFloor = probe.get(nx, nf, nz);
                double moveCost = (cellAtFloor == CellKind.FLUID) ? 2.4
                        : (dy > 0 ? 2.2 : dy < 0 ? 1.4 : 1.0);
                double ng = cur.g + moveCost;
                long nk = ProtectedZone.colKey(nx, nz);
                if (ng + 1e-9 < bestG.getOrDefault(nk, Double.POSITIVE_INFINITY)) {
                    bestG.put(nk, ng);
                    parent.put(nk, cur.col);
                    floorMap.put(nk, nf);
                    double h = Math.abs(nx - spec.endX()) + Math.abs(nz - spec.endZ());
                    pq.add(new WalkNode(nk, nf, ng, ng + h));
                }
            }
        }
        return null;
    }

    /** Finds the walkable/wadable surface Y near {@code deckY} for an ordinary jumping/wading player. */
    private static int surfaceFloorY(RoadProbe probe, int x, int z, int deckY) {
        // Check from highest hill down so ordinary walker walks over bumps instead of tunneling.
        for (int y = deckY + MAX_SURFACE_SCAN; y >= deckY - MAX_SURFACE_SCAN; y--) {
            CellKind k = probe.get(x, y, z);
            if (k == CellKind.HAZARD || k == CellKind.UNKNOWN) continue;
            if (k.isWalkableFullDeck() || k == CellKind.FLUID) {
                CellKind h1 = probe.get(x, y + 1, z);
                CellKind h2 = probe.get(x, y + 2, z);
                if (h1.isPassableHeadroom() && h2.isPassableHeadroom()) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private record WalkNode(long col, int floorY, double g, double f) {}
}
