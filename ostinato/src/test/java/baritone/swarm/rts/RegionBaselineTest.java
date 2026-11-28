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

package baritone.swarm.rts;

import baritone.swarm.rts.BlueprintRepairSession.CellObservation;
import baritone.swarm.rts.RegionBaseline.Entry;
import baritone.swarm.rts.RegionBaseline.Origin;
import baritone.swarm.rts.RegionBaseline.Report;
import baritone.swarm.rts.RegionBaseline.Selection;
import baritone.swarm.rts.RegionBaseline.Verdict;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Ostinato: the player-approved region baseline. Walks the "repair this wall but keep my doorway" fixture at the pure
 * level: an 8x4 stone-brick wall, a registered doorway, an approved missing patch, a second unclassified opening, and
 * an unloaded end of the wall, across a write/read round trip standing in for a client restart.
 */
public class RegionBaselineTest {

    private static final int[] BOX = {0, 64, 0, 7, 67, 0};

    /** A mutable fake world: unset cells are air, cells in {@code unloaded} are not loaded. */
    private static final class FakeWorld implements BlueprintRepairSession.WorldReader {
        final Map<Long, CellObservation> blocks = new HashMap<>();
        final Set<Long> unloaded = new java.util.HashSet<>();

        void set(int x, int y, int z, String block) {
            blocks.put(Blueprint.cellKey(x, y, z), CellObservation.of(block, Map.of()));
        }

        void clear(int x, int y, int z) {
            blocks.remove(Blueprint.cellKey(x, y, z));
        }

        @Override
        public CellObservation read(int x, int y, int z) {
            long k = Blueprint.cellKey(x, y, z);
            if (unloaded.contains(k)) {
                return CellObservation.unloaded();
            }
            return blocks.getOrDefault(k, CellObservation.air());
        }
    }

    private static FakeWorld wall() {
        FakeWorld w = new FakeWorld();
        for (int x = 0; x <= 7; x++) {
            for (int y = 64; y <= 67; y++) {
                w.set(x, y, 0, "stone_bricks");
            }
        }
        return w;
    }

    private static RegionBaseline snapshot(FakeWorld w) {
        RegionBaseline b = new RegionBaseline("wall", "sp:testworld", "minecraft:overworld", BOX, 1L);
        for (int x = 0; x <= 7; x++) {
            for (int y = 64; y <= 67; y++) {
                CellObservation o = w.read(x, y, 0);
                b.capture(new Entry(x, y, 0, o.block(), o.properties(), Origin.SNAPSHOT));
            }
        }
        return b;
    }

    private static RegionBaseline restart(RegionBaseline b) {
        return RegionBaseline.read(b.write());
    }

    @Test
    public void acceptanceFixtureRestoresOnlyAuthorizedPatchAndReviewsTheRest() {
        FakeWorld w = wall();
        RegionBaseline b = snapshot(w);
        assertEquals(32, b.size());

        // The player adds a doorway (x=2, y=64..65) and registers it as a deliberate edit.
        w.clear(2, 64, 0);
        w.clear(2, 65, 0);
        assertTrue(b.accept(2, 64, 0, "air", Map.of()));
        assertTrue(b.accept(2, 65, 0, "air", Map.of()));
        // Damage: a separate patch of approved masonry (x=5..6, y=66..67) is gone.
        for (int x = 5; x <= 6; x++) {
            for (int y = 66; y <= 67; y++) {
                w.clear(x, y, 0);
            }
        }
        // A second opening nobody registered (x=0, y=65).
        w.clear(0, 65, 0);
        // The east end of the wall (x=7) is in an unloaded chunk.
        for (int y = 64; y <= 67; y++) {
            w.unloaded.add(Blueprint.cellKey(7, y, 0));
        }

        RegionBaseline after = restart(b);
        Selection patch = new Selection(Set.of(), List.of(new int[]{5, 66, 0, 6, 67, 0}));
        Report r = after.diff(w, id -> false, patch);

        assertEquals(4, r.restore());
        for (RegionBaseline.Finding f : r.of(Verdict.RESTORE)) {
            assertTrue(f.x() >= 5 && f.x() <= 6 && f.y() >= 66);
            assertEquals("stone_bricks", f.expected());
        }
        assertEquals("the doorway is accepted, so it is neither restorable nor reviewed", 0,
                r.of(Verdict.REVIEW).stream().filter(f -> f.x() == 2).count());
        assertEquals(1, r.review());
        RegionBaseline.Finding second = r.of(Verdict.REVIEW).get(0);
        assertEquals(0, second.x());
        assertEquals(65, second.y());
        assertTrue(second.reason().contains("not authorized"));
        assertEquals(4, r.unknown());
        for (RegionBaseline.Finding f : r.of(Verdict.UNKNOWN)) {
            assertEquals(7, f.x());
        }

        Blueprint repair = after.restoreBlueprint(r, RegionBaseline.repairBlueprintName("wall"));
        assertEquals(4, repair.size());
        assertNull("the doorway is never in the repair set", repair.at(2, 64, 0));
        assertNull("the unclassified opening is never in the repair set", repair.at(0, 65, 0));
        assertNull("unloaded cells are never in the repair set", repair.at(7, 64, 0));
    }

