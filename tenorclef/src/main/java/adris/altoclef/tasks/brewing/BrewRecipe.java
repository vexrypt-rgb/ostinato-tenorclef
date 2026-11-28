package adris.altoclef.tasks.brewing;

import java.util.Objects;

/**
 * Pure-Java definition of a two-stage brewing recipe.
 * <p>
 * First playable slice supports ordinary Potion of Healing:
 * {@code Water Bottle (minecraft:potion[minecraft:water])}
 * + {@code minecraft:nether_wart} -> {@code Awkward Potion (minecraft:potion[minecraft:awkward])}
 * + {@code minecraft:glistering_melon_slice} -> {@code Ordinary Potion of Healing (minecraft:potion[minecraft:healing])}.
 */
public final class BrewRecipe {

    public static final int MAX_BOTTLES_PER_BATCH = 3;
    public static final int FUEL_USES_PER_BLAZE_POWDER = 20;

    public static final BrewRecipe ORDINARY_HEALING = new BrewRecipe(
            "ordinary_healing",
            "Ordinary Potion of Healing",
            "minecraft:potion",
            "minecraft:water",
            "minecraft:nether_wart",
            "minecraft:awkward",
            "minecraft:glistering_melon_slice",
            "minecraft:healing",
            "minecraft:blaze_powder"
    );

    private final String id;
    private final String displayName;
    private final String bottleItemId;
    private final String basePotionId;
    private final String stage1IngredientId;
    private final String stage1PotionId;
    private final String stage2IngredientId;
    private final String finalPotionId;
    private final String fuelItemId;

    public BrewRecipe(String id,
                      String displayName,
                      String bottleItemId,
                      String basePotionId,
                      String stage1IngredientId,
                      String stage1PotionId,
                      String stage2IngredientId,
                      String finalPotionId,
                      String fuelItemId) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.bottleItemId = BrewItemSnapshot.normalizeIdOrEmpty(bottleItemId);
        this.basePotionId = BrewItemSnapshot.normalizeIdOrEmpty(basePotionId);
        this.stage1IngredientId = BrewItemSnapshot.normalizeIdOrEmpty(stage1IngredientId);
        this.stage1PotionId = BrewItemSnapshot.normalizeIdOrEmpty(stage1PotionId);
        this.stage2IngredientId = BrewItemSnapshot.normalizeIdOrEmpty(stage2IngredientId);
        this.finalPotionId = BrewItemSnapshot.normalizeIdOrEmpty(finalPotionId);
        this.fuelItemId = BrewItemSnapshot.normalizeIdOrEmpty(fuelItemId);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String bottleItemId() {
        return bottleItemId;
    }

    public String basePotionId() {
        return basePotionId;
    }

    public String stage1IngredientId() {
        return stage1IngredientId;
    }

    public String stage1PotionId() {
        return stage1PotionId;
    }

    public String stage2IngredientId() {
        return stage2IngredientId;
    }

    public String finalPotionId() {
        return finalPotionId;
    }

    public String fuelItemId() {
        return fuelItemId;
    }

    public boolean isExactBaseBottle(BrewItemSnapshot stack) {
        return stack != null && stack.isExactOrdinaryPotion(bottleItemId, basePotionId);
    }

    public boolean isExactStage1Output(BrewItemSnapshot stack) {
        return stack != null && stack.isExactOrdinaryPotion(bottleItemId, stage1PotionId);
    }

    public boolean isExactFinalOutput(BrewItemSnapshot stack) {
        return stack != null && stack.isExactOrdinaryPotion(bottleItemId, finalPotionId);
    }

    public boolean isKnownRecipeBottle(BrewItemSnapshot stack) {
        return isExactBaseBottle(stack) || isExactStage1Output(stack) || isExactFinalOutput(stack);
    }

    /**
     * Returns {@code 0} for base water bottle, {@code 1} for stage-1 intermediate (awkward),
     * {@code 2} for exact final output (ordinary healing), or {@code -1} for any other item
     * (including splash/lingering/strong/wrong-type/custom-effect potions).
     */
    public int bottleStage(BrewItemSnapshot stack) {
        if (isExactBaseBottle(stack)) return 0;
        if (isExactStage1Output(stack)) return 1;
        if (isExactFinalOutput(stack)) return 2;
        return -1;
    }

    public boolean isStage1Ingredient(BrewItemSnapshot stack) {
        return stack != null && stack.isItem(stage1IngredientId);
    }

    public boolean isStage2Ingredient(BrewItemSnapshot stack) {
        return stack != null && stack.isItem(stage2IngredientId);
    }

    public boolean isFuel(BrewItemSnapshot stack) {
        return stack != null && stack.isItem(fuelItemId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BrewRecipe that)) return false;
        return Objects.equals(id, that.id)
                && Objects.equals(bottleItemId, that.bottleItemId)
                && Objects.equals(basePotionId, that.basePotionId)
                && Objects.equals(stage1IngredientId, that.stage1IngredientId)
                && Objects.equals(stage1PotionId, that.stage1PotionId)
                && Objects.equals(stage2IngredientId, that.stage2IngredientId)
                && Objects.equals(finalPotionId, that.finalPotionId)
                && Objects.equals(fuelItemId, that.fuelItemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, bottleItemId, basePotionId, stage1IngredientId, stage1PotionId,
                stage2IngredientId, finalPotionId, fuelItemId);
    }

    @Override
    public String toString() {
        return displayName + " (" + bottleItemId + "[" + finalPotionId + "])";
    }
}
