package adris.altoclef.tasks.replenish;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplenishCommandParserTest {

    @Test
    void parsesNaturalPlayerRequestForm() {
        ReplenishCommandParser.ParsedCommand cmd = ReplenishCommandParser.parse(
                "torch from home to quarry below 32 target 64 reserve 16");
        assertEquals(ReplenishCommandParser.Action.START_RULE, cmd.action());
        ReplenishmentRule r = cmd.rule();
        assertNotNull(r);
        assertEquals("torch", r.itemId());
        assertEquals("home", r.sourceName());
        assertEquals("quarry", r.destinationName());
        assertEquals(32, r.triggerBelow());
        assertEquals(64, r.targetCount());
        assertEquals(16, r.sourceReserve());
        assertFalse(r.shouldTrigger(32));
        assertTrue(r.shouldTrigger(31));
    }

    @Test
    void parsesKeyValueFormWithInterval() {
        ReplenishCommandParser.ParsedCommand cmd = ReplenishCommandParser.parse(
                "item=minecraft:torch from=home to=quarry below=32 target=64 reserve=16 interval=2");
        assertEquals(ReplenishCommandParser.Action.START_RULE, cmd.action());
        assertEquals("torch", cmd.rule().itemId());
        assertEquals(2_000L, cmd.rule().pollIntervalMs());
    }

    @Test
    void parsesChestRegistrationAndControlSubcommands() {
        ReplenishCommandParser.ParsedCommand reg = ReplenishCommandParser.parse("chest home 10 64 -5");
        assertEquals(ReplenishCommandParser.Action.REGISTER_CHEST, reg.action());
        assertEquals("home", reg.chestName());
        assertTrue(reg.hasCoords());
        assertEquals(10, reg.x());
        assertEquals(64, reg.y());
        assertEquals(-5, reg.z());

        assertEquals(ReplenishCommandParser.Action.LIST_CHESTS, ReplenishCommandParser.parse("chests").action());
        assertEquals(ReplenishCommandParser.Action.STATUS, ReplenishCommandParser.parse("status").action());
        assertEquals(ReplenishCommandParser.Action.STATUS, ReplenishCommandParser.parse("").action());
        assertEquals(ReplenishCommandParser.Action.STOP, ReplenishCommandParser.parse("stop").action());
    }

    @Test
    void rejectsInvalidRules() {
        assertThrows(IllegalArgumentException.class, () ->
                ReplenishCommandParser.parse("torch from home to home below 32 target 64 reserve 16"));
        assertThrows(IllegalArgumentException.class, () ->
                ReplenishCommandParser.parse("torch from home to quarry below 64 target 32 reserve 16"));
        assertThrows(IllegalArgumentException.class, () ->
                ReplenishCommandParser.parse("torch from home to quarry below 32 target 64 reserve -1"));
        assertThrows(IllegalArgumentException.class, () ->
                ReplenishCommandParser.parse("torch from home"));
    }
}
