package adris.altoclef.tasks.brewing;

import java.util.Objects;

/**
 * Pure-Java immutable brewing request parsed from the normal player command entry point.
 */
public final class BrewRequest {

    public static final int DEFAULT_BATCH_COUNT = 3;
    public static final int DEFAULT_SUPPLY_RADIUS = 8;

    private final BrewRecipe recipe;
    private final int targetCount;
    private final boolean hasExplicitStandPos;
    private final int standX;
    private final int standY;
    private final int standZ;
    private final int supplySearchRadius;

    public BrewRequest(BrewRecipe recipe,
                       int targetCount,
                       boolean hasExplicitStandPos,
                       int standX,
                       int standY,
                       int standZ,
                       int supplySearchRadius) {
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        if (targetCount <= 0) {
            throw new IllegalArgumentException("targetCount must be >= 1, got " + targetCount);
        }
        if (supplySearchRadius < 1 || supplySearchRadius > 32) {
            throw new IllegalArgumentException("supplySearchRadius must be between 1 and 32, got " + supplySearchRadius);
        }
        this.targetCount = targetCount;
        this.hasExplicitStandPos = hasExplicitStandPos;
        this.standX = standX;
        this.standY = standY;
        this.standZ = standZ;
        this.supplySearchRadius = supplySearchRadius;
    }

    public static BrewRequest ordinaryHealing(int count) {
        return new BrewRequest(BrewRecipe.ORDINARY_HEALING, count, false, 0, 0, 0, DEFAULT_SUPPLY_RADIUS);
    }

    public BrewRecipe recipe() {
        return recipe;
    }

    public int targetCount() {
        return targetCount;
    }

    public boolean hasExplicitStandPos() {
        return hasExplicitStandPos;
    }

    public int standX() {
        return standX;
    }

    public int standY() {
        return standY;
    }

    public int standZ() {
        return standZ;
    }

    public int supplySearchRadius() {
        return supplySearchRadius;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(targetCount).append("x ").append(recipe.displayName());
        if (hasExplicitStandPos) {
            sb.append(" at (").append(standX).append(", ").append(standY).append(", ").append(standZ).append(')');
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BrewRequest that)) return false;
        return targetCount == that.targetCount
                && hasExplicitStandPos == that.hasExplicitStandPos
                && standX == that.standX
                && standY == that.standY
                && standZ == that.standZ
                && supplySearchRadius == that.supplySearchRadius
                && Objects.equals(recipe, that.recipe);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recipe, targetCount, hasExplicitStandPos, standX, standY, standZ, supplySearchRadius);
    }

    @Override
    public String toString() {
        return describe();
    }
}
