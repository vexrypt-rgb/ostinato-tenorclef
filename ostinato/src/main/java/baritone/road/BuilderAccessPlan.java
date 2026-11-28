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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Plans an ordered, wavefront construction sequence for a candidate road alignment so an ordinary player/bot can clear
 * headroom obstacles and place the level full-block deck without stranding itself or entering/editing any protected plot.
 * <p>
 * Uses flat corridor-indexed primitive arrays for zero-boxing reachability checks so planning completes in milliseconds.
 */
public final class BuilderAccessPlan {

    /** Conservative ordinary player reach squared in XZ (3 blocks horizontally -> safely within 4.5-block reach). */
    public static final int SAFE_STAND_DIST_SQR = 9;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public enum OpKind {
        CLEAR,
        PLACE
    }

    public record BuildOp(
            OpKind kind,
            int x, int y, int z,
            int standX, int standY, int standZ,
            int supportX, int supportY, int supportZ
    ) {
        public long cellKey() {
            return ProtectedZone.key(x, y, z);
        }
    }

    private final boolean feasible;
    private final List<BuildOp> operations;
    private final List<List<BuildOp>> stages;
    private final String refusalReason;

    private BuilderAccessPlan(boolean feasible, List<BuildOp> operations, List<List<BuildOp>> stages, String refusalReason) {
        this.feasible = feasible;
        this.operations = List.copyOf(operations);
        this.stages = List.copyOf(stages);
        this.refusalReason = refusalReason;
    }

    public boolean feasible() {
        return feasible;
    }

    public List<BuildOp> operations() {
        return operations;
    }

    /** Wavefront batches suitable for staged execution through {@code BuilderProcess}. */
    public List<List<BuildOp>> stages() {
        return stages;
    }

    public String refusalReason() {
        return refusalReason;
    }

    public int placeOpCount() {
        int n = 0;
        for (BuildOp op : operations) {
            if (op.kind() == OpKind.PLACE) n++;
        }
        return n;
    }

    public int clearOpCount() {
        int n = 0;
        for (BuildOp op : operations) {
            if (op.kind() == OpKind.CLEAR) n++;
        }
        return n;
    }

