package adris.altoclef.tasks.brewing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link BrewRequestParser} and exact potion recipe/component matching.
 */
public class BrewRequestParserTest {

    @Test
    void parsesOrdinaryHealingCommandsAndDefaults() {
        BrewRequestParser.ParsedCommand basic = BrewRequestParser.parseTokens(List.of("healing", "3"));
        Assertions.assertEquals(BrewRequestParser.Action.START, basic.action());
        Assertions.assertEquals(BrewRecipe.ORDINARY_HEALING, basic.request().recipe());
        Assertions.assertEquals(3, basic.request().targetCount());
        Assertions.assertFalse(basic.request().hasExplicitStandPos());

        BrewRequestParser.ParsedCommand withCoords = BrewRequestParser.parseTokens(
                List.of("potion{healing}", "3", "at", "10", "64", "-20", "radius=6"));
        Assertions.assertEquals(3, withCoords.request().targetCount());
        Assertions.assertTrue(withCoords.request().hasExplicitStandPos());
        Assertions.assertEquals(10, withCoords.request().standX());
        Assertions.assertEquals(64, withCoords.request().standY());
        Assertions.assertEquals(-20, withCoords.request().standZ());
        Assertions.assertEquals(6, withCoords.request().supplySearchRadius());

        BrewRequestParser.ParsedCommand natural = BrewRequestParser.parseTokens(List.of("ordinary", "healing"));
        Assertions.assertEquals(3, natural.request().targetCount());
        Assertions.assertEquals(BrewRecipe.ORDINARY_HEALING, natural.request().recipe());

        Assertions.assertEquals(BrewRequestParser.Action.STATUS,
                BrewRequestParser.parseTokens(List.of("status")).action());
        Assertions.assertEquals(BrewRequestParser.Action.STOP,
                BrewRequestParser.parseTokens(List.of("stop")).action());
    }

    @Test
    void rejectsUnsupportedVariantsAndInvalidCounts() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BrewRequestParser.parseTokens(List.of("splash_healing", "3")));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BrewRequestParser.parseTokens(List.of("lingering_healing", "3")));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BrewRequestParser.parseTokens(List.of("strong_healing", "3")));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BrewRequestParser.parseTokens(List.of("swiftness", "3")));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BrewRequestParser.parseTokens(List.of("healing", "0")));
    }

    @Test
    void exactPotionMatchingRejectsSplashLingeringStrongWrongTypeAndCustomEffects() {
        BrewRecipe r = BrewRecipe.ORDINARY_HEALING;

        BrewItemSnapshot ordinaryHealing = BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:healing");
        BrewItemSnapshot splashHealing = BrewItemSnapshot.ofPotion("minecraft:splash_potion", "minecraft:healing");
        BrewItemSnapshot lingeringHealing = BrewItemSnapshot.ofPotion("minecraft:lingering_potion", "minecraft:healing");
        BrewItemSnapshot strongHealing = BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:strong_healing");
        BrewItemSnapshot regen = BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:regeneration");
        BrewItemSnapshot customEffectsHealing = BrewItemSnapshot.ofPotion("minecraft:potion", "minecraft:healing", true);
        BrewItemSnapshot glassBottle = BrewItemSnapshot.ofItem("minecraft:glass_bottle", 1);

        Assertions.assertTrue(r.isExactFinalOutput(ordinaryHealing));
        Assertions.assertEquals(2, r.bottleStage(ordinaryHealing));

        Assertions.assertFalse(r.isExactFinalOutput(splashHealing));
        Assertions.assertFalse(r.isKnownRecipeBottle(splashHealing));
        Assertions.assertFalse(r.isExactFinalOutput(lingeringHealing));
        Assertions.assertFalse(r.isKnownRecipeBottle(lingeringHealing));
        Assertions.assertFalse(r.isExactFinalOutput(strongHealing));
        Assertions.assertFalse(r.isKnownRecipeBottle(strongHealing));
        Assertions.assertFalse(r.isExactFinalOutput(regen));
        Assertions.assertFalse(r.isKnownRecipeBottle(regen));
        Assertions.assertFalse(r.isExactFinalOutput(customEffectsHealing));
        Assertions.assertFalse(r.isKnownRecipeBottle(customEffectsHealing));
        Assertions.assertFalse(r.isKnownRecipeBottle(glassBottle));
    }
}
