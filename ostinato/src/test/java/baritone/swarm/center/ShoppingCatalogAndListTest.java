package baritone.swarm.center;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ShoppingCatalog} and {@link ShoppingListModel}.
 */
public class ShoppingCatalogAndListTest {

    @Test
    public void loadsFullMinecraft263Catalog() {
        List<ShoppingCatalog.Entry> all = ShoppingCatalog.all();
        assertTrue("Expected full MC 26.3 item catalog (>1500 items), got " + all.size(), all.size() > 1500);
        assertNotNull(ShoppingCatalog.byId("diamond"));
        assertNotNull(ShoppingCatalog.byId("oak_log"));
        assertNotNull(ShoppingCatalog.byId("netherite_pickaxe"));
        assertNotNull(ShoppingCatalog.byId("enchanted_golden_apple"));
        assertEquals("Diamond", ShoppingCatalog.displayName("diamond"));
        assertEquals("Diamond", ShoppingCatalog.displayName("minecraft:diamond"));
    }

    @Test
    public void filtersByMultiTermQueryAndCategory() {
        List<ShoppingCatalog.Entry> picks = ShoppingCatalog.filter("diamond pick", ShoppingCatalog.Category.TOOLS);
        assertTrue(picks.stream().anyMatch(e -> e.id().equals("diamond_pickaxe")));
        assertTrue(picks.stream().noneMatch(e -> e.id().equals("diamond_sword")));

        List<ShoppingCatalog.Entry> allFoods = ShoppingCatalog.filter("", ShoppingCatalog.Category.FOOD);
        assertFalse(allFoods.isEmpty());
        assertTrue(allFoods.stream().allMatch(e -> e.category() == ShoppingCatalog.Category.FOOD));
    }

    @Test
    public void cartMutationsAndCommandRoundTrip() {
        ShoppingListModel cart = new ShoppingListModel();
        assertTrue(cart.isEmpty());
        assertEquals("", cart.buildShopCommand());

        assertEquals(32, cart.add("oak_log", 32));
        assertEquals(64, cart.add("minecraft:oak_log", 32));
        assertEquals(5, cart.set("diamond", 5));
        assertEquals(2, cart.distinctCount());
        assertEquals(69, cart.totalCount());
        assertEquals("oak_log:64 diamond:5", cart.formatSpec());
        assertEquals("shop gather oak_log:64 diamond:5", cart.buildShopCommand());

        // Setting to 0 or decrementing past 0 removes the entry
        assertEquals(0, cart.add("diamond", -10));
        assertEquals(1, cart.distinctCount());
        assertEquals(0, cart.countOf("diamond"));

        // Parse various user-typed formats
        java.util.Map<String, Integer> parsed = ShoppingListModel.parseSpec("minecraft:cobblestone:64, oak_log 16; cobblestone:32");
        assertEquals(2, parsed.size());
        assertEquals(Integer.valueOf(96), parsed.get("cobblestone"));
        assertEquals(Integer.valueOf(16), parsed.get("oak_log"));

        cart.clear();
        assertTrue(cart.isEmpty());
    }
}
