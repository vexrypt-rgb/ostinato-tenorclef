package adris.altoclef.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts normalized item identifiers and enchantment/component data from live Minecraft {@link ItemStack}s
 * for exact matching against {@link ItemComponentSpec}.
 */
public final class ItemComponentReader {

    private ItemComponentReader() {
    }

    /** Canonical lowercase registry ID (e.g. {@code minecraft:enchanted_book}), or empty string if null/air. */
    public static String itemId(Item item) {
        if (item == null || item == Items.AIR) {
            return "";
        }
        //#if MC >= 260000
        //$$ net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        //$$ return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
        //#else
        net.minecraft.util.Identifier id = net.minecraft.registry.Registries.ITEM.getId(item);
        return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
        //#endif
    }

    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return itemId(stack.getItem());
    }

    /**
     * Reads normalized enchantment entries ({@code minecraft:mending -> 1}, etc.) from an {@link ItemStack}.
     * Checks {@code STORED_ENCHANTMENTS} (used by {@code enchanted_book}) and {@code ENCHANTMENTS}.
     */
    public static Map<String, Integer> readEnchantments(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        //#if MC >= 260000
        //$$ addComponentEnchantments(stack.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS), result);
        //$$ addComponentEnchantments(stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS), result);
        //#elseif MC >= 12005
        addComponentEnchantments(stack.get(net.minecraft.component.DataComponentTypes.STORED_ENCHANTMENTS), result);
        addComponentEnchantments(stack.get(net.minecraft.component.DataComponentTypes.ENCHANTMENTS), result);
        //#else
        //$$ java.util.Map<net.minecraft.enchantment.Enchantment, Integer> legacy = net.minecraft.enchantment.EnchantmentHelper.get(stack);
        //$$ if (legacy != null) {
        //$$     for (java.util.Map.Entry<net.minecraft.enchantment.Enchantment, Integer> e : legacy.entrySet()) {
        //$$         net.minecraft.util.Identifier id = net.minecraft.registry.Registries.ENCHANTMENT.getId(e.getKey());
        //$$         if (id != null && e.getValue() != null && e.getValue() > 0) {
        //$$             result.put(id.toString().toLowerCase(Locale.ROOT), e.getValue());
        //$$         }
        //$$     }
        //$$ }
        //#endif
        return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
    }

    //#if MC >= 260000
    //$$ private static void addComponentEnchantments(net.minecraft.world.item.enchantment.ItemEnchantments enchants, Map<String, Integer> out) {
    //$$     if (enchants == null || enchants.isEmpty()) {
    //$$         return;
    //$$     }
    //$$     for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>> entry : enchants.entrySet()) {
    //$$         int level = entry.getIntValue();
    //$$         if (level <= 0) continue;
    //$$         entry.getKey().unwrapKey().ifPresent(key ->
    //$$                 out.put(key.identifier().toString().toLowerCase(Locale.ROOT), level));
    //$$     }
    //$$ }
    //#elseif MC >= 12005
    private static void addComponentEnchantments(net.minecraft.component.type.ItemEnchantmentsComponent enchants, Map<String, Integer> out) {
        if (enchants == null || enchants.isEmpty()) {
            return;
        }
        for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<net.minecraft.registry.entry.RegistryEntry<net.minecraft.enchantment.Enchantment>> entry : enchants.getEnchantmentEntries()) {
            int level = entry.getIntValue();
            if (level <= 0) continue;
            entry.getKey().getKey().ifPresent(key ->
                    out.put(key.getValue().toString().toLowerCase(Locale.ROOT), level));
        }
    }
    //#endif

    public static boolean matches(ItemStack stack, ItemComponentSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return stack != null && !stack.isEmpty();
        }
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return spec.matchesEnchantments(readEnchantments(stack));
    }

    /**
     * Reads the canonical lowercase potion registry ID (e.g. {@code minecraft:water},
     * {@code minecraft:awkward}, {@code minecraft:healing}, {@code minecraft:strong_healing})
     * from a potion/bottle {@link ItemStack}, or {@code ""} if absent.
     */
    public static String readPotionId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        //#if MC >= 260000
        //$$ net.minecraft.world.item.alchemy.PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        //$$ if (contents == null) {
        //$$     return "";
        //$$ }
        //$$ return contents.potion()
        //$$         .flatMap(net.minecraft.core.Holder::unwrapKey)
        //$$         .map(key -> key.identifier().toString().toLowerCase(Locale.ROOT))
        //$$         .orElse("");
        //#elseif MC >= 12005
        net.minecraft.component.type.PotionContentsComponent contents = stack.get(net.minecraft.component.DataComponentTypes.POTION_CONTENTS);
        if (contents == null) {
            return "";
        }
        return contents.potion()
                .flatMap(net.minecraft.registry.entry.RegistryEntry::getKey)
                .map(key -> key.getValue().toString().toLowerCase(Locale.ROOT))
                .orElse("");
        //#else
        //$$ net.minecraft.potion.Potion potion = net.minecraft.potion.PotionUtil.getPotion(stack);
        //$$ if (potion == null || potion == net.minecraft.potion.Potions.EMPTY) {
        //$$     return "";
        //$$ }
        //$$ net.minecraft.util.Identifier id = net.minecraft.registry.Registries.POTION.getId(potion);
        //$$ return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
        //#endif
    }

    /**
     * Returns {@code true} if the stack carries custom potion effects beyond a standard base potion.
     */
    public static boolean hasCustomPotionEffects(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        //#if MC >= 260000
        //$$ net.minecraft.world.item.alchemy.PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        //$$ return contents != null && !contents.customEffects().isEmpty();
        //#elseif MC >= 12005
        net.minecraft.component.type.PotionContentsComponent contents = stack.get(net.minecraft.component.DataComponentTypes.POTION_CONTENTS);
        return contents != null && !contents.customEffects().isEmpty();
        //#else
        //$$ return !net.minecraft.potion.PotionUtil.getCustomPotionEffects(stack).isEmpty();
        //#endif
    }
}
