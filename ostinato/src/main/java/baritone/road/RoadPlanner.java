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

import baritone.road.ConstraintFailure.Detail;
import baritone.road.ConstraintFailure.Kind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Terrain-aware 2-block-wide level road alignment planner.
 * <p>
 * Workflow:
 * <ol>
 *     <li>Validate endpoints &amp; spec (level deck elevation, width, budget).</li>
 *     <li>Survey only the bounded corridor around the endpoints ({@link CorridorSurvey}).</li>
 *     <li>Generate &amp; compare multiple candidate alignments across distinct cost profiles and lateral detours on the
 *         continuous {@code width x width} anchor graph (preventing 1-block pinch points and corner squeezes).</li>
 *     <li>Validate builder access ({@link BuilderAccessPlan}) on budget-feasible candidates so the bot never strands
 *         itself or violates a protected plot during construction.</li>
 *     <li>When infeasible, run targeted constraint-relaxation diagnostics to report the exact blocking constraint(s)
 *         before any world edit occurs.</li>
 * </ol>
 */
public final class RoadPlanner {

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private RoadPlanner() {}

    public record PlanResult(
            RoadSpec spec,
            CorridorSurvey survey,
            boolean feasible,
            RoadAlignment chosen,
            List<RoadAlignment> candidates,
            List<Detail> failures,
            long plannerTimeNanos
    ) {
        public double plannerTimeMs() {
            return plannerTimeNanos / 1_000_000.0;
        }

        public boolean hasFailure(Kind kind) {
            for (Detail d : failures) {
                if (d.kind() == kind) return true;
            }
            return false;
        }

        /** Human-readable preview + Bill of Materials (or exact infeasibility report). */
        public String formatPreview() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT,
                    "Road %s (%d,%d,%d) -> %s (%d,%d,%d) | width=%d deckY=%d headroom=%d budget=%d | surveyed=%d cols (%d stream, %d protected) in %.2f ms",
                    spec.startLabel(), spec.startX(), spec.startY(), spec.startZ(),
                    spec.endLabel(), spec.endX(), spec.endY(), spec.endZ(),
                    spec.width(), spec.deckY(), spec.headroom(), spec.maxPlacedBlocks(),
                    survey == null ? 0 : survey.surveyedColumns,
                    survey == null ? 0 : survey.streamColumns,
                    survey == null ? 0 : survey.protectedColumns,
                    plannerTimeMs()));
            if (!spec.protectedZones().isEmpty()) {
                sb.append("\n  Protected zones: ");
                for (int i = 0; i < spec.protectedZones().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(spec.protectedZones().get(i).describe());
                }
            }
            if (feasible && chosen != null) {
                int[] box = chosen.boundingBox();
                sb.append("\n  STATUS: FEASIBLE (").append(candidates.size()).append(" candidate(s) evaluated)");
                sb.append("\n  Chosen alignment: ").append(chosen.oneLineSummary(spec.maxPlacedBlocks()));
                sb.append(String.format(Locale.ROOT,
                        "\n  Footprint: %d columns, box=(%d,%d,%d..%d,%d,%d), protectedEdits=0",
                        chosen.footprintColumns().size(), box[0], box[1], box[2], box[3], box[4], box[5]));
                sb.append(String.format(Locale.ROOT,
                        "\n  Bill of Materials: %d x %s (budget %d, remaining %d), clear=%d blocks, builderStages=%d",
                        chosen.placedBlockCount(), spec.deckBlockId(), spec.maxPlacedBlocks(),
                        spec.maxPlacedBlocks() - chosen.placedBlockCount(),
                        chosen.clearedBlockCount(), chosen.accessPlan().stages().size()));
            } else {
                sb.append("\n  STATUS: INFEASIBLE (zero world edits performed)");
                for (Detail d : failures) {
                    sb.append("\n  BLOCKER ").append(d.kind()).append(": ").append(d.message());
                }
            }
            if (!candidates.isEmpty()) {
                sb.append("\n  Candidates compared:");
                int show = Math.min(5, candidates.size());
                for (int i = 0; i < show; i++) {
                    sb.append("\n    ").append(i + 1).append(". ").append(candidates.get(i).oneLineSummary(spec.maxPlacedBlocks()));
                }
            }
            return sb.toString();
        }
    }

    private record SearchWeights(
            String name,
            double placeWeight,
            double clearWeight,
            double stepWeight,
            double turnWeight,
            double lateralBias // >0 prefers left of start->end vector, <0 prefers right
    ) {}

    private static final List<SearchWeights> STRATEGIES = List.of(
            // Strongly minimizes placed blocks to satisfy tight crossing budgets (global optimum for placed blocks).
            new SearchWeights("min-material", 1000.0, 2.0, 1.0, 0.5, 0.0),
            // Balanced material, clearing, and distance.
            new SearchWeights("balanced", 24.0, 6.0, 1.5, 1.0, 0.0),
            // Minimizes total world edits (placements + clearings).
            new SearchWeights("min-edits", 20.0, 20.0, 1.0, 0.8, 0.0),
            // Shortest direct corridor alignment.
            new SearchWeights("direct-short", 2.0, 2.0, 5.0, 1.5, 0.0),
            // Lateral detours around central plots or wide stream bends.
            new SearchWeights("detour-left", 120.0, 4.0, 1.0, 0.5, 0.35),
            new SearchWeights("detour-right", 120.0, 4.0, 1.0, 0.5, -0.35)
    );

    public static PlanResult plan(RoadSpec spec, RoadProbe probe) {
        long t0 = System.nanoTime();

        // 1. Level endpoint pre-check (slice 1: level full-block deck, no stairs/slabs/graded paths).
        if (!spec.isLevelEndpoints()) {
            Detail d = new Detail(Kind.ELEVATION_MISMATCH,
                    String.format(Locale.ROOT, "Start Y=%d and end Y=%d differ by %d blocks; level no-jump deck requires matching endpoint elevation",
                            spec.startY(), spec.endY(), Math.abs(spec.endY() - spec.startY())),
                    Math.abs(spec.endY() - spec.startY()), 0);
            return new PlanResult(spec, null, false, null, List.of(), List.of(d), System.nanoTime() - t0);
        }

        // 2. Bounded corridor survey.
        CorridorSurvey survey = CorridorSurvey.survey(spec, probe);

        // 3. Collect valid endpoint anchors under full constraints.
        List<Long> startAnchors = validEndpointAnchors(spec, survey, spec.startX(), spec.startZ(), false, false, false);
        List<Long> endAnchors = validEndpointAnchors(spec, survey, spec.endX(), spec.endZ(), false, false, false);

        // 4. Generate and compare candidate alignments across all strategies.
        Map<String, RoadAlignment> uniqueByFootprint = new LinkedHashMap<>();
        if (!startAnchors.isEmpty() && !endAnchors.isEmpty()) {
            for (SearchWeights w : STRATEGIES) {
                List<Long> anchorPath = searchAnchors(spec, survey, startAnchors, endAnchors, w, false, false, false);
                if (anchorPath == null) {
                    continue;
                }
                RoadAlignment candidate = evaluateCandidate(spec, survey, w.name(), anchorPath, true);
                String fpKey = footprintSignature(candidate.footprintColumns());
                RoadAlignment existing = uniqueByFootprint.get(fpKey);
                if (existing == null || compareAlignments(candidate, existing) < 0) {
                    uniqueByFootprint.put(fpKey, candidate);
                }
            }
        }

        List<RoadAlignment> candidates = new ArrayList<>(uniqueByFootprint.values());
        candidates.sort(RoadPlanner::compareAlignments);

        RoadAlignment chosen = null;
        for (RoadAlignment c : candidates) {
            if (c.feasible()) {
                chosen = c;
                break;
            }
        }

        if (chosen != null) {
            return new PlanResult(spec, survey, true, chosen, candidates, List.of(), System.nanoTime() - t0);
        }

        // 5. Infeasible -> diagnose exact blocking constraint(s) via targeted relaxations (zero world edits).
        List<Detail> failures = diagnoseInfeasibility(spec, survey, candidates);
        return new PlanResult(spec, survey, false, null, candidates, failures, System.nanoTime() - t0);
    }

    /** Evaluates exact footprint, placements, clearings, budget, protected zones, and builder access for an anchor path. */
    static RoadAlignment evaluateCandidate(
            RoadSpec spec,
            CorridorSurvey survey,
            String strategyName,
            List<Long> anchorPath,
            boolean checkBuilderAccess) {
        int w = spec.width();
        int deckY = survey.deckY;
        int headroom = survey.headroom;

        LinkedHashSet<Long> footprint = new LinkedHashSet<>();
        for (long anchor : anchorPath) {
            int ax = ProtectedZone.colX(anchor);
            int az = ProtectedZone.colZ(anchor);
            for (int dx = 0; dx < w; dx++) {
                for (int dz = 0; dz < w; dz++) {
                    footprint.add(ProtectedZone.colKey(ax + dx, az + dz));
                }
            }
        }

        List<Long> footprintList = new ArrayList<>(footprint);
        List<Long> placements = new ArrayList<>();
        List<Long> clearCells = new ArrayList<>();
        int streamCols = 0;
        int protectedHits = 0;
        int clearanceHits = 0;
        int unloadedHits = 0;
        int impassableHits = 0;

        for (long col : footprintList) {
            int cx = ProtectedZone.colX(col);
            int cz = ProtectedZone.colZ(col);
            if (survey.isProtected(cx, cz)) protectedHits++;
            if (survey.isUnloaded(cx, cz)) unloadedHits++;
            if (survey.isClearanceBlocked(cx, cz)) clearanceHits++;
            if (survey.isDeckImpassable(cx, cz)) impassableHits++;
            if (survey.isStream(cx, cz)) streamCols++;
            if (!survey.hasSolidDeck(cx, cz)) {
                placements.add(ProtectedZone.key(cx, deckY, cz));
            }
            int mask = survey.clearMask(cx, cz);
            for (int dy = 0; dy <= headroom; dy++) {
                if ((mask & (1 << dy)) != 0) {
                    clearCells.add(ProtectedZone.key(cx, deckY + dy, cz));
                }
            }
        }

        int turns = countTurns(anchorPath);
        int steps = Math.max(0, anchorPath.size() - 1);

        List<Detail> violations = new ArrayList<>();
        if (unloadedHits > 0) {
            violations.add(new Detail(Kind.UNLOADED_TERRAIN,
                    unloadedHits + " footprint column(s) are unloaded", unloadedHits, 0));
        }
        if (protectedHits > 0) {
            violations.add(new Detail(Kind.PROTECTED_ZONE,
                    protectedHits + " footprint column(s) intersect a protected plot", protectedHits, 0));
        }
        if (clearanceHits > 0) {
            violations.add(new Detail(Kind.CLEARANCE_BLOCKED,
                    clearanceHits + " footprint column(s) have unbreakable/impassable headroom obstructions", clearanceHits, 0));
        }
        if (impassableHits > 0) {
            violations.add(new Detail(Kind.TERRAIN_IMPASSABLE,
                    impassableHits + " footprint column(s) have impassable hazard deck cells", impassableHits, 0));
        }
        if (placements.size() > spec.maxPlacedBlocks()) {
            violations.add(new Detail(Kind.BUDGET_EXCEEDED,
                    String.format(Locale.ROOT, "Alignment requires %d placed blocks, exceeding budget of %d",
                            placements.size(), spec.maxPlacedBlocks()),
                    placements.size(), spec.maxPlacedBlocks()));
        }

        BuilderAccessPlan accessPlan = null;
        if (checkBuilderAccess && violations.isEmpty()) {
            accessPlan = BuilderAccessPlan.plan(spec, survey, footprintList);
            if (!accessPlan.feasible()) {
                violations.add(new Detail(Kind.BUILDER_ACCESS_BLOCKED, accessPlan.refusalReason()));
            }
        }

        boolean feasible = violations.isEmpty();
        // Ranking score: primary = placed blocks (budget efficiency), secondary = clearings, tertiary = steps + turns.
        double score = placements.size() * 100.0 + clearCells.size() * 6.0 + steps * 1.0 + turns * 0.5;
        return new RoadAlignment(
                strategyName, deckY, w, headroom,
                anchorPath, footprintList, placements, clearCells,
                placements.size(), clearCells.size(), streamCols, steps, turns,
                feasible, violations, accessPlan, score);
    }

    private static List<Detail> diagnoseInfeasibility(
            RoadSpec spec,
            CorridorSurvey survey,
            List<RoadAlignment> constrainedCandidates) {
        List<Detail> details = new ArrayList<>();
        Set<Kind> seen = new HashSet<>();

        // Check if we already found geometrically valid candidates that only failed BUDGET or BUILDER_ACCESS.
        RoadAlignment minPlacedCandidate = null;
        RoadAlignment accessBlockedCandidate = null;
        for (RoadAlignment c : constrainedCandidates) {
            boolean onlyBudget = !c.violations().isEmpty()
                    && c.violations().stream().allMatch(v -> v.kind() == Kind.BUDGET_EXCEEDED);
            if (onlyBudget && (minPlacedCandidate == null || c.placedBlockCount() < minPlacedCandidate.placedBlockCount())) {
                minPlacedCandidate = c;
            }
            boolean onlyAccess = !c.violations().isEmpty()
                    && c.violations().stream().allMatch(v -> v.kind() == Kind.BUILDER_ACCESS_BLOCKED);
            if (onlyAccess && accessBlockedCandidate == null) {
                accessBlockedCandidate = c;
            }
        }

        if (accessBlockedCandidate != null) {
            for (Detail v : accessBlockedCandidate.violations()) {
                if (seen.add(v.kind())) details.add(v);
            }
            return details;
        }

        if (minPlacedCandidate != null) {
            Detail budgetDetail = new Detail(Kind.BUDGET_EXCEEDED,
                    String.format(Locale.ROOT, "Minimum feasible crossing respecting protected zones requires %d placed blocks, exceeding budget of %d",
                            minPlacedCandidate.placedBlockCount(), spec.maxPlacedBlocks()),
                    minPlacedCandidate.placedBlockCount(), spec.maxPlacedBlocks());
            details.add(budgetDetail);
            seen.add(Kind.BUDGET_EXCEEDED);

            // Check whether protected zones specifically forced the over-budget detour.
            if (!spec.protectedZones().isEmpty()) {
                RoadAlignment noProt = findMinMaterialRelaxed(spec, survey, true, false, false);
                if (noProt != null && noProt.placedBlockCount() <= spec.maxPlacedBlocks()) {
                    details.add(new Detail(Kind.PROTECTED_ZONE,
                            String.format(Locale.ROOT, "Protected plot blocks shorter %d-block crossing (detour requires %d > %d)",
                                    noProt.placedBlockCount(), minPlacedCandidate.placedBlockCount(), spec.maxPlacedBlocks()),
                            minPlacedCandidate.placedBlockCount(), spec.maxPlacedBlocks()));
                    seen.add(Kind.PROTECTED_ZONE);
                }
            }
            return details;
        }

        // No constrained path existed at all: run single-constraint relaxation passes to pinpoint exact blocker(s).
        if (!spec.protectedZones().isEmpty()) {
            RoadAlignment noProt = findMinMaterialRelaxed(spec, survey, true, false, false);
            if (noProt != null) {
                details.add(new Detail(Kind.PROTECTED_ZONE,
                        String.format(Locale.ROOT, "Protected plot blocks all 2-wide level alignments within the corridor (unblocked path needs %d placed blocks)",
                                noProt.placedBlockCount()),
                        noProt.placedBlockCount(), spec.maxPlacedBlocks()));
                seen.add(Kind.PROTECTED_ZONE);
                if (noProt.placedBlockCount() > spec.maxPlacedBlocks() && seen.add(Kind.BUDGET_EXCEEDED)) {
                    details.add(new Detail(Kind.BUDGET_EXCEEDED,
                            String.format(Locale.ROOT, "Even without protected plots, minimum crossing needs %d placed blocks > budget %d",
                                    noProt.placedBlockCount(), spec.maxPlacedBlocks()),
                            noProt.placedBlockCount(), spec.maxPlacedBlocks()));
                }
            }
        }

        RoadAlignment noClear = findMinMaterialRelaxed(spec, survey, false, true, false);
        if (noClear != null && seen.add(Kind.CLEARANCE_BLOCKED)) {
            details.add(new Detail(Kind.CLEARANCE_BLOCKED,
                    "Headroom clearance (" + spec.headroom() + " blocks above deckY=" + survey.deckY
                            + ") is blocked by unbreakable or fluid obstructions across the corridor"));
        }

        if (survey.unloadedColumns > 0) {
            RoadAlignment noUnloaded = findMinMaterialRelaxed(spec, survey, false, false, true);
            if (noUnloaded != null && seen.add(Kind.UNLOADED_TERRAIN)) {
                details.add(new Detail(Kind.UNLOADED_TERRAIN,
                        survey.unloadedColumns + " unloaded corridor column(s) prevent complete survey between endpoints",
                        survey.unloadedColumns, 0));
            }
        }

        if (details.isEmpty()) {
            // Check if relaxing both protected zones and clearance unlocks a route.
            RoadAlignment both = findMinMaterialRelaxed(spec, survey, true, true, false);
            if (both != null) {
                if (!spec.protectedZones().isEmpty() && seen.add(Kind.PROTECTED_ZONE)) {
                    details.add(new Detail(Kind.PROTECTED_ZONE, "Protected plot combined with overhead obstacles blocks all corridor alignments"));
                }
                if (seen.add(Kind.CLEARANCE_BLOCKED)) {
                    details.add(new Detail(Kind.CLEARANCE_BLOCKED, "Overhead clearance obstructions combined with protected plot block all corridor alignments"));
                }
            } else {
                details.add(new Detail(Kind.TERRAIN_IMPASSABLE,
                        "No 2-wide level alignment connects " + spec.startLabel() + " and " + spec.endLabel()
                                + " within bounded corridor margin " + spec.corridorMargin()));
            }
        }

        return details;
    }

    private static RoadAlignment findMinMaterialRelaxed(
            RoadSpec spec,
            CorridorSurvey survey,
            boolean ignoreProtected,
            boolean ignoreClearance,
            boolean ignoreUnloaded) {
        List<Long> starts = validEndpointAnchors(spec, survey, spec.startX(), spec.startZ(),
                ignoreProtected, ignoreClearance, ignoreUnloaded);
        List<Long> ends = validEndpointAnchors(spec, survey, spec.endX(), spec.endZ(),
                ignoreProtected, ignoreClearance, ignoreUnloaded);
        if (starts.isEmpty() || ends.isEmpty()) {
            return null;
        }
        List<Long> path = searchAnchors(spec, survey, starts, ends, STRATEGIES.get(0),
                ignoreProtected, ignoreClearance, ignoreUnloaded);
        if (path == null) {
            return null;
        }
        return evaluateCandidate(spec, survey, "diag", path, false);
    }

    /** Returns all valid {@code width x width} anchor corners containing {@code (px, pz)}. */
    private static List<Long> validEndpointAnchors(
            RoadSpec spec,
            CorridorSurvey survey,
            int px, int pz,
            boolean ignoreProtected,
            boolean ignoreClearance,
            boolean ignoreUnloaded) {
        int w = spec.width();
        List<Long> out = new ArrayList<>();
        for (int ox = 0; ox < w; ox++) {
            for (int oz = 0; oz < w; oz++) {
                int ax = px - ox;
                int az = pz - oz;
                if (isAnchorUsable(survey, ax, az, w, ignoreProtected, ignoreClearance, ignoreUnloaded)) {
                    out.add(ProtectedZone.colKey(ax, az));
                }
            }
        }
        return out;
    }

    private static boolean isAnchorUsable(
            CorridorSurvey survey,
            int ax, int az, int w,
            boolean ignoreProtected,
            boolean ignoreClearance,
            boolean ignoreUnloaded) {
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < w; dz++) {
                if (!survey.isColumnUsable(ax + dx, az + dz, ignoreProtected, ignoreClearance, ignoreUnloaded)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * A* search over the {@code width x width} anchor grid inside the bounded corridor.
     * Each cardinal move enters exactly {@code width} new columns, giving accurate incremental placement/clearing cost.
     */
    private static List<Long> searchAnchors(
            RoadSpec spec,
            CorridorSurvey survey,
            List<Long> startAnchors,
            List<Long> endAnchors,
            SearchWeights weights,
            boolean ignoreProtected,
            boolean ignoreClearance,
            boolean ignoreUnloaded) {
        int w = spec.width();
        int aMinX = survey.minX;
        int aMaxX = survey.maxX - (w - 1);
        int aMinZ = survey.minZ;
        int aMaxZ = survey.maxZ - (w - 1);
        if (aMaxX < aMinX || aMaxZ < aMinZ) {
            return null;
        }
        int aSizeX = aMaxX - aMinX + 1;
        int aSizeZ = aMaxZ - aMinZ + 1;
        int totalNodes = aSizeX * aSizeZ;

        // Precompute anchor validity bitset for O(1) neighbor checks.
        boolean[] usable = new boolean[totalNodes];
        for (int ax = aMinX; ax <= aMaxX; ax++) {
            int ox = ax - aMinX;
            for (int az = aMinZ; az <= aMaxZ; az++) {
                usable[ox * aSizeZ + (az - aMinZ)] = isAnchorUsable(survey, ax, az, w,
                        ignoreProtected, ignoreClearance, ignoreUnloaded);
            }
        }

        // End anchor lookup & coordinates for heuristic.
        boolean[] isGoal = new boolean[totalNodes];
        int[] endXs = new int[endAnchors.size()];
        int[] endZs = new int[endAnchors.size()];
        for (int i = 0; i < endAnchors.size(); i++) {
            int ex = ProtectedZone.colX(endAnchors.get(i));
            int ez = ProtectedZone.colZ(endAnchors.get(i));
            endXs[i] = ex;
            endZs[i] = ez;
            int idx = (ex - aMinX) * aSizeZ + (ez - aMinZ);
            if (idx >= 0 && idx < totalNodes) {
                isGoal[idx] = true;
            }
        }

        // Axis normal for lateral detour bias (unit perpendicular to start->end vector).
        double axisDx = spec.endX() - spec.startX();
        double axisDz = spec.endZ() - spec.startZ();
        double axisLen = Math.hypot(axisDx, axisDz);
        double perpX = axisLen > 1e-6 ? -axisDz / axisLen : 0.0;
        double perpZ = axisLen > 1e-6 ? axisDx / axisLen : 0.0;

        double[] bestG = new double[totalNodes];
        Arrays.fill(bestG, Double.POSITIVE_INFINITY);
        int[] parent = new int[totalNodes];
        Arrays.fill(parent, -1);
        byte[] enterDir = new byte[totalNodes];
        Arrays.fill(enterDir, (byte) -1);

        // Min-heap of (nodeIndex, g, f).
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));

        for (long sa : startAnchors) {
            int sx = ProtectedZone.colX(sa);
            int sz = ProtectedZone.colZ(sa);
            int idx = (sx - aMinX) * aSizeZ + (sz - aMinZ);
            if (idx < 0 || idx >= totalNodes || !usable[idx]) continue;

            // Initial cost covers the starting width x width anchor footprint.
            double initPlace = 0, initClear = 0;
            for (int dx = 0; dx < w; dx++) {
                for (int dz = 0; dz < w; dz++) {
                    initPlace += survey.placedCost(sx + dx, sz + dz);
                    initClear += survey.clearCost(sx + dx, sz + dz);
                }
            }
            double g0 = initPlace * weights.placeWeight() + initClear * weights.clearWeight();
            bestG[idx] = g0;
            double h0 = heuristic(sx, sz, endXs, endZs, weights.stepWeight());
            open.add(new SearchNode(idx, g0, g0 + h0));
        }

        int goalNode = -1;
        while (!open.isEmpty()) {
            SearchNode cur = open.poll();
            if (cur.g > bestG[cur.idx] + 1e-9) {
                continue;
            }
            if (isGoal[cur.idx]) {
                goalNode = cur.idx;
                break;
            }
            int cx = aMinX + (cur.idx / aSizeZ);
            int cz = aMinZ + (cur.idx % aSizeZ);
            byte prevDir = enterDir[cur.idx];

            for (int d = 0; d < 4; d++) {
                int nx = cx + CARDINAL[d][0];
                int nz = cz + CARDINAL[d][1];
                if (nx < aMinX || nx > aMaxX || nz < aMinZ || nz > aMaxZ) {
                    continue;
                }
                int nIdx = (nx - aMinX) * aSizeZ + (nz - aMinZ);
                if (!usable[nIdx]) {
                    continue;
                }

                // Exact cost of the newly entered w columns along the leading edge.
                int addPlace = 0;
                int addClear = 0;
                if (d == 0) { // +X
                    int colX = cx + w;
                    for (int k = 0; k < w; k++) {
                        addPlace += survey.placedCost(colX, cz + k);
                        addClear += survey.clearCost(colX, cz + k);
                    }
                } else if (d == 1) { // -X
                    int colX = cx - 1;
                    for (int k = 0; k < w; k++) {
                        addPlace += survey.placedCost(colX, cz + k);
                        addClear += survey.clearCost(colX, cz + k);
                    }
                } else if (d == 2) { // +Z
                    int colZ = cz + w;
                    for (int k = 0; k < w; k++) {
                        addPlace += survey.placedCost(cx + k, colZ);
                        addClear += survey.clearCost(cx + k, colZ);
                    }
                } else { // -Z
                    int colZ = cz - 1;
                    for (int k = 0; k < w; k++) {
                        addPlace += survey.placedCost(cx + k, colZ);
                        addClear += survey.clearCost(cx + k, colZ);
                    }
                }

                double stepCost = weights.stepWeight()
                        + addPlace * weights.placeWeight()
                        + addClear * weights.clearWeight()
                        + (prevDir >= 0 && prevDir != d ? weights.turnWeight() : 0.0);

                if (weights.lateralBias() != 0.0) {
                    double signedOffset = (nx - spec.startX()) * perpX + (nz - spec.startZ()) * perpZ;
                    // Penalize the opposite side of the corridor gently so the detour strategy explores the preferred side.
                    if (weights.lateralBias() > 0 && signedOffset < 0) {
                        stepCost += (-signedOffset) * weights.lateralBias();
                    } else if (weights.lateralBias() < 0 && signedOffset > 0) {
                        stepCost += signedOffset * (-weights.lateralBias());
                    }
                }

                double ng = cur.g + stepCost;
                if (ng + 1e-9 < bestG[nIdx]) {
                    bestG[nIdx] = ng;
                    parent[nIdx] = cur.idx;
                    enterDir[nIdx] = (byte) d;
                    double nf = ng + heuristic(nx, nz, endXs, endZs, weights.stepWeight());
                    open.add(new SearchNode(nIdx, ng, nf));
                }
            }
        }

        if (goalNode < 0) {
            return null;
        }

        List<Long> path = new ArrayList<>();
        for (int cur = goalNode; cur >= 0; cur = parent[cur]) {
            int ax = aMinX + (cur / aSizeZ);
            int az = aMinZ + (cur % aSizeZ);
            path.add(ProtectedZone.colKey(ax, az));
        }
        Collections.reverse(path);
        return path;
    }

    private static double heuristic(int x, int z, int[] endXs, int[] endZs, double stepWeight) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < endXs.length; i++) {
            int d = Math.abs(x - endXs[i]) + Math.abs(z - endZs[i]);
            if (d < best) best = d;
        }
        return best * stepWeight;
    }

    private static int countTurns(List<Long> anchorPath) {
        if (anchorPath.size() < 3) return 0;
        int turns = 0;
        int prevDx = ProtectedZone.colX(anchorPath.get(1)) - ProtectedZone.colX(anchorPath.get(0));
        int prevDz = ProtectedZone.colZ(anchorPath.get(1)) - ProtectedZone.colZ(anchorPath.get(0));
        for (int i = 2; i < anchorPath.size(); i++) {
            int dx = ProtectedZone.colX(anchorPath.get(i)) - ProtectedZone.colX(anchorPath.get(i - 1));
            int dz = ProtectedZone.colZ(anchorPath.get(i)) - ProtectedZone.colZ(anchorPath.get(i - 1));
            if (dx != prevDx || dz != prevDz) {
                turns++;
                prevDx = dx;
                prevDz = dz;
            }
        }
        return turns;
    }

    private static String footprintSignature(List<Long> footprintColumns) {
        long[] sorted = new long[footprintColumns.size()];
        for (int i = 0; i < footprintColumns.size(); i++) {
            sorted[i] = footprintColumns.get(i);
        }
        Arrays.sort(sorted);
        return Arrays.toString(sorted);
    }

    private static int compareAlignments(RoadAlignment a, RoadAlignment b) {
        if (a.feasible() != b.feasible()) {
            return a.feasible() ? -1 : 1;
        }
        int c = Double.compare(a.score(), b.score());
        if (c != 0) return c;
        c = Integer.compare(a.placedBlockCount(), b.placedBlockCount());
        if (c != 0) return c;
        return Integer.compare(a.lengthSteps(), b.lengthSteps());
    }

    private record SearchNode(int idx, double g, double f) {}
}
