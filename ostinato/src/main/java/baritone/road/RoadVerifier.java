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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Physical post-build road verifier + independent second-traveler bidirectional no-jump traversal checker.
 * <p>
 * The second-traveler check is deliberately independent of {@link RoadPlanner}: it operates directly on raw voxel
 * queries ({@link RoadProbe}) with jumping, digging, and placing disabled, verifying continuous level footing at
 * {@code deckY} and standing headroom at {@code deckY + 1 .. deckY + headroom} in both directions
 * ({@code start -> end} and {@code end -> start}).
 */
public final class RoadVerifier {

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private RoadVerifier() {}

    public record TraversalResult(
            boolean forwardReachable,
            boolean reverseReachable,
            int forwardSteps,
            int reverseSteps,
            boolean twoWideContinuous
    ) {
        public boolean bidirectionalNoJumpPass() {
            return forwardReachable && reverseReachable;
        }
    }

    public record VerificationReport(
            boolean passed,
            int missingDeckBlocks,
            int blockedHeadroomCells,
            int protectedPlotEdits,
            int placedBlocksUsed,
            int budgetLimit,
            TraversalResult traversal,
            List<String> issues
    ) {
        public String summary() {
            return String.format(Locale.ROOT,
                    "Verify %s | deckMissing=%d headroomBlocked=%d protectedEdits=%d placed=%d/%d | noJump(fwd=%s/%d, rev=%s/%d, 2wide=%s)%s",
                    passed ? "PASS" : "FAIL",
                    missingDeckBlocks, blockedHeadroomCells, protectedPlotEdits,
                    placedBlocksUsed, budgetLimit,
                    traversal.forwardReachable(), traversal.forwardSteps(),
                    traversal.reverseReachable(), traversal.reverseSteps(),
                    traversal.twoWideContinuous(),
                    issues.isEmpty() ? "" : " issues=" + issues);
        }
    }

