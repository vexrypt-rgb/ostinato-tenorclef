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

/**
 * Structured diagnosis for why a road request or candidate alignment is infeasible.
 */
public final class ConstraintFailure {

    public enum Kind {
        /** Minimum feasible placed blocks exceeds the requested placed-block budget. */
        BUDGET_EXCEEDED,
        /** Protected-plot exclusion zone blocks all otherwise feasible alignments. */
        PROTECTED_ZONE,
        /** Unbreakable, fluid, or impassable overhead/body obstructions prevent required headroom clearance. */
        CLEARANCE_BLOCKED,
        /** Start and end elevations differ (level no-jump deck cannot bridge vertical grade changes in slice 1). */
        ELEVATION_MISMATCH,
        /** Required corridor terrain is unloaded / outside client-visible chunks. */
        UNLOADED_TERRAIN,
        /** Impassable hazards or terrain barriers disconnect endpoints within the bounded corridor. */
        TERRAIN_IMPASSABLE,
        /** A geometric alignment exists, but no safe builder access sequence exists without stranding or protected-plot violation. */
        BUILDER_ACCESS_BLOCKED,
        /** Player inventory does not hold enough pre-stocked full blocks for the accepted bill of materials. */
        INSUFFICIENT_MATERIALS,
        /** Width or request parameters unsupported by this slice. */
        UNSUPPORTED_SPEC
    }

    public record Detail(Kind kind, String message, int observed, int limit) {
        public Detail(Kind kind, String message) {
            this(kind, message, -1, -1);
        }

        @Override
        public String toString() {
            return kind + ": " + message;
        }
    }

    private ConstraintFailure() {}
}
