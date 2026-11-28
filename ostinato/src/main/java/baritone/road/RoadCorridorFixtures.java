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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Five held-out 40–80-block terrain road fixtures (4 feasible, 1 infeasible), each containing a stream and a protected
 * plot. Shared by pure unit/acceptance tests and the live Minecraft Java 26.3 Fabric test harness.
 */
public final class RoadCorridorFixtures {

    public static final int DECK_Y = 63;
    public static final int FEET_Y = DECK_Y + 1;
    public static final int BUDGET = 48;

    private RoadCorridorFixtures() {}

    /** Mutable voxel world used for deterministic offline fixtures and to mirror terrain into live Fabric worlds. */
    public static final class SyntheticWorld implements RoadProbe {
        private final Map<Long, CellKind> cells = new HashMap<>();
        private final int defaultDeckY;

        public SyntheticWorld(int defaultDeckY) {
            this.defaultDeckY = defaultDeckY;
        }

        @Override
        public CellKind get(int x, int y, int z) {
            CellKind override = cells.get(ProtectedZone.key(x, y, z));
            if (override != null) return override;
            return y <= defaultDeckY ? CellKind.SOLID_FULL : CellKind.AIR;
        }

        public void set(int x, int y, int z, CellKind kind) {
            cells.put(ProtectedZone.key(x, y, z), kind);
        }

        public Map<Long, CellKind> overrides() {
            return cells;
        }