    /**
     * Plans a safe non-stranding construction order from {@code start} across the footprint columns of an alignment.
     */
    public static BuilderAccessPlan plan(RoadSpec spec, CorridorSurvey survey, List<Long> orderedFootprintCols) {
        int deckY = survey.deckY;
        int headroom = survey.headroom;
        int minX = survey.minX, minZ = survey.minZ;
        int sizeX = survey.sizeX, sizeZ = survey.sizeZ;
        int total = sizeX * sizeZ;

        // Defense-in-depth: never allow any footprint cell inside a protected zone.
        for (long col : orderedFootprintCols) {
            int cx = ProtectedZone.colX(col);
            int cz = ProtectedZone.colZ(col);
            if (survey.isProtected(cx, cz)) {
                return new BuilderAccessPlan(false, List.of(), List.of(),
                        "Footprint column (" + cx + "," + cz + ") intersects protected zone");
            }
        }

        Map<Long, Integer> progressOrder = new HashMap<>(orderedFootprintCols.size() * 2);
        for (int i = 0; i < orderedFootprintCols.size(); i++) {
            progressOrder.putIfAbsent(orderedFootprintCols.get(i), i);
        }

        // Flat primitive simulation arrays over the bounded corridor.
        boolean[] allowedCol = new boolean[total];
        boolean[] solidDeck = new boolean[total];
        byte[] pendingClear = new byte[total];
        for (int x = survey.minX; x <= survey.maxX; x++) {
            int ox = x - minX;
            for (int z = survey.minZ; z <= survey.maxZ; z++) {
                int idx = ox * sizeZ + (z - minZ);
                boolean ok = !survey.isProtected(x, z) && !survey.isUnloaded(x, z) && !survey.isClearanceBlocked(x, z);
                allowedCol[idx] = ok;
                if (ok) {
                    solidDeck[idx] = survey.hasSolidDeck(x, z);
                    pendingClear[idx] = (byte) survey.clearMask(x, z);
                }
            }
        }

        LinkedHashSet<Long> remainingPlaces = new LinkedHashSet<>();
        LinkedHashMap<Long, List<Integer>> remainingClearsByCol = new LinkedHashMap<>();
        int totalClearOps = 0;
        for (long col : orderedFootprintCols) {
            int cx = ProtectedZone.colX(col);
            int cz = ProtectedZone.colZ(col);
            int mask = survey.clearMask(cx, cz);
            if (mask != 0) {
                List<Integer> ys = new ArrayList<>();
                for (int dy = headroom; dy >= 0; dy--) {
                    if ((mask & (1 << dy)) != 0) {
                        ys.add(deckY + dy);
                    }
                }
                if (!ys.isEmpty()) {
                    remainingClearsByCol.put(col, ys);
                    totalClearOps += ys.size();
                }
            }
            if (!survey.hasSolidDeck(cx, cz)) {
                remainingPlaces.add(col);
            }
        }

        int[] startSeeds = findInitialStandSeeds(spec, survey, orderedFootprintCols, allowedCol, solidDeck, pendingClear);
        if (startSeeds.length == 0) {
            return new BuilderAccessPlan(false, List.of(), List.of(),
                    "No safe non-protected walkable starting stand near " + spec.startLabel());
        }

        int[] visitStamp = new int[total];
        int[] bfsQueue = new int[total];
        int stamp = 1;
        List<BuildOp> ops = new ArrayList<>(totalClearOps + remainingPlaces.size());
        int maxIterations = (totalClearOps + remainingPlaces.size()) * 2 + 16;

        // Track reachable component incrementally; only recompute BFS when a column newly becomes walkable.
        boolean dirtyReachable = true;

        for (int iter = 0; iter < maxIterations; iter++) {
            if (remainingClearsByCol.isEmpty() && remainingPlaces.isEmpty()) {
                break;
            }
            if (dirtyReachable) {
                stamp++;
                runBfs(minX, minZ, sizeX, sizeZ, startSeeds, allowedCol, solidDeck, pendingClear, visitStamp, stamp, bfsQueue);
                dirtyReachable = false;
            }

            // 1. Prefer any reachable clear op on the road footprint.
            BuildOp nextOp = pickNextClearOp(minX, minZ, sizeX, sizeZ, deckY, visitStamp, stamp, remainingClearsByCol, progressOrder);
            if (nextOp != null) {
                ops.add(nextOp);
                long col = ProtectedZone.colKey(nextOp.x(), nextOp.z());
                List<Integer> ys = remainingClearsByCol.get(col);
                ys.remove(Integer.valueOf(nextOp.y()));
                if (ys.isEmpty()) {
                    remainingClearsByCol.remove(col);
                }
                int idx = (nextOp.x() - minX) * sizeZ + (nextOp.z() - minZ);
                int dy = nextOp.y() - deckY;
                pendingClear[idx] = (byte) ((pendingClear[idx] & 0xFF) & ~(1 << dy));
                if (solidDeck[idx] && ((pendingClear[idx] & 0xFF) & ~1) == 0) {
                    dirtyReachable = true;
                }
                continue;
            }

            // 2. Otherwise pick the next reachable deck placement with an existing solid support face.
            nextOp = pickNextPlaceOp(survey, minX, minZ, sizeX, sizeZ, deckY, visitStamp, stamp,
                    solidDeck, pendingClear, remainingPlaces, progressOrder);
            if (nextOp != null) {
                ops.add(nextOp);
                long col = ProtectedZone.colKey(nextOp.x(), nextOp.z());
                remainingPlaces.remove(col);
                int idx = (nextOp.x() - minX) * sizeZ + (nextOp.z() - minZ);
                solidDeck[idx] = true;
                if (((pendingClear[idx] & 0xFF) & ~1) == 0) {
                    dirtyReachable = true;
                }
                continue;
            }

            return new BuilderAccessPlan(false, List.of(), List.of(),
                    "Builder access blocked: " + remainingPlaces.size() + " deck placements and "
                            + remainingClearsByCol.size() + " clear columns unreachable without stranding or entering protected zone");
        }

        if (!remainingClearsByCol.isEmpty() || !remainingPlaces.isEmpty()) {
            return new BuilderAccessPlan(false, List.of(), List.of(),
                    "Builder access planner exceeded iteration bound with unfinished work");
        }

        if (dirtyReachable) {
            stamp++;
            runBfs(minX, minZ, sizeX, sizeZ, startSeeds, allowedCol, solidDeck, pendingClear, visitStamp, stamp, bfsQueue);
        }
        int endIdx = (spec.endX() - minX) * sizeZ + (spec.endZ() - minZ);
        if (endIdx < 0 || endIdx >= total || visitStamp[endIdx] != stamp) {
            return new BuilderAccessPlan(false, List.of(), List.of(),
                    "Post-construction walkable check cannot reach destination " + spec.endLabel() + " from " + spec.startLabel());
        }

        List<List<BuildOp>> stages = groupIntoStages(ops, 4);
        return new BuilderAccessPlan(true, ops, stages, "");
    }

    private static boolean isWalkableIdx(int idx, boolean[] allowedCol, boolean[] solidDeck, byte[] pendingClear) {
        return allowedCol[idx] && solidDeck[idx] && ((pendingClear[idx] & 0xFF) & ~1) == 0;
    }

