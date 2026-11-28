package adris.altoclef.util.slots;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies BrewingStandMenu / BrewingStandScreenHandler window-slot to PlayerInventory-index mapping:
 * 0..2 = bottle slots, 3 = ingredient, 4 = fuel; 5..31 = main inventory (9..35); 32..40 = hotbar (0..8).
 */
public class BrewingStandSlotMappingTest {

    @Test
    void brewingStandContainerSlotsKeepTheirWindowIndices() {
        Assertions.assertEquals(0, BrewingStandSlot.LEFT_POTION.getWindowSlot());
        Assertions.assertEquals(1, BrewingStandSlot.MIDDLE_POTION.getWindowSlot());
        Assertions.assertEquals(2, BrewingStandSlot.RIGHT_POTION.getWindowSlot());
        Assertions.assertEquals(3, BrewingStandSlot.INGREDIENT.getWindowSlot());
        Assertions.assertEquals(4, BrewingStandSlot.FUEL.getWindowSlot());
        Assertions.assertEquals(3, BrewingStandSlot.BOTTLE_SLOTS.length);
    }

    @Test
    void hotbarAndMainInventoryRoundTrip() {
        for (int inv = 0; inv < 9; inv++) {
            BrewingStandSlot fromInv = new BrewingStandSlot(inv, true);
            int expectedWindow = inv + 32;
            Assertions.assertEquals(expectedWindow, fromInv.getWindowSlot());
            BrewingStandSlot fromWin = new BrewingStandSlot(expectedWindow);
            Assertions.assertEquals(inv, fromWin.getInventorySlot());
            Assertions.assertEquals(fromInv, fromWin);
        }
        for (int inv = 9; inv < 36; inv++) {
            BrewingStandSlot fromInv = new BrewingStandSlot(inv, true);
            int expectedWindow = inv - 4;
            Assertions.assertEquals(expectedWindow, fromInv.getWindowSlot());
            BrewingStandSlot fromWin = new BrewingStandSlot(expectedWindow);
            Assertions.assertEquals(inv, fromWin.getInventorySlot());
            Assertions.assertEquals(fromInv, fromWin);
        }
    }
}
