package baritone.swarm.rts;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StoragePlanTest {

    @Test
    public void categoriesGroupByKind() {
        assertEquals(StoragePlan.Category.ORES, StoragePlan.category("coal"));
        assertEquals(StoragePlan.Category.ORES, StoragePlan.category("copper_ingot"));
        assertEquals(StoragePlan.Category.STONE, StoragePlan.category("cobblestone"));
        assertEquals(StoragePlan.Category.WOOD, StoragePlan.category("oak_log"));
        assertEquals(StoragePlan.Category.FARM, StoragePlan.category("wheat"));
        assertEquals(StoragePlan.Category.MOB, StoragePlan.category("string"));
        assertEquals(StoragePlan.Category.GEAR, StoragePlan.category("copper_pickaxe"));
    }

    @Test
    public void eachCategoryTakesTheChestHoldingMostOfIt() {
        StoragePlan p = new StoragePlan(2);
        p.saw(0, Map.of("coal", 10, "cobblestone", 30));
        p.saw(1, Map.of("coal", 40, "cobblestone", 5));
        Map<StoragePlan.Category, Integer> h = p.homes();
        assertEquals(1, (int) h.get(StoragePlan.Category.ORES));
        assertEquals(0, (int) h.get(StoragePlan.Category.STONE));
        assertTrue(p.misplaced(0, "coal"));
        assertFalse(p.misplaced(1, "coal"));
        assertTrue(p.misplaced(1, "cobblestone"));
    }

    @Test
    public void biggerCategoryPicksFirstAndTheNextTakesAFreeChest() {
        StoragePlan p = new StoragePlan(2);
        // both piles sit in chest 0: stone (more) keeps it, ores move to the free chest 1
        p.saw(0, Map.of("cobblestone", 64, "coal", 20));
        p.saw(1, Map.of());
        assertEquals(0, p.home("cobblestone"));
        assertEquals(1, p.home("coal"));
    }

    @Test
    public void moreCategoriesThanChestsShareTheChestHoldingMost() {
        StoragePlan p = new StoragePlan(2);
        p.saw(0, Map.of("cobblestone", 64, "oak_log", 3));
        p.saw(1, Map.of("coal", 32, "wheat", 2));
        assertEquals(0, p.home("cobblestone"));
        assertEquals(1, p.home("coal"));
        assertEquals(0, p.home("oak_log"));
        assertEquals(1, p.home("wheat"));
    }

    @Test
    public void sortedRoomHasNothingMisplacedAndUnknownItemsStay() {
        StoragePlan p = new StoragePlan(3);
        p.saw(0, Map.of("coal", 5));
        p.saw(1, Map.of("cobblestone", 5));
        p.saw(2, Map.of("oak_log", 5));
        assertTrue(p.surveyed());
        assertEquals(0, p.misplacedStacks(Map.of(0, List.of("coal"), 1, List.of("cobblestone"), 2, List.of("oak_log"))));
        assertEquals(-1, p.home("string"));
        assertFalse(p.misplaced(0, "string"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void oneChestIsNotStorage() {
        new StoragePlan(1);
    }
}
