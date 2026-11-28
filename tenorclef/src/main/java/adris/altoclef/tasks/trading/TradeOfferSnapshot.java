package adris.altoclef.tasks.trading;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java snapshot of a single merchant trade offer as visible on the client merchant screen.
 */
public final class TradeOfferSnapshot {

    public static final String EMERALD_ID = "minecraft:emerald";

    private final int offerIndex;
    private final String costAItemId;
    private final int costACount;
    private final boolean costAComponentsSatisfied;
    private final String costBItemId;
    private final int costBCount;
    private final boolean costBComponentsSatisfied;
    private final String outputItemId;
    private final int outputCount;
    private final Map<String, Integer> outputEnchantments;
    private final int uses;
    private final int maxUses;
    private final boolean outOfStock;

    public TradeOfferSnapshot(int offerIndex,
                              String costAItemId,
                              int costACount,
                              boolean costAComponentsSatisfied,
                              String costBItemId,
                              int costBCount,
                              boolean costBComponentsSatisfied,
                              String outputItemId,
                              int outputCount,
                              Map<String, Integer> outputEnchantments,
                              int uses,
                              int maxUses,
                              boolean outOfStock) {
        this.offerIndex = offerIndex;
        this.costAItemId = normalizeOptional(costAItemId);
        this.costACount = Math.max(0, costACount);
        this.costAComponentsSatisfied = costAComponentsSatisfied;
        this.costBItemId = normalizeOptional(costBItemId);
        this.costBCount = Math.max(0, costBCount);
        this.costBComponentsSatisfied = costBComponentsSatisfied;
        this.outputItemId = normalizeOptional(outputItemId);
        this.outputCount = Math.max(0, outputCount);
        if (outputEnchantments == null || outputEnchantments.isEmpty()) {
            this.outputEnchantments = Collections.emptyMap();
        } else {
            Map<String, Integer> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : outputEnchantments.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    copy.put(normalizeOptional(e.getKey()), e.getValue());
                }
            }
            this.outputEnchantments = Collections.unmodifiableMap(copy);
        }
        this.uses = Math.max(0, uses);
        this.maxUses = Math.max(0, maxUses);
        this.outOfStock = outOfStock;
    }

    private static String normalizeOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    public int offerIndex() {
        return offerIndex;
    }

    public String costAItemId() {
        return costAItemId;
    }

    public int costACount() {
        return costACount;
    }

    public boolean costAComponentsSatisfied() {
        return costAComponentsSatisfied;
    }

    public String costBItemId() {
        return costBItemId;
    }

    public int costBCount() {
        return costBCount;
    }

    public boolean hasCostB() {
        return !costBItemId.isEmpty() && costBCount > 0;
    }

    public boolean costBComponentsSatisfied() {
        return !hasCostB() || costBComponentsSatisfied;
    }

    public String outputItemId() {
        return outputItemId;
    }

    public int outputCount() {
        return outputCount;
    }

    public Map<String, Integer> outputEnchantments() {
        return outputEnchantments;
    }

    public int uses() {
        return uses;
    }

    public int maxUses() {
        return maxUses;
    }

    public boolean outOfStock() {
        return outOfStock;
    }

    public boolean hasRemainingUses() {
        return !outOfStock && uses < maxUses;
    }

    public int remainingUses() {
        return outOfStock ? 0 : Math.max(0, maxUses - uses);
    }

    /** Total emerald cost of one execution of this offer (across costA and costB). */
    public int emeraldCost() {
        int total = 0;
        if (EMERALD_ID.equals(costAItemId)) {
            total += costACount;
        }
        if (hasCostB() && EMERALD_ID.equals(costBItemId)) {
            total += costBCount;
        }
        return total;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TradeOfferSnapshot that)) return false;
        return offerIndex == that.offerIndex
                && costACount == that.costACount
                && costAComponentsSatisfied == that.costAComponentsSatisfied
                && costBCount == that.costBCount
                && costBComponentsSatisfied == that.costBComponentsSatisfied
                && outputCount == that.outputCount
                && uses == that.uses
                && maxUses == that.maxUses
                && outOfStock == that.outOfStock
                && Objects.equals(costAItemId, that.costAItemId)
                && Objects.equals(costBItemId, that.costBItemId)
                && Objects.equals(outputItemId, that.outputItemId)
                && Objects.equals(outputEnchantments, that.outputEnchantments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(offerIndex, costAItemId, costACount, costAComponentsSatisfied,
                costBItemId, costBCount, costBComponentsSatisfied, outputItemId, outputCount,
                outputEnchantments, uses, maxUses, outOfStock);
    }

    @Override
    public String toString() {
        return "Offer#" + offerIndex + "[" + costAItemId + " x" + costACount
                + (hasCostB() ? " + " + costBItemId + " x" + costBCount : "")
                + " -> " + outputItemId + " x" + outputCount
                + (outputEnchantments.isEmpty() ? "" : " " + outputEnchantments)
                + ", uses=" + uses + "/" + maxUses + (outOfStock ? " SOLD_OUT" : "") + "]";
    }
}