    @Test
    public void unloadedCellsStayUnknownNeverMissingEvenWhenAuthorized() {
        FakeWorld w = wall();
        RegionBaseline b = snapshot(w);
        w.unloaded.add(Blueprint.cellKey(3, 64, 0));
        Selection everything = new Selection(Set.of(), List.of(BOX));
        Report r = b.diff(w, id -> false, everything);
        assertEquals(1, r.unknown());
        assertEquals(0, r.restore());
        // Revisited: the chunk loads and the block really is there.
        w.unloaded.clear();
        assertEquals(0, b.diff(w, id -> false, everything).unknown());
    }

    @Test
    public void nothingIsRestoredWithoutSelectionOrStandingPolicy() {
        FakeWorld w = wall();
        RegionBaseline b = snapshot(w);
        w.clear(4, 66, 0);
        Report r = b.diff(w, id -> false, Selection.NONE);
        assertEquals(0, r.restore());
        assertEquals(1, r.review());

        b.addZone(new int[]{4, 66, 0, 4, 66, 0});
        Report withPolicy = restart(b).diff(w, id -> false, Selection.NONE);
        assertEquals(1, withPolicy.restore());
        assertEquals(0, withPolicy.review());
    }

    @Test
    public void explicitCellSelectionAuthorizesOnlyThoseCells() {
        FakeWorld w = wall();
        RegionBaseline b = snapshot(w);
        w.clear(4, 66, 0);
        w.clear(5, 66, 0);
        Selection one = new Selection(Set.of(Blueprint.cellKey(4, 66, 0)), List.of());
        Report r = b.diff(w, id -> false, one);
        assertEquals(1, r.restore());
        assertEquals(1, r.review());
        assertEquals(4, r.of(Verdict.RESTORE).get(0).x());
    }

    @Test
    public void unmanagedCellsAreNeverComparedOrRecaptured() {
        FakeWorld w = wall();
        RegionBaseline b = snapshot(w);
        assertTrue(b.exclude(6, 64, 0, "player's torch holder"));
        assertEquals(31, b.size());
        w.clear(6, 64, 0);
        RegionBaseline after = restart(b);
        Report r = after.diff(w, id -> false, new Selection(Set.of(), List.of(BOX)));
        assertEquals(0, r.restore());
        assertEquals(0, r.review());
        assertEquals(1, r.excluded());
        assertEquals("player's torch holder", after.exclusions().get(0).reason());
        assertFalse("capture must skip an excluded cell",
                after.capture(new Entry(6, 64, 0, "stone_bricks", Map.of(), Origin.SNAPSHOT)));
        // Accepting it brings it back under management in its current (air) state.
        assertTrue(after.accept(6, 64, 0, "air", Map.of()));
        assertFalse(after.isExcluded(6, 64, 0));
    }

    @Test
    public void acceptedOpeningRefilledIsReviewedNotRestoredOrRemoved() {
        FakeWorld w = wall();
        RegionBaseline b = snapshot(w);
        w.clear(2, 64, 0);
        b.accept(2, 64, 0, "air", Map.of());
        w.set(2, 64, 0, "oak_planks");
        Report r = b.diff(w, id -> false, new Selection(Set.of(), List.of(BOX)));
        assertEquals(0, r.restore());
        assertEquals(1, r.review());
        assertTrue(r.of(Verdict.REVIEW).get(0).reason().contains("accepted opening"));
    }

    @Test
    public void containersAreNeverOverwrittenOrRefilled() {
        FakeWorld w = wall();
        RegionBaseline b = snapshot(w);
        // A chest was placed where wall used to be: authorized or not, the chest is never replaced.
        w.set(3, 65, 0, "chest");
        Report r = b.diff(w, id -> id.equals("chest"), new Selection(Set.of(), List.of(BOX)));
        assertEquals(0, r.restore());
        assertEquals(1, r.review());
        assertTrue(r.of(Verdict.REVIEW).get(0).reason().contains("contents"));
    }

