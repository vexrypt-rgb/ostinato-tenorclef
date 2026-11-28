package adris.altoclef.tasks.trading;

import adris.altoclef.util.ItemComponentSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class ItemComponentSpecAndParserTest {

    @Test
    void exactEnchantmentSpecMatchesTargetAndRejectsLookAlikes() {
        ItemComponentSpec mending = ItemComponentSpec.parse("{mending}");
        Assertions.assertTrue(mending.matchesEnchantments(Map.of("minecraft:mending", 1)));
        Assertions.assertFalse(mending.matchesEnchantments(Map.of("minecraft:unbreaking", 3)));
        Assertions.assertFalse(mending.matchesEnchantments(Map.of()));
        Assertions.assertFalse(mending.matchesEnchantments(Map.of(
                "minecraft:mending", 1,
                "minecraft:vanishing_curse", 1)));

        ItemComponentSpec unbreaking3 = ItemComponentSpec.parse("unbreaking=3");
        Assertions.assertTrue(unbreaking3.matchesEnchantments(Map.of("minecraft:unbreaking", 3)));
        Assertions.assertFalse(unbreaking3.matchesEnchantments(Map.of("minecraft:unbreaking", 2)));
        Assertions.assertFalse(unbreaking3.matchesEnchantments(Map.of("minecraft:mending", 1)));

        ItemComponentSpec combo = ItemComponentSpec.parse("{unbreaking=3+mending=1}");
        Assertions.assertTrue(combo.matchesEnchantments(Map.of(
                "minecraft:unbreaking", 3,
                "minecraft:mending", 1)));
        Assertions.assertFalse(combo.matchesEnchantments(Map.of("minecraft:mending", 1)));
    }

    @Test
    void parserHandlesNormalGetExtensionsAndPlainTargets() {
        TradeRequestParser.ParsedGetTarget mendingOrder =
                TradeRequestParser.parseExpression("enchanted_book{mending} 1 max_emeralds=20");
        Assertions.assertEquals("enchanted_book", mendingOrder.baseName());
        Assertions.assertEquals(1, mendingOrder.count());
        Assertions.assertEquals(20, mendingOrder.maxEmeralds());
        Assertions.assertNull(mendingOrder.preferredMerchantId());
        Assertions.assertEquals(ItemComponentSpec.ofEnchantment("minecraft:mending"), mendingOrder.componentSpec());

        TradeRequestParser.ParsedGetTarget implicitCount =
                TradeRequestParser.parseExpression("enchanted_book{mending=1} budget=18 villager=42");
        Assertions.assertEquals("enchanted_book", implicitCount.baseName());
        Assertions.assertEquals(1, implicitCount.count());
        Assertions.assertEquals(18, implicitCount.maxEmeralds());
        Assertions.assertEquals("42", implicitCount.preferredMerchantId());
        Assertions.assertEquals(ItemComponentSpec.ofEnchantment("minecraft:mending", 1), implicitCount.componentSpec());

        TradeRequestParser.ParsedGetTarget plain =
                TradeRequestParser.parseExpression("oak_planks 16");
        Assertions.assertEquals("oak_planks", plain.baseName());
        Assertions.assertNull(plain.componentSpec());
        Assertions.assertEquals(16, plain.count());
        Assertions.assertEquals(VillagerTradeRequest.UNCAPPED_EMERALDS, plain.maxEmeralds());
    }

    @Test
    void parserRejectsMalformedSpecsAndNegativeBudget() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ItemComponentSpec.parse("{}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ItemComponentSpec.parse("{mending=0}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ItemComponentSpec.parse("{mending=-2}"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TradeRequestParser.parseExpression("enchanted_book{mending 1"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TradeRequestParser.parseExpression("enchanted_book{mending} 1 max_emeralds=-1"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TradeRequestParser.parseExpression("enchanted_book{mending} 1 max_emeralds=abc"));
    }
}
