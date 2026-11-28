package adris.altoclef.tasks.brewing;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure-Java immutable snapshot of an item stack relevant to potion brewing,
 * capturing exact item ID, count, max stack size, potion type ID, and whether
 * any custom effects are present.
 */
public final class BrewItemSnapshot {

    public static final BrewItemSnapshot EMPTY = new BrewItemSnapshot("", 0, 64, "", false);

    private final String itemId;
    private final int count;
    private final int maxStackSize;
    private final String potionId;
    private final boolean hasCustomEffects;

    public BrewItemSnapshot(String itemId, int count, int maxStackSize, String potionId, boolean hasCustomEffects) {
        String normItem = normalizeIdOrEmpty(itemId);
        int normCount = normItem.isEmpty() ? 0 : Math.max(0, count);
        if (normCount == 0) {
            this.itemId = "";
            this.count = 0;
            this.maxStackSize = 64;
            this.potionId = "";
            this.hasCustomEffects = false;
        } else {
            this.itemId = normItem;
            this.count = normCount;
            this.maxStackSize = Math.max(1, maxStackSize);
            this.potionId = normalizeIdOrEmpty(potionId);
            this.hasCustomEffects = hasCustomEffects;
        }
    }

    public static BrewItemSnapshot ofItem(String itemId, int count) {
        return new BrewItemSnapshot(itemId, count, 64, "", false);
    }

    public static BrewItemSnapshot ofItem(String itemId, int count, int maxStackSize) {
        return new BrewItemSnapshot(itemId, count, maxStackSize, "", false);
    }

    public static BrewItemSnapshot ofPotion(String bottleItemId, String potionId) {
        return new BrewItemSnapshot(bottleItemId, 1, 1, potionId, false);
    }

    public static BrewItemSnapshot ofPotion(String bottleItemId, String potionId, boolean hasCustomEffects) {
        return new BrewItemSnapshot(bottleItemId, 1, 1, potionId, hasCustomEffects);
    }

    /**
     * Normalizes an identifier to lowercase {@code namespace:path} (defaulting to {@code minecraft:}),
     * or returns {@code ""} for null/blank/air.
     */
    public static String normalizeIdOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("air") || s.equals("minecraft:air")) {
            return "";
        }
        if (s.indexOf(':') < 0) {
            s = "minecraft:" + s;
        }
        return s;
    }

    public boolean isEmpty() {
        return count <= 0 || itemId.isEmpty();
    }

    public String itemId() {
        return itemId;
    }

    public int count() {
        return count;
    }

    public int maxStackSize() {
        return maxStackSize;
    }

    public String potionId() {
        return potionId;
    }

    public boolean hasCustomEffects() {
        return hasCustomEffects;
    }

    public boolean isItem(String expectedItemId) {
        if (isEmpty()) {
            return false;
        }
        return itemId.equals(normalizeIdOrEmpty(expectedItemId));
    }

    /**
     * Checks exact bottle item form (e.g. {@code minecraft:potion}, rejecting splash/lingering),
     * exact potion type (e.g. {@code minecraft:healing}, rejecting strong_healing/regeneration),
     * and absence of custom potion effects.
     */
    public boolean isExactOrdinaryPotion(String expectedBottleItemId, String expectedPotionId) {
        if (isEmpty() || hasCustomEffects) {
            return false;
        }
        return itemId.equals(normalizeIdOrEmpty(expectedBottleItemId))
                && potionId.equals(normalizeIdOrEmpty(expectedPotionId));
    }

    public boolean canStackWith(BrewItemSnapshot other) {
        if (other == null || isEmpty() || other.isEmpty()) {
            return false;
        }
        return maxStackSize > 1
                && other.maxStackSize > 1
                && itemId.equals(other.itemId)
                && potionId.equals(other.potionId)
                && hasCustomEffects == other.hasCustomEffects;
    }

    public BrewItemSnapshot withCount(int newCount) {
        if (newCount <= 0 || isEmpty()) {
            return EMPTY;
        }
        return new BrewItemSnapshot(itemId, newCount, maxStackSize, potionId, hasCustomEffects);
    }

    public String describe() {
        if (isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder(itemId);
        if (!potionId.isEmpty() || hasCustomEffects) {
            sb.append('[');
            if (!potionId.isEmpty()) {
                sb.append(potionId);
            }
            if (hasCustomEffects) {
                if (!potionId.isEmpty()) sb.append(',');
                sb.append("custom_effects");
            }
            sb.append(']');
        }
        sb.append(" x").append(count);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BrewItemSnapshot that)) return false;
        return count == that.count
                && maxStackSize == that.maxStackSize
                && hasCustomEffects == that.hasCustomEffects
                && Objects.equals(itemId, that.itemId)
                && Objects.equals(potionId, that.potionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, count, maxStackSize, potionId, hasCustomEffects);
    }

    @Override
    public String toString() {
        return describe();
    }
}
