package adris.altoclef.compose;

import adris.altoclef.tasks.replenish.ContainerSlotSnapshot;
import adris.altoclef.util.ItemComponentReader;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared client-visible container snapshot adapter for teach-by-demonstration recording and replay.
 * Supports standard storage containers (chests, double chests, trapped chests, ender chests, barrels,
 * shulker boxes, and 3x3 containers) whose menus place container slots before the 36 player inventory slots.
 */
public final class DemoContainerSnapshots {

    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private DemoContainerSnapshots() {
    }

    public static boolean isSupportedContainerHandler(ScreenHandler handler) {
        return (handler instanceof GenericContainerScreenHandler
                || handler instanceof ShulkerBoxScreenHandler
                || handler instanceof Generic3x3ContainerScreenHandler)
                && handler.slots.size() > PLAYER_INVENTORY_SLOTS;
    }

    public static ContainerSlotSnapshot capture(ScreenHandler handler) {
        int totalSlots = handler.slots.size();
        int containerSlots = Math.max(0, totalSlots - PLAYER_INVENTORY_SLOTS);
        List<ContainerSlotSnapshot.SlotEntry> entries = new ArrayList<>(totalSlots);
        for (int i = 0; i < totalSlots; i++) {
            ItemStack st = handler.slots.get(i).getStack();
            if (st == null || st.isEmpty()) {
                entries.add(new ContainerSlotSnapshot.SlotEntry(i, i < containerSlots, "", 0, 64));
            } else {
                String id = ContainerSlotSnapshot.normalizeOrEmpty(ItemComponentReader.itemId(st));
                entries.add(new ContainerSlotSnapshot.SlotEntry(i, i < containerSlots, id, st.getCount(), st.getMaxCount()));
            }
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        String cursorId = (cursor == null || cursor.isEmpty())
                ? "" : ContainerSlotSnapshot.normalizeOrEmpty(ItemComponentReader.itemId(cursor));
        int cursorCount = (cursor == null || cursor.isEmpty()) ? 0 : cursor.getCount();
        int cursorMax = (cursor == null || cursor.isEmpty()) ? 64 : cursor.getMaxCount();
        return new ContainerSlotSnapshot(containerSlots, entries, cursorId, cursorCount, cursorMax);
    }
}
