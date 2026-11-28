package adris.altoclef.tasks.trading;

import adris.altoclef.util.ItemComponentSpec;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable specification of a player villager-trading acquisition order.
 * Pure Java (no Minecraft runtime dependency).
 */
public final class VillagerTradeRequest {

    public static final int UNCAPPED_EMERALDS = -1;

    private final String outputItemId;
    private final ItemComponentSpec componentSpec;
    private final int targetCount;
    private final int maxEmeralds;
    private final String preferredMerchantId;

    public VillagerTradeRequest(String outputItemId,
                                ItemComponentSpec componentSpec,
                                int targetCount,
                                int maxEmeralds,
                                String preferredMerchantId) {
        if (outputItemId == null || outputItemId.isBlank()) {
            throw new IllegalArgumentException("outputItemId is required");
        }
        if (targetCount < 1) {
            throw new IllegalArgumentException("targetCount must be >= 1, got " + targetCount);
        }
        if (maxEmeralds < UNCAPPED_EMERALDS) {
            throw new IllegalArgumentException("maxEmeralds must be >= 0 or -1 (uncapped), got " + maxEmeralds);
        }
        this.outputItemId = normalizeItemId(outputItemId);
        this.componentSpec = componentSpec == null ? ItemComponentSpec.EMPTY : componentSpec;
        this.targetCount = targetCount;
        this.maxEmeralds = maxEmeralds;
        this.preferredMerchantId = (preferredMerchantId == null || preferredMerchantId.isBlank())
                ? null : preferredMerchantId.trim();
    }

    public static String normalizeItemId(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    public String outputItemId() {
        return outputItemId;
    }

    public ItemComponentSpec componentSpec() {
        return componentSpec;
    }

    public int targetCount() {
        return targetCount;
    }

    public int maxEmeralds() {
        return maxEmeralds;
    }

    public boolean hasEmeraldCap() {
        return maxEmeralds >= 0;
    }

    public String preferredMerchantId() {
        return preferredMerchantId;
    }

    public boolean hasPreferredMerchant() {
        return preferredMerchantId != null;
    }

    public String describeTarget() {
        String base = outputItemId.startsWith("minecraft:")
                ? outputItemId.substring("minecraft:".length()) : outputItemId;
        String spec = componentSpec.isEmpty() ? "" : componentSpec.formatBraceSpec();
        String budget = hasEmeraldCap() ? " (max_emeralds=" + maxEmeralds + ")" : "";
        return base + spec + " x" + targetCount + budget;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VillagerTradeRequest that)) return false;
        return targetCount == that.targetCount
                && maxEmeralds == that.maxEmeralds
                && Objects.equals(outputItemId, that.outputItemId)
                && Objects.equals(componentSpec, that.componentSpec)
                && Objects.equals(preferredMerchantId, that.preferredMerchantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outputItemId, componentSpec, targetCount, maxEmeralds, preferredMerchantId);
    }

    @Override
    public String toString() {
        return describeTarget();
    }
}