        /** Carves a stream channel at {@code deckY} (water at deckY and deckY-1, solid riverbed at deckY-2). */
        public void fillStream(int x0, int x1, int z0, int z1) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, defaultDeckY, z, CellKind.FLUID);
                    set(x, defaultDeckY - 1, z, CellKind.FLUID);
                    set(x, defaultDeckY - 2, z, CellKind.SOLID_FULL);
                }
            }
        }

        /** Places a 2-block-high mound/ridge (natural solid blocks at deckY+1 and optionally deckY+2). */
        public void fillHill(int x0, int x1, int z0, int z1, int heightAboveDeck) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    for (int dy = 1; dy <= heightAboveDeck; dy++) {
                        set(x, defaultDeckY + dy, z, CellKind.SOLID_FULL);
                    }
                }
            }
        }

        /** Places a small tree/bush obstacle (trunk at deckY+1, canopy at deckY+2). */
        public void placeTree(int x, int z) {
            set(x, defaultDeckY + 1, z, CellKind.SOLID_FULL);
            set(x, defaultDeckY + 2, z, CellKind.SOLID_NON_FULL_CLEARABLE);
            set(x + 1, defaultDeckY + 2, z, CellKind.SOLID_NON_FULL_CLEARABLE);
            set(x - 1, defaultDeckY + 2, z, CellKind.SOLID_NON_FULL_CLEARABLE);
        }

        /** Marks a farm plot with farmland (non-full) at deckY and crops at deckY+1 so edits are immediately detectable. */
        public void populateProtectedField(ProtectedZone zone) {
            for (int x = zone.minX(); x <= zone.maxX(); x++) {
                for (int z = zone.minZ(); z <= zone.maxZ(); z++) {
                    set(x, defaultDeckY, z, CellKind.SOLID_NON_FULL_CLEARABLE);
                    set(x, defaultDeckY + 1, z, CellKind.PASSABLE_PLANT);
                }
            }
        }
    }

    public record Fixture(
            String id,
            String description,
            RoadSpec spec,
            SyntheticWorld world,
            boolean expectedFeasible,
            List<ConstraintFailure.Kind> expectedFailureKinds
    ) {}

    /** Returns all 5 held-out 40–80-block corridors (4 feasible + 1 infeasible). */
    public static List<Fixture> heldOutFive() {
        return List.of(
                fixture1HallToVillageNarrowFord(),
                fixture2WideMarshVsNarrowCrossing(),
                fixture3DiagonalHillAndCreek(),
                fixture4TightBudgetDoubleChannel(),
                fixture5InfeasibleBudgetProtectedWall()
        );
    }

    /**
     * F1 (52 blocks, feasible): Hall to village with a central protected crop field blocking the straight line and a
     * stream that widens near the direct axis but narrows to a 4-block ford to the south.
     */
    public static Fixture fixture1HallToVillageNarrowFord() {
        SyntheticWorld w = new SyntheticWorld(DECK_Y);
        ProtectedZone field = ProtectedZone.columns("field", 10, -4, 20, 4);
        w.populateProtectedField(field);

        // Stream runs N-S across the whole corridor; wide (10 blocks) in the north/center, narrows to 4 blocks at z=7..12.
        for (int z = -26; z <= 26; z++) {
            int x0, x1;
            if (z >= 7 && z <= 12) {
                x0 = 26; x1 = 29; // 4-block narrow ford -> 8 placed blocks
            } else {
                x0 = 23; x1 = 33; // 11-block wide channel -> 22 placed blocks
            }
            w.fillStream(x0, x1, z, z);
        }
        // Scattered bank trees on the far bank that need ordinary headroom clearing.
        w.placeTree(36, 8);
        w.placeTree(42, 4);
        w.set(34, DECK_Y, 9, CellKind.SOLID_NON_FULL_CLEARABLE); // stray slab/path block to clear & replace

        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, FEET_Y, 0, "village", 52, FEET_Y, 0, BUDGET, List.of(field));
        return new Fixture("F1_hall_to_village_narrow_ford",
                "52-block corridor: central protected crop field + stream narrowing to 4-block ford",
                spec, w, true, List.of());
    }

    /**
     * F2 (64 blocks, feasible): Direct route crosses a 26-block-wide marsh (52 placed blocks > 48 budget) and a 1-block
     * rocky ridge, while a northern protected vineyard forces the feasible 6-block crossing to the south.
     */
    public static Fixture fixture2WideMarshVsNarrowCrossing() {
        SyntheticWorld w = new SyntheticWorld(DECK_Y);
        ProtectedZone vineyard = ProtectedZone.columns("vineyard", 14, -18, 30, -5);
        w.populateProtectedField(vineyard);

        // Huge marsh across z=-6..7 (26 blocks wide -> 52 blocks > 48 budget!), narrowing to 6 blocks at z=10..16.
        for (int z = -26; z <= 26; z++) {
            int x0, x1;
            if (z >= 10 && z <= 16) {
                x0 = 30; x1 = 35; // 6 blocks wide -> 12 placed blocks
            } else {
                x0 = 18; x1 = 43; // 26 blocks wide -> 52 placed blocks (over budget!)
            }
            w.fillStream(x0, x1, z, z);
        }
        // Grassy 1-block ridge on direct axis so ordinary walker jumps over it.
        w.fillHill(8, 15, -3, 4, 1);
        w.placeTree(46, 11);

        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, FEET_Y, 0, "village", 64, FEET_Y, 0, BUDGET, List.of(vineyard));
        return new Fixture("F2_wide_marsh_vs_narrow_crossing",
                "64-block corridor: 26-block over-budget marsh on direct line, northern protected vineyard, southern 6-block crossing",
                spec, w, true, List.of());
    }

    /**
     * F3 (60-block Euclidean diagonal, feasible): Diagonal corridor (0,64,0)->(48,64,36) with a central protected
     * pasture, a 1-block grassy hill along the direct walking route, and a diagonal creek with a narrow straight crossing.
     */
    public static Fixture fixture3DiagonalHillAndCreek() {
        SyntheticWorld w = new SyntheticWorld(DECK_Y);
        ProtectedZone pasture = ProtectedZone.columns("pasture", 16, 10, 26, 20);
        w.populateProtectedField(pasture);

        // Diagonal creek crossing the corridor; widens near center, narrows to 5 blocks in the southern corridor.
        for (int z = -24; z <= 60; z++) {
            int centerX = 36 - (z / 3);
            int halfW = (z >= 24 && z <= 34) ? 2 : 5;
            w.fillStream(centerX - halfW, centerX + halfW, z, z);
        }
        // Natural hill right along the straight walking path (z=1..9, x=8..14).
        w.fillHill(8, 14, 1, 9, 1);
        w.placeTree(36, 28);

        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, FEET_Y, 0, "village", 48, FEET_Y, 36, BUDGET, List.of(pasture));
        return new Fixture("F3_diagonal_hill_and_creek",
                "60-block diagonal corridor: central protected pasture, natural hill on walking line, diagonal creek",
                spec, w, true, List.of());
    }

    /**
     * F4 (76 blocks, feasible tight budget): Two braided stream channels separated by a protected wheat field.
     * Wide bends require 56+ blocks (> 48), while routing via both narrowings uses ~36–40 placed blocks (<= 48).
     */
    public static Fixture fixture4TightBudgetDoubleChannel() {
        SyntheticWorld w = new SyntheticWorld(DECK_Y);
        ProtectedZone wheatField = ProtectedZone.columns("wheat_field", 32, -4, 46, 8);
        w.populateProtectedField(wheatField);

        // Channel A around x=16..28 (9 blocks at narrow northern pass z=-12..-7, 14 blocks elsewhere).
        // Channel B around x=50..64 (10 blocks at narrow northern pass z=-12..-7, 15 blocks elsewhere).
        for (int z = -26; z <= 26; z++) {
            if (z >= -12 && z <= -7) {
                w.fillStream(18, 26, z, z); // 9 wide -> 18 blocks
                w.fillStream(52, 61, z, z); // 10 wide -> 20 blocks (total 38 <= 48!)
            } else {
                w.fillStream(14, 27, z, z); // 14 wide -> 28 blocks
                w.fillStream(49, 63, z, z); // 15 wide -> 30 blocks (total 58 > 48!)
            }
        }
        w.placeTree(38, -9);
        w.placeTree(66, -6);

        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, FEET_Y, 0, "village", 76, FEET_Y, 0, BUDGET, List.of(wheatField));
        return new Fixture("F4_tight_budget_double_channel",
                "76-block corridor: dual braided channels (58 blocks direct vs 38 blocks via northern narrowings) + protected wheat field",
                spec, w, true, List.of());
    }

    /**
     * F5 (56 blocks, INFEASIBLE): Wide 27-block river across the entire corridor except an 8-block narrow crossing
     * whose banks lie inside the protected meadow sanctuary. Avoiding the protected sanctuary forces >= 54 placed
     * blocks (> 48 budget), whereas without the sanctuary constraint the narrow crossing needs only 16 blocks.
     */
    public static Fixture fixture5InfeasibleBudgetProtectedWall() {
        SyntheticWorld w = new SyntheticWorld(DECK_Y);
        ProtectedZone sanctuary = ProtectedZone.columns("sanctuary_meadow", 15, -13, 41, -4);

        for (int z = -26; z <= 26; z++) {
            if (z >= -11 && z <= -6) {
                // Only narrow crossing (8 wide -> 16 blocks) with grass meadow banks, all inside sanctuary_meadow!
                w.fillStream(24, 31, z, z);
                for (int x = 15; x <= 41; x++) {
                    if (x < 24 || x > 31) {
                        w.set(x, DECK_Y + 1, z, CellKind.PASSABLE_PLANT); // protected meadow flowers
                    }
                }
            } else {
                // 27 blocks wide -> 54 placed blocks > 48 budget!
                w.fillStream(15, 41, z, z);
            }
        }

        RoadSpec spec = RoadSpec.levelTwoWide("hall", 0, FEET_Y, 0, "village", 56, FEET_Y, 0, BUDGET, List.of(sanctuary));
        return new Fixture("F5_infeasible_budget_and_protected",
                "56-block corridor: narrow crossing inside protected sanctuary; all legal detours need >=54 placed blocks > 48 budget",
                spec, w, false, List.of(ConstraintFailure.Kind.BUDGET_EXCEEDED, ConstraintFailure.Kind.PROTECTED_ZONE));
    }
}
