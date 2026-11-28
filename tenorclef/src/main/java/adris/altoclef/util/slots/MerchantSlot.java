package adris.altoclef.util.slots;

/**
 * Villager / Merchant screen window slot mapping:
 * <ul>
 *     <li>0: first payment slot ({@code PAYMENT1_SLOT})</li>
 *     <li>1: second payment slot ({@code PAYMENT2_SLOT})</li>
 *     <li>2: trade output slot ({@code RESULT_SLOT})</li>
 *     <li>3..29: player main inventory (inventory indices 9..35)</li>
 *     <li>30..38: player hotbar (inventory indices 0..8)</li>
 * </ul>
 */
public class MerchantSlot extends Slot {

    public static final MerchantSlot INPUT_SLOT_1 = new MerchantSlot(0);
    public static final MerchantSlot INPUT_SLOT_2 = new MerchantSlot(1);
    public static final MerchantSlot OUTPUT_SLOT = new MerchantSlot(2);

    public MerchantSlot(int windowSlot) {
        this(windowSlot, false);
    }

    protected MerchantSlot(int slot, boolean inventory) {
        super(slot, inventory);
    }

    @Override
    protected int inventorySlotToWindowSlot(int inventorySlot) {
        if (inventorySlot < 9) {
            return inventorySlot + 30;
        }
        return inventorySlot - 6;
    }

    @Override
    protected int windowSlotToInventorySlot(int windowSlot) {
        if (windowSlot >= 30) {
            return windowSlot - 30;
        }
        return windowSlot + 6;
    }

    @Override
    protected String getName() {
        return "Merchant";
    }
}