    @Test
    public void wrongBlockAndWrongStateAreRestorableWhenAuthorized() {
        FakeWorld w = new FakeWorld();
        RegionBaseline b = new RegionBaseline("p", "sp:w", "minecraft:overworld", new int[]{0, 64, 0, 2, 64, 0}, 1L);
        b.capture(new Entry(0, 64, 0, "oak_log", Map.of("axis", "y"), Origin.SNAPSHOT));
        b.capture(new Entry(1, 64, 0, "stone_brick_stairs", Map.of("facing", "east", "half", "bottom"), Origin.SNAPSHOT));
        b.capture(new Entry(2, 64, 0, "stone_bricks", Map.of(), Origin.SNAPSHOT));
        w.blocks.put(Blueprint.cellKey(0, 64, 0), CellObservation.of("oak_log", Map.of("axis", "x")));
        w.blocks.put(Blueprint.cellKey(1, 64, 0),
                CellObservation.of("stone_brick_stairs", Map.of("facing", "east", "half", "bottom", "shape", "inner_left")));
        w.set(2, 64, 0, "dirt");
        Report r = b.diff(w, id -> false, new Selection(Set.of(), List.of(new int[]{0, 64, 0, 2, 64, 0})));
        assertEquals("stair shape is neighbour-derived and not compared", 1, r.matched());
        assertEquals(2, r.restore());
        assertEquals("wrong state", r.of(Verdict.RESTORE).get(0).reason());
        assertEquals("found dirt", r.of(Verdict.RESTORE).get(1).reason());
    }

    @Test
    public void structuralPropertiesKeepOnlyWhatTheBuilderCanPlace() {
        assertEquals(Map.of("axis", "z"),
                RegionBaseline.structuralProperties("oak_log", Map.of("axis", "z", "waterlogged", "false")));
        assertEquals(Map.of("type", "top"),
                RegionBaseline.structuralProperties("stone_slab", Map.of("type", "top", "waterlogged", "false")));
        assertEquals(Map.of(), RegionBaseline.structuralProperties("stone_slab", Map.of("type", "double")));
        assertEquals(Map.of("facing", "north", "half", "top"),
                RegionBaseline.structuralProperties("stone_stairs",
                        Map.of("facing", "north", "half", "top", "shape", "outer_left")));
        assertEquals(Map.of(), RegionBaseline.structuralProperties("stone_bricks", Map.of("anything", "x")));
    }

    @Test
    public void roundTripPreservesIdentityRegionEditsExclusionsAndZones() {
        FakeWorld w = wall();
        RegionBaseline b = snapshot(w);
        b.accept(2, 64, 0, "air", Map.of());
        b.exclude(1, 64, 0, "decor");
        b.addZone(new int[]{4, 64, 0, 6, 67, 0});
        RegionBaseline r = restart(b);
        assertEquals(b.write(), r.write());
        assertTrue(r.sameWorld("sp:testworld", "minecraft:overworld"));
        assertFalse("another dimension is a different world", r.sameWorld("sp:testworld", "minecraft:the_nether"));
        assertFalse(r.sameWorld("sp:other", "minecraft:overworld"));
        assertEquals(Origin.ACCEPTED, r.at(2, 64, 0).origin());
        assertTrue(r.at(2, 64, 0).isAir());
        assertTrue(r.isExcluded(1, 64, 0));
        assertEquals(1, r.zones().size());
    }

    @Test
    public void boundsAndBadInputAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegionBaseline("big", "w", "d", new int[]{0, 0, 0, 64, 1, 1}, 0L));
        assertThrows(IllegalArgumentException.class, () -> RegionBaseline.safeName("has space"));
        assertThrows(IllegalArgumentException.class, () -> RegionBaseline.read("ost1\n1 1 1\n"));
        RegionBaseline b = new RegionBaseline("w", "w", "d", new int[]{0, 0, 0, 3, 3, 3}, 0L);
        assertThrows(IllegalArgumentException.class,
                () -> b.capture(new Entry(9, 0, 0, "stone", Map.of(), Origin.SNAPSHOT)));
        assertThrows(IllegalArgumentException.class, () -> b.addZone(new int[]{0, 0, 0, 9, 9, 9}));
    }
}
