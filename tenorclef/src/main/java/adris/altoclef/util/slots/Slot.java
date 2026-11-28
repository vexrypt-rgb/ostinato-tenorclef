package adris.altoclef.util.slots;

import adris.altoclef.multiversion.ScreenVer;

import adris.altoclef.Debug;
import adris.altoclef.headless.BotClientBridge;
import net.minecraft.client.gui.screen.Screen;
//#if MC < 260000
import net.minecraft.client.gui.screen.ingame.*;
//#else
//$$ import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
//$$ import net.minecraft.client.gui.screens.inventory.CraftingScreen;
//$$ import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
//$$ import net.minecraft.client.gui.screens.inventory.ContainerScreen;
//$$ import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
//$$ import net.minecraft.client.gui.screens.inventory.MerchantScreen;
//$$ import net.minecraft.client.gui.screens.inventory.SmithingScreen;
//$$ import net.minecraft.client.gui.screens.inventory.SmokerScreen;
//#endif
import net.minecraft.entity.player.PlayerEntity;
//#if MC < 260000
import net.minecraft.screen.*;
//#else
//$$ import net.minecraft.world.inventory.AbstractFurnaceMenu;
//$$ import net.minecraft.world.inventory.CraftingMenu;
//$$ import net.minecraft.world.inventory.EnchantmentMenu;
//$$ import net.minecraft.world.inventory.ChestMenu;
//$$ import net.minecraft.world.inventory.InventoryMenu;
//$$ import net.minecraft.world.inventory.AbstractContainerMenu;
//$$ import net.minecraft.world.inventory.MerchantMenu;
//$$ import net.minecraft.world.inventory.SmithingMenu;
//#endif

import java.util.Iterator;
import java.util.Objects;

// Very helpful links
// Container Window Slots (used to move stuff around all containers, including player):
//      https://wiki.vg/Inventory
// Player Inventory Slots (used to grab inventory items only):
//      https://minecraft.gamepedia.com/Inventory
public abstract class Slot {

    // -1 means cursor slot, the slot of the cursor when it holds an item.
    public static final int CURSOR_SLOT_INDEX = -1;
    private static final int UNDEFINED_SLOT_INDEX = -999;
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static Slot UNDEFINED = new PlayerSlot(UNDEFINED_SLOT_INDEX);
    private final int _inventorySlot;
    private final int _windowSlot;
    private final boolean _isInventory;

    public Slot(int slot, boolean inventory) {
        _isInventory = inventory;
        if (inventory) {
            _inventorySlot = slot;
            _windowSlot = UNDEFINED_SLOT_INDEX;
            //_windowSlot = inventorySlotToWindowSlot(slot);
        } else {
            //_inventorySlot = windowSlotToInventorySlot(slot);
            _inventorySlot = UNDEFINED_SLOT_INDEX;
            _windowSlot = slot;
        }
    }

    private static Slot getFromCurrentScreenAbstract(int slot, boolean inventory) {
        switch (getCurrentType()) {
            case PLAYER:
                return new PlayerSlot(slot, inventory);
            case CRAFTING_TABLE:
                return new CraftingTableSlot(slot, inventory);
            case FURNACE_OR_SMITH_OR_SMOKER_OR_BLAST:
                return new FurnaceSlot(slot, inventory);
            case CHEST_LARGE:
                return new ChestSlot(slot, true, inventory);
            case CHEST_SMALL:
                return new ChestSlot(slot, false, inventory);
            case ENCHANTING_TABLE:
                return new EnchantingTableSlot(slot, inventory);
            case MERCHANT:
                return new MerchantSlot(slot, inventory);
            default:
                Debug.logWarning("Unhandled slot for inventory check: " + getCurrentType());
                return null;
        }
    }

    public static Slot getFromCurrentScreen(int windowSlot) {
        return getFromCurrentScreenAbstract(windowSlot, false);
    }

    public static Slot getFromCurrentScreenInventory(int inventorySlot) {
        return getFromCurrentScreenAbstract(inventorySlot, true);
    }

