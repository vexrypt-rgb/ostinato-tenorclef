package adris.altoclef.shop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShoppingSpecTest {

    @Test
    void parsesColonAndSpaceSeparatedAndMergesDuplicates() {
        List<ShoppingSpec.Entry> entries = ShoppingSpec.parse(
                "minecraft:oak_log:32, cobblestone 64; oak_log:16 diamond:5");
        assertEquals(3, entries.size());
        assertEquals(new ShoppingSpec.Entry("oak_log", 48), entries.get(0));
        assertEquals(new ShoppingSpec.Entry("cobblestone", 64), entries.get(1));
        assertEquals(new ShoppingSpec.Entry("diamond", 5), entries.get(2));
        assertEquals("oak_log:48 cobblestone:64 diamond:5", ShoppingSpec.format(entries));
    }

    @Test
    void handlesDefaultsAndInvalidTokensGracefully() {
        List<ShoppingSpec.Entry> entries = ShoppingSpec.parse("  [torch, iron_ingot:0, 42, gold_ingot:999999]  ");
        assertEquals(2, entries.size());
        assertEquals(new ShoppingSpec.Entry("torch", 1), entries.get(0));
        assertEquals(new ShoppingSpec.Entry("gold_ingot", ShoppingSpec.MAX_COUNT), entries.get(1));
    }

    @Test
    void emptySpecReturnsEmptyList() {
        assertTrue(ShoppingSpec.parse(null).isEmpty());
        assertTrue(ShoppingSpec.parse("   ").isEmpty());
        assertEquals("", ShoppingSpec.format(List.of()));
    }
}
