package adris.altoclef.tasks.husbandry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HusbandryRequestParserTest {

    @Test
    void parsesStandardGrowCowsForms() {
        var p1 = HusbandryRequestParser.parseTokens(List.of("cows", "6"));
        assertEquals(HusbandryRequestParser.Action.START, p1.action());
        assertEquals("cow", p1.request().species());
        assertEquals(6, p1.request().targetCount());
        assertFalse(p1.request().hasExplicitSeedPos());
        assertFalse(p1.request().hasExplicitBox());

        var p2 = HusbandryRequestParser.parseTokens(List.of("cow", "to", "6"));
        assertEquals(p1.request(), p2.request());

        var p3 = HusbandryRequestParser.parseTokens(List.of("6"));
        assertEquals(p1.request(), p3.request());
    }

    @Test
    void parsesExplicitSeedAndBox() {
        var atCmd = HusbandryRequestParser.parseTokens(List.of("cows", "6", "at", "10", "64", "-20"));
        assertTrue(atCmd.request().hasExplicitSeedPos());
        assertEquals(10, atCmd.request().seedX());
        assertEquals(64, atCmd.request().seedY());
        assertEquals(-20, atCmd.request().seedZ());

        var boxCmd = HusbandryRequestParser.parseTokens(List.of("cows", "6", "box", "5", "64", "8", "1", "64", "2"));
        assertTrue(boxCmd.request().hasExplicitBox());
        assertEquals(1, boxCmd.request().minX());
        assertEquals(5, boxCmd.request().maxX());
        assertEquals(2, boxCmd.request().minZ());
        assertEquals(8, boxCmd.request().maxZ());
    }

    @Test
    void parsesStatusAndStop() {
        assertEquals(HusbandryRequestParser.Action.STATUS,
                HusbandryRequestParser.parseTokens(List.of("status")).action());
        assertEquals(HusbandryRequestParser.Action.STOP,
                HusbandryRequestParser.parseTokens(List.of("stop")).action());
        assertEquals(HusbandryRequestParser.Action.STOP,
                HusbandryRequestParser.parseTokens(List.of("cancel")).action());
    }

    @Test
    void rejectsUnsupportedSpeciesAndInvalidCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> HusbandryRequestParser.parseTokens(List.of("sheep", "6")));
        assertThrows(IllegalArgumentException.class,
                () -> HusbandryRequestParser.parseTokens(List.of("cows", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> HusbandryRequestParser.parseTokens(List.of("cows", "-2")));
        assertThrows(IllegalArgumentException.class,
                () -> HusbandryRequestParser.parseTokens(List.of()));
    }
}
