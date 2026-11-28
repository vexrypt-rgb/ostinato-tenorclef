package adris.altoclef.util.slots;

import adris.altoclef.headless.BotClientBridge;
import adris.altoclef.multiversion.ScreenVer;

import net.minecraft.client.gui.screen.Screen;
//#if MC < 260000
import net.minecraft.client.gui.screen.ingame.*;
import net.minecraft.screen.*;
//#else
//$$ import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
//$$ import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
//$$ import net.minecraft.client.gui.screens.inventory.CraftingScreen;
//$$ import net.minecraft.client.gui.screens.inventory.ContainerScreen;
//$$ import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
//$$ import net.minecraft.client.gui.screens.inventory.MerchantScreen;
//$$ import net.minecraft.client.gui.screens.inventory.SmithingScreen;
//$$ import net.minecraft.world.inventory.AbstractFurnaceMenu;
//$$ import net.minecraft.world.inventory.BlastFurnaceMenu;
//$$ import net.minecraft.world.inventory.BrewingStandMenu;
//$$ import net.minecraft.world.inventory.ChestMenu;
//$$ import net.minecraft.world.inventory.CraftingMenu;
//$$ import net.minecraft.world.inventory.EnchantmentMenu;
//$$ import net.minecraft.world.inventory.FurnaceMenu;
//$$ import net.minecraft.world.inventory.AbstractContainerMenu;
//$$ import net.minecraft.world.inventory.MerchantMenu;
//$$ import net.minecraft.world.inventory.SmithingMenu;
//$$ import net.minecraft.world.inventory.SmokerMenu;
//#endif
import net.minecraft.entity.player.PlayerEntity;
import org.apache.commons.lang3.NotImplementedException;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@SuppressWarnings("rawtypes")
public class SlotScreenMapping {

    // Order here matters as whoever returns "true" in the predicate first is picked.
    private static final List<SlotScreenMappingEntry> _classList = List.of(
            e(CraftingTableSlot.class, screen -> screen instanceof CraftingScreen, CraftingTableSlot::new),
            e(FurnaceSlot.class, screen -> screen instanceof AbstractFurnaceScreen, FurnaceSlot::new),
            e(SmokerSlot.class, screen -> screen instanceof AbstractFurnaceScreen, SmokerSlot::new),
            e(BlastFurnaceSlot.class, screen -> screen instanceof AbstractFurnaceScreen, BlastFurnaceSlot::new),
            e(SmithingTableSlot.class, screen -> screen instanceof SmithingScreen, SmithingTableSlot::new),
            e(BrewingStandSlot.class, screen -> screen instanceof BrewingStandScreen, BrewingStandSlot::new),
            e(EnchantingTableSlot.class, screen -> screen instanceof EnchantmentScreen, EnchantingTableSlot::new),
            e(MerchantSlot.class, screen -> screen instanceof MerchantScreen, MerchantSlot::new),
            e(ChestSlot.class, screen -> screen instanceof GenericContainerScreen, ChestSlot::new),
            e(PlayerSlot.class, screen -> true, PlayerSlot::new), // Order matters, leave this BEFORE the BACK!
            e(CursorSlot.class, screen -> true, (slot, inv) -> CursorSlot.SLOT) // Order matters, leave this in the BACK!
    );

    @SuppressWarnings("unchecked")
    public static boolean isScreenOpen(Class slotType) {
        if (BotClientBridge.isHeadless()) {
            PlayerEntity p = BotClientBridge.player();
            ScreenHandler h = p != null ? p.currentScreenHandler : null;
            if (slotType == CraftingTableSlot.class) return h instanceof CraftingScreenHandler;
            if (slotType == FurnaceSlot.class || slotType == SmokerSlot.class || slotType == BlastFurnaceSlot.class) {
                return h instanceof AbstractFurnaceScreenHandler;
            }
            if (slotType == SmithingTableSlot.class) return h instanceof SmithingScreenHandler;
            if (slotType == BrewingStandSlot.class) return h instanceof BrewingStandScreenHandler;
            if (slotType == EnchantingTableSlot.class) return h instanceof EnchantmentScreenHandler;
            if (slotType == MerchantSlot.class) return h instanceof MerchantScreenHandler;
            if (slotType == ChestSlot.class) return h instanceof GenericContainerScreenHandler;
            return slotType == PlayerSlot.class || slotType == CursorSlot.class;
        }
        Screen screen = BotClientBridge.currentScreenOrNull();
        if (!_classList.isEmpty()) {
            for (SlotScreenMappingEntry entry : _classList) {
                if (slotType == entry.type || slotType.isAssignableFrom(entry.type)) {
                    return entry.inScreen.test(screen);
                }
            }
        }
        throw new NotImplementedException("Slot type class not registered in SlotScreenMapping: " + slotType + ". Please register! (current screen = " + screen + ")");
    }

    public static Slot getFromScreen(int slot, boolean inventory) {
        if (BotClientBridge.isHeadless()) {
            PlayerEntity p = BotClientBridge.player();
            ScreenHandler h = p != null ? p.currentScreenHandler : null;
            if (h instanceof CraftingScreenHandler) return new CraftingTableSlot(slot, inventory);
            if (h instanceof FurnaceScreenHandler) return new FurnaceSlot(slot, inventory);
            if (h instanceof SmokerScreenHandler) return new SmokerSlot(slot, inventory);
            if (h instanceof BlastFurnaceScreenHandler) return new BlastFurnaceSlot(slot, inventory);
            if (h instanceof AbstractFurnaceScreenHandler) return new FurnaceSlot(slot, inventory);
            if (h instanceof SmithingScreenHandler) return new SmithingTableSlot(slot, inventory);
            if (h instanceof BrewingStandScreenHandler) return new BrewingStandSlot(slot, inventory);
            if (h instanceof EnchantmentScreenHandler) return new EnchantingTableSlot(slot, inventory);
            if (h instanceof MerchantScreenHandler) return new MerchantSlot(slot, inventory);
            if (h instanceof GenericContainerScreenHandler) return new ChestSlot(slot, inventory);
            return new PlayerSlot(slot, inventory);
        }
        Screen screen = BotClientBridge.currentScreenOrNull();
        if (!_classList.isEmpty()) {
            for (SlotScreenMappingEntry entry : _classList) {
                if (entry.inScreen.test(screen)) {
                    return entry.getSlot.apply(slot, inventory);
                }
            }
        }
        throw new NotImplementedException("We should never get here, _classList should be filled with a predicate that always returns true at the bottom (for PlayerSlot & CursorSlot)");
    }


    private static SlotScreenMappingEntry e(Class type, Predicate<Screen> inScreen, BiFunction<Integer, Boolean, Slot> getSlot) {
        return new SlotScreenMappingEntry(type, inScreen, getSlot);
    }

    static class SlotScreenMappingEntry {
        public Class type;
        public Predicate<Screen> inScreen;
        public BiFunction<Integer, Boolean, Slot> getSlot;

        public SlotScreenMappingEntry(Class type, Predicate<Screen> inScreen, BiFunction<Integer, Boolean, Slot> getSlot) {
            this.type = type;
            this.inScreen = inScreen;
            this.getSlot = getSlot;
        }
    }
}
