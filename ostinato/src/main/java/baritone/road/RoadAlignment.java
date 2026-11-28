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

import java.util.List;
import java.util.Locale;

/**
 * A single evaluated candidate road alignment, with its footprint, bill of materials, access plan, and constraint verdict.
 */
public record RoadAlignment(
        String strategyName,
        int deckY,
        int width,
        int headroom,
        List<Long> anchorPath,
        List<Long> footprintColumns,
        List<Long> deckPlacements,
        List<Long> clearCells,
        int placedBlockCount,
        int clearedBlockCount,
        int streamCrossingColumns,
        int lengthSteps,
        int turnCount,
        boolean feasible,
        List<ConstraintFailure.Detail> violations,
        BuilderAccessPlan accessPlan,
        double score
) {
    public RoadAlignment {
        anchorPath = List.copyOf(anchorPath);
        footprintColumns = List.copyOf(footprintColumns);
        deckPlacements = List.copyOf(deckPlacements);
        clearCells = List.copyOf(clearCells);
        violations = List.copyOf(violations);
    }

    /** Bounding box of the road footprint as {@code {minX, deckY, minZ, maxX, deckY + headroom, maxZ}}. */
    public int[] boundingBox() {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long col : footprintColumns) {
            int x = ProtectedZone.colX(col);
            int z = ProtectedZone.colZ(col);
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (z < z0) z0 = z;
            if (z > z1) z1 = z;
        }
        if (x0 == Integer.MAX_VALUE) {
            return new int[]{0, deckY, 0, 0, deckY + headroom, 0};
        }
        return new int[]{x0, deckY, z0, x1, deckY + headroom, z1};
    }

    public String oneLineSummary(int budget) {
        return String.format(Locale.ROOT,
                "[%s] %s placed=%d/%d cleared=%d streamCols=%d steps=%d turns=%d stages=%d score=%.1f",
                strategyName,
                feasible ? "FEASIBLE" : "INFEASIBLE(" + (violations.isEmpty() ? "?" : violations.get(0).kind()) + ")",
                placedBlockCount, budget, clearedBlockCount, streamCrossingColumns,
                lengthSteps, turnCount,
                accessPlan == null ? 0 : accessPlan.stages().size(),
                score);
    }
}
