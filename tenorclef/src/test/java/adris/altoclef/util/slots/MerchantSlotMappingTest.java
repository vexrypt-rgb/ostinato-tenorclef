package adris.altoclef.util.slots;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies MerchantMenu / MerchantScreenHandler window-slot to PlayerInventory-index mapping:
 * 0..2 = payment1, payment2, result; 3..29 = main inventory (9..35); 30..38 = hotbar (0..8).
 */
public class MerchantSlotMappingTest {

    @Test
    void merchantContainerSlotsKeepTheirWindowIndices() {
        Assertions.assertEquals(0, MerchantSlot.INPUT_SLOT_1.getWindowSlot());
        Assertions.assertEquals(1, MerchantSlot.INPUT_SLOT_2.getWindowSlot());
        Assertions.assertEquals(2, MerchantSlot.OUTPUT_SLOT.getWindowSlot());
    }

    @Test
    void hotbarAndMainInventoryRoundTrip() {
        for (int inv = 0; inv < 9; inv++) {
            MerchantSlot fromInv = new MerchantSlot(inv, true);
            int expectedWindow = inv + 30;
            Assertions.assertEquals(expectedWindow, fromInv.getWindowSlot());
            MerchantSlot fromWin = new MerchantSlot(expectedWindow);
            Assertions.assertEquals(inv, fromWin.getInventorySlot());
            Assertions.assertEquals(fromInv, fromWin);
        }
        for (int inv = 9; inv < 36; inv++) {
            MerchantSlot fromInv = new MerchantSlot(inv, true);
            int expectedWindow = inv - 6;
            Assertions.assertEquals(expectedWindow, fromInv.getWindowSlot());
            MerchantSlot fromWin = new MerchantSlot(expectedWindow);
            Assertions.assertEquals(inv, fromWin.getInventorySlot());
            Assertions.assertEquals(fromInv, fromWin);
        }
    }
}