    private static int[] findInitialStandSeeds(
            RoadSpec spec,
            CorridorSurvey survey,
            List<Long> footprintCols,
            boolean[] allowedCol,
            boolean[] solidDeck,
            byte[] pendingClear) {
        int minX = survey.minX, minZ = survey.minZ, sizeZ = survey.sizeZ;
        List<Integer> seeds = new ArrayList<>();
        if (survey.inBounds(spec.startX(), spec.startZ())) {
            int sIdx = (spec.startX() - minX) * sizeZ + (spec.startZ() - minZ);
            if (isWalkableIdx(sIdx, allowedCol, solidDeck, pendingClear)) {
                seeds.add(sIdx);
            }
        }
        if (seeds.isEmpty()) {
            for (int r = 1; r <= 2 && seeds.isEmpty(); r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int x = spec.startX() + dx;
                        int z = spec.startZ() + dz;
                        if (survey.inBounds(x, z)) {
                            int idx = (x - minX) * sizeZ + (z - minZ);
                            if (isWalkableIdx(idx, allowedCol, solidDeck, pendingClear)) {
                                seeds.add(idx);
                            }
                        }
                    }
                }
            }
        }
        if (seeds.isEmpty()) {
            for (long col : footprintCols) {
                int x = ProtectedZone.colX(col);
                int z = ProtectedZone.colZ(col);
                if (Math.abs(x - spec.startX()) <= 2 && Math.abs(z - spec.startZ()) <= 2 && survey.inBounds(x, z)) {
                    int idx = (x - minX) * sizeZ + (z - minZ);
                    if (isWalkableIdx(idx, allowedCol, solidDeck, pendingClear)) {
                        seeds.add(idx);
                    }
                }
            }
        }
        int[] out = new int[seeds.size()];
        for (int i = 0; i < seeds.size(); i++) out[i] = seeds.get(i);
        return out;
    }

    private static void runBfs(
            int minX, int minZ, int sizeX, int sizeZ,
            int[] seeds,
            boolean[] allowedCol,
            boolean[] solidDeck,
            byte[] pendingClear,
            int[] visitStamp,
            int stamp,
            int[] queue) {
        int head = 0, tail = 0;
        for (int seed : seeds) {
            if (visitStamp[seed] != stamp) {
                visitStamp[seed] = stamp;
                queue[tail++] = seed;
            }
        }
        while (head < tail) {
            int cur = queue[head++];
            int ox = cur / sizeZ;
            int oz = cur % sizeZ;
            if (ox + 1 < sizeX) {
                int n = cur + sizeZ;
                if (visitStamp[n] != stamp && isWalkableIdx(n, allowedCol, solidDeck, pendingClear)) {
                    visitStamp[n] = stamp;
                    queue[tail++] = n;
                }
            }
            if (ox > 0) {
                int n = cur - sizeZ;
                if (visitStamp[n] != stamp && isWalkableIdx(n, allowedCol, solidDeck, pendingClear)) {
                    visitStamp[n] = stamp;
                    queue[tail++] = n;
                }
            }
            if (oz + 1 < sizeZ) {
                int n = cur + 1;
                if (visitStamp[n] != stamp && isWalkableIdx(n, allowedCol, solidDeck, pendingClear)) {
                    visitStamp[n] = stamp;
                    queue[tail++] = n;
                }
            }
            if (oz > 0) {
                int n = cur - 1;
                if (visitStamp[n] != stamp && isWalkableIdx(n, allowedCol, solidDeck, pendingClear)) {
                    visitStamp[n] = stamp;
                    queue[tail++] = n;
                }
            }
        }
    }

    private static BuildOp pickNextClearOp(
            int minX, int minZ, int sizeX, int sizeZ,
            int deckY,
            int[] visitStamp, int stamp,
            Map<Long, List<Integer>> remainingClearsByCol,
            Map<Long, Integer> progressOrder) {
        BuildOp best = null;
        int bestProgress = Integer.MAX_VALUE;
        int bestDist = Integer.MAX_VALUE;
        for (Map.Entry<Long, List<Integer>> entry : remainingClearsByCol.entrySet()) {
            long col = entry.getKey();
            int tx = ProtectedZone.colX(col);
            int tz = ProtectedZone.colZ(col);
            int ty = entry.getValue().get(0);
            boolean isDeckClear = (ty == deckY);
            int[] stand = findNearestStand(tx, tz, minX, minZ, sizeX, sizeZ, visitStamp, stamp, isDeckClear);
            if (stand == null) continue;
            int prog = progressOrder.getOrDefault(col, Integer.MAX_VALUE);
            int dist = (stand[0] - tx) * (stand[0] - tx) + (stand[1] - tz) * (stand[1] - tz);
            if (prog < bestProgress || (prog == bestProgress && dist < bestDist)) {
                bestProgress = prog;
                bestDist = dist;
                best = new BuildOp(OpKind.CLEAR, tx, ty, tz, stand[0], deckY + 1, stand[1], tx, ty, tz);
            }
        }
        return best;
    }

    private static BuildOp pickNextPlaceOp(
            CorridorSurvey survey,
            int minX, int minZ, int sizeX, int sizeZ,
            int deckY,
            int[] visitStamp, int stamp,
            boolean[] solidDeck,
            byte[] pendingClear,
            LinkedHashSet<Long> remainingPlaces,
            Map<Long, Integer> progressOrder) {
        BuildOp best = null;
        int bestProgress = Integer.MAX_VALUE;
        int bestSupportScore = Integer.MAX_VALUE;
        for (long col : remainingPlaces) {
            int tx = ProtectedZone.colX(col);
            int tz = ProtectedZone.colZ(col);
            int idx = (tx - minX) * sizeZ + (tz - minZ);
            if (((pendingClear[idx] & 0xFF) & 1) != 0) {
                continue;
            }
            int[] support = findSupportFace(survey, tx, deckY, tz, minX, minZ, sizeX, sizeZ, solidDeck);
            if (support == null) {
                continue;
            }
            int[] stand = findNearestStand(tx, tz, minX, minZ, sizeX, sizeZ, visitStamp, stamp, true);
            if (stand == null) {
                continue;
            }
            int prog = progressOrder.getOrDefault(col, Integer.MAX_VALUE);
            int standDist = (stand[0] - tx) * (stand[0] - tx) + (stand[1] - tz) * (stand[1] - tz);
            if (prog < bestProgress || (prog == bestProgress && standDist < bestSupportScore)) {
                bestProgress = prog;
                bestSupportScore = standDist;
                best = new BuildOp(OpKind.PLACE, tx, deckY, tz,
                        stand[0], deckY + 1, stand[1],
                        support[0], support[1], support[2]);
            }
        }
        return best;
    }

    private static int[] findSupportFace(
            CorridorSurvey survey,
            int x, int deckY, int z,
            int minX, int minZ, int sizeX, int sizeZ,
            boolean[] solidDeck) {
        for (int[] d : CARDINAL) {
            int ox = (x + d[0]) - minX;
            int oz = (z + d[1]) - minZ;
            if (ox >= 0 && ox < sizeX && oz >= 0 && oz < sizeZ && solidDeck[ox * sizeZ + oz]) {
                return new int[]{x + d[0], deckY, z + d[1]};
            }
        }
        if (survey.hasSolidBelow(x, z)) {
            return new int[]{x, deckY - 1, z};
        }
        return null;
    }

    private static int[] findNearestStand(
            int tx, int tz,
            int minX, int minZ, int sizeX, int sizeZ,
            int[] visitStamp, int stamp,
            boolean forbidSelf) {
        int bestX = 0, bestZ = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            int ox = (tx + dx) - minX;
            if (ox < 0 || ox >= sizeX) continue;
            for (int dz = -3; dz <= 3; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > SAFE_STAND_DIST_SQR || (forbidSelf && d2 == 0) || d2 >= bestDist) continue;
                int oz = (tz + dz) - minZ;
                if (oz < 0 || oz >= sizeZ) continue;
                if (visitStamp[ox * sizeZ + oz] == stamp) {
                    bestDist = d2;
                    bestX = tx + dx;
                    bestZ = tz + dz;
                }
            }
        }
        return bestDist == Integer.MAX_VALUE ? null : new int[]{bestX, bestZ};
    }

    private static List<List<BuildOp>> groupIntoStages(List<BuildOp> ops, int maxStageOps) {
        if (ops.isEmpty()) {
            return List.of();
        }
        List<List<BuildOp>> out = new ArrayList<>();
        List<BuildOp> current = new ArrayList<>();
        OpKind curKind = null;
        int anchorX = 0, anchorZ = 0;
        for (BuildOp op : ops) {
            boolean startNew = current.isEmpty()
                    || op.kind() != curKind
                    || current.size() >= maxStageOps
                    || Math.abs(op.x() - anchorX) > 4
                    || Math.abs(op.z() - anchorZ) > 4;
            if (startNew && !current.isEmpty()) {
                out.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            if (current.isEmpty()) {
                curKind = op.kind();
                anchorX = op.x();
                anchorZ = op.z();
            }
            current.add(op);
        }
        if (!current.isEmpty()) {
            out.add(List.copyOf(current));
        }
        return out;
    }
}