    private static ContainerType getCurrentType() {
        if (BotClientBridge.isHeadless()) {
            PlayerEntity player = BotClientBridge.player();
            ScreenHandler handler = player != null ? player.currentScreenHandler : null;
            if (handler instanceof AbstractFurnaceScreenHandler || handler instanceof SmithingScreenHandler) {
                return ContainerType.FURNACE_OR_SMITH_OR_SMOKER_OR_BLAST;
            }
            if (handler instanceof GenericContainerScreenHandler gch) {
                return gch.getRows() == 6 ? ContainerType.CHEST_LARGE : ContainerType.CHEST_SMALL;
            }
            if (handler instanceof CraftingScreenHandler) {
                return ContainerType.CRAFTING_TABLE;
            }
            if (handler instanceof EnchantmentScreenHandler) {
                return ContainerType.ENCHANTING_TABLE;
            }
            if (handler instanceof MerchantScreenHandler) {
                return ContainerType.MERCHANT;
            }
            return ContainerType.PLAYER;
        }
        Screen screen = BotClientBridge.currentScreenOrNull();
        if (screen instanceof FurnaceScreen || screen instanceof SmithingScreen || screen instanceof SmokerScreen ||
                screen instanceof BlastFurnaceScreen) {
            return ContainerType.FURNACE_OR_SMITH_OR_SMOKER_OR_BLAST;
        }
        if (screen instanceof GenericContainerScreen) {
            GenericContainerScreenHandler handler = ((GenericContainerScreen) screen).getScreenHandler();
            boolean big = (handler.getRows() == 6);
            return big ? ContainerType.CHEST_LARGE : ContainerType.CHEST_SMALL;
        }
        if (screen instanceof CraftingScreen) {
            return ContainerType.CRAFTING_TABLE;
        }
        if (screen instanceof EnchantmentScreen) {
            return ContainerType.ENCHANTING_TABLE;
        }
        if (screen instanceof MerchantScreen) {
            return ContainerType.MERCHANT;
        }
        return ContainerType.PLAYER;
    }

    public static boolean isCursor(Slot slot) {
        return slot instanceof CursorSlot;
    }

    public static Iterable<Slot> getCurrentScreenSlots() {
        return () -> new Iterator<>() {
            final PlayerEntity player = BotClientBridge.player();
            final ScreenHandler handler = player != null ? player.currentScreenHandler : null;
            final int MAX = handler != null ? handler.slots.size() : 0;
            int i = -1;

            @Override
            public boolean hasNext() {
                return i < MAX;
            }

            @Override
            public Slot next() {
                if (i == -1) {
                    ++i;
                    return new CursorSlot();
                }
                return Slot.getFromCurrentScreen(i++);
            }
        };
    }

    public int getInventorySlot() {
        if (!_isInventory) {
            return windowSlotToInventorySlot(_windowSlot);
        }
        return _inventorySlot;
    }

    public int getWindowSlot() {
        if (_isInventory) {
            return inventorySlotToWindowSlot(_inventorySlot);
        }
        return _windowSlot;
    }

    protected abstract int inventorySlotToWindowSlot(int inventorySlot);

    protected abstract int windowSlotToInventorySlot(int windowSlot);

    protected abstract String getName();

    @Override
    public String toString() {
        return getName() + (_isInventory ? "InventorySlot" : "Slot") + "{" +
                "inventory slot = " + getInventorySlot() +
                ", window slot = " + getWindowSlot() +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if (o instanceof Slot slot) {
            return getInventorySlot() == slot.getInventorySlot() && getWindowSlot() == slot.getWindowSlot();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getInventorySlot(), getWindowSlot());
    }

    /**
     * @return Whether this slot exists within the player's inventory or in a container that's disconnected from the player's inventory.
     */
    public boolean isSlotInPlayerInventory() {
        PlayerEntity player = BotClientBridge.player();
        ScreenHandler handler = player != null ? player.currentScreenHandler : null;
        int windowSlot = getWindowSlot();
        if (handler instanceof PlayerScreenHandler) {
            // Everything visible is player inventory.
            return true;
        }
        int slotCount = handler != null ? handler.slots.size() : 0;
        return windowSlot >= (slotCount - (4 * 9));
    }

    enum ContainerType {
        PLAYER,
        CRAFTING_TABLE,
        CHEST_SMALL,
        CHEST_LARGE,
        FURNACE_OR_SMITH_OR_SMOKER_OR_BLAST,
        ENCHANTING_TABLE,
        MERCHANT
    }
}