    /** Captures the pre-build state of all cells inside all protected zones within the bounded corridor. */
    public static Map<Long, CellKind> snapshotProtectedZones(RoadSpec spec, RoadProbe probe) {
        Map<Long, CellKind> snap = new HashMap<>();
        if (spec.protectedZones().isEmpty()) {
            return snap;
        }
        int minX = spec.minCorridorX();
        int maxX = spec.maxCorridorX();
        int minZ = spec.minCorridorZ();
        int maxZ = spec.maxCorridorZ();
        int yLo = spec.deckY() - 1;
        int yHi = spec.deckY() + spec.headroom() + 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!spec.isProtectedColumn(x, z)) continue;
                for (int y = yLo; y <= yHi; y++) {
                    if (spec.isProtected(x, y, z)) {
                        snap.put(ProtectedZone.key(x, y, z), probe.get(x, y, z));
                    }
                }
            }
        }
        return snap;
    }

    /** Audits physical deck, headroom clearance, protected-plot immutability, budget, and bidirectional no-jump traversal. */
    public static VerificationReport verify(
            RoadSpec spec,
            RoadAlignment alignment,
            RoadProbe probe,
            Map<Long, CellKind> preProtectedSnapshot,
            int actualPlacedBlocks) {
        List<String> issues = new ArrayList<>();
        int deckY = alignment.deckY();
        int headroom = alignment.headroom();
        int missingDeck = 0;
        int blockedHeadroom = 0;

        for (long col : alignment.footprintColumns()) {
            int cx = ProtectedZone.colX(col);
            int cz = ProtectedZone.colZ(col);
            if (!probe.get(cx, deckY, cz).isWalkableFullDeck()) {
                missingDeck++;
            }
            for (int dy = 1; dy <= headroom; dy++) {
                if (!probe.get(cx, deckY + dy, cz).isPassableHeadroom()) {
                    blockedHeadroom++;
                }
            }
        }
        if (missingDeck > 0) {
            issues.add(missingDeck + " footprint column(s) missing solid full deck block at y=" + deckY);
        }
        if (blockedHeadroom > 0) {
            issues.add(blockedHeadroom + " headroom cell(s) obstructed above deckY=" + deckY);
        }

        int protectedEdits = 0;
        if (preProtectedSnapshot != null) {
            for (Map.Entry<Long, CellKind> e : preProtectedSnapshot.entrySet()) {
                long k = e.getKey();
                CellKind now = probe.get(ProtectedZone.x(k), ProtectedZone.y(k), ProtectedZone.z(k));
                if (now != e.getValue()) {
                    protectedEdits++;
                }
            }
        }
        if (protectedEdits > 0) {
            issues.add(protectedEdits + " unauthorized edit(s) detected inside protected plot");
        }

        if (actualPlacedBlocks > spec.maxPlacedBlocks()) {
            issues.add("Placed blocks " + actualPlacedBlocks + " exceeded budget " + spec.maxPlacedBlocks());
        }

        TraversalResult traversal = walkIndependentNoJump(spec, probe);
        if (!traversal.forwardReachable()) {
            issues.add("Independent no-jump traveler failed forward walk " + spec.startLabel() + " -> " + spec.endLabel());
        }
        if (!traversal.reverseReachable()) {
            issues.add("Independent no-jump traveler failed reverse walk " + spec.endLabel() + " -> " + spec.startLabel());
        }
        if (!traversal.twoWideContinuous()) {
            issues.add("Completed road is not continuously " + spec.width() + " blocks wide on level deckY=" + deckY);
        }

        boolean passed = issues.isEmpty();
        return new VerificationReport(
                passed, missingDeck, blockedHeadroom, protectedEdits,
                actualPlacedBlocks, spec.maxPlacedBlocks(), traversal, issues);
    }

    /**
     * Independent second traveler: strictly level walking at {@code y = deckY + 1} with jumping, digging, and placing
     * completely disabled, never entering any {@link ProtectedZone}, and staying inside the bounded corridor.
     */
    public static TraversalResult walkIndependentNoJump(RoadSpec spec, RoadProbe probe) {
        if (!spec.isLevelEndpoints()) {
            return new TraversalResult(false, false, -1, -1, false);
        }
        int deckY = spec.deckY();
        int headroom = spec.headroom();
        int minX = spec.minCorridorX();
        int maxX = spec.maxCorridorX();
        int minZ = spec.minCorridorZ();
        int maxZ = spec.maxCorridorZ();

        int fwd = bfsLevelNoJump(spec, probe, spec.startX(), spec.startZ(), spec.endX(), spec.endZ(),
                deckY, headroom, minX, maxX, minZ, maxZ);
        int rev = bfsLevelNoJump(spec, probe, spec.endX(), spec.endZ(), spec.startX(), spec.startZ(),
                deckY, headroom, minX, maxX, minZ, maxZ);
        boolean twoWide = checkContinuousWidth(spec, probe, deckY, headroom, minX, maxX, minZ, maxZ);
        return new TraversalResult(fwd >= 0, rev >= 0, fwd, rev, twoWide);
    }

    private static boolean canStandNoJump(RoadSpec spec, RoadProbe probe, int x, int z, int deckY, int headroom) {
        if (spec.isProtectedColumn(x, z)) {
            return false;
        }
        if (!probe.get(x, deckY, z).isWalkableFullDeck()) {
            return false;
        }
        for (int dy = 1; dy <= headroom; dy++) {
            if (!probe.get(x, deckY + dy, z).isPassableHeadroom()) {
                return false;
            }
        }
        return true;
    }

    private static int bfsLevelNoJump(
            RoadSpec spec, RoadProbe probe,
            int sx, int sz, int ex, int ez,
            int deckY, int headroom,
            int minX, int maxX, int minZ, int maxZ) {
        if (!canStandNoJump(spec, probe, sx, sz, deckY, headroom)
                || !canStandNoJump(spec, probe, ex, ez, deckY, headroom)) {
            return -1;
        }
        if (sx == ex && sz == ez) {
            return 0;
        }
        Set<Long> visited = new HashSet<>();
        ArrayDeque<long[]> q = new ArrayDeque<>();
        long startKey = ProtectedZone.colKey(sx, sz);
        long endKey = ProtectedZone.colKey(ex, ez);
        visited.add(startKey);
        q.add(new long[]{startKey, 0});

        while (!q.isEmpty()) {
            long[] cur = q.poll();
            int cx = ProtectedZone.colX(cur[0]);
            int cz = ProtectedZone.colZ(cur[0]);
            int dist = (int) cur[1];
            for (int[] d : CARDINAL) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                long nk = ProtectedZone.colKey(nx, nz);
                if (visited.contains(nk)) continue;
                if (!canStandNoJump(spec, probe, nx, nz, deckY, headroom)) continue;
                if (nk == endKey) {
                    return dist + 1;
                }
                visited.add(nk);
                q.add(new long[]{nk, dist + 1});
            }
        }
        return -1;
    }

    /** Verifies a continuous {@code width x width} level no-jump corridor exists from start to end on the live world. */
    private static boolean checkContinuousWidth(
            RoadSpec spec, RoadProbe probe,
            int deckY, int headroom,
            int minX, int maxX, int minZ, int maxZ) {
        int w = spec.width();
        Set<Long> starts = new HashSet<>();
        Set<Long> ends = new HashSet<>();
        for (int ox = 0; ox < w; ox++) {
            for (int oz = 0; oz < w; oz++) {
                int sax = spec.startX() - ox, saz = spec.startZ() - oz;
                if (anchorWalkable(spec, probe, sax, saz, w, deckY, headroom, minX, maxX, minZ, maxZ)) {
                    starts.add(ProtectedZone.colKey(sax, saz));
                }
                int eax = spec.endX() - ox, eaz = spec.endZ() - oz;
                if (anchorWalkable(spec, probe, eax, eaz, w, deckY, headroom, minX, maxX, minZ, maxZ)) {
                    ends.add(ProtectedZone.colKey(eax, eaz));
                }
            }
        }
        if (starts.isEmpty() || ends.isEmpty()) {
            return false;
        }
        Set<Long> visited = new HashSet<>(starts);
        ArrayDeque<Long> q = new ArrayDeque<>(starts);
        while (!q.isEmpty()) {
            long cur = q.poll();
            if (ends.contains(cur)) {
                return true;
            }
            int ax = ProtectedZone.colX(cur);
            int az = ProtectedZone.colZ(cur);
            for (int[] d : CARDINAL) {
                int nx = ax + d[0];
                int nz = az + d[1];
                long nk = ProtectedZone.colKey(nx, nz);
                if (visited.contains(nk)) continue;
                if (anchorWalkable(spec, probe, nx, nz, w, deckY, headroom, minX, maxX, minZ, maxZ)) {
                    visited.add(nk);
                    q.add(nk);
                }
            }
        }
        return false;
    }

    private static boolean anchorWalkable(
            RoadSpec spec, RoadProbe probe,
            int ax, int az, int w, int deckY, int headroom,
            int minX, int maxX, int minZ, int maxZ) {
        if (ax < minX || ax + w - 1 > maxX || az < minZ || az + w - 1 > maxZ) return false;
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < w; dz++) {
                if (!canStandNoJump(spec, probe, ax + dx, az + dz, deckY, headroom)) {
                    return false;
                }
            }
        }
        return true;
    }
}
