package adris.altoclef.tasks.trading;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java snapshot of observable player inventory state for trade planning and post-trade reconciliation.
 */
public final class TradeInventorySnapshot {

    private final int matchingTargetInInventory;
    private final int matchingTargetInCursor;
    private final int totalEmeraldsOwned;
    private final Map<String, Integer> ownedItemCounts;
    private final boolean hasInventorySpaceForOutput;
    private final boolean matchingItemDroppedNearby;

    public TradeInventorySnapshot(int matchingTargetInInventory,
                                  int matchingTargetInCursor,
                                  int totalEmeraldsOwned,
                                  Map<String, Integer> ownedItemCounts,
                                  boolean hasInventorySpaceForOutput,
                                  boolean matchingItemDroppedNearby) {
        this.matchingTargetInInventory = Math.max(0, matchingTargetInInventory);
        this.matchingTargetInCursor = Math.max(0, matchingTargetInCursor);
        this.totalEmeraldsOwned = Math.max(0, totalEmeraldsOwned);
        if (ownedItemCounts == null || ownedItemCounts.isEmpty()) {
            this.ownedItemCounts = Collections.emptyMap();
        } else {
            Map<String, Integer> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : ownedItemCounts.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    String k = e.getKey().trim().toLowerCase(Locale.ROOT);
                    if (k.indexOf(':') < 0) k = "minecraft:" + k;
                    copy.merge(k, e.getValue(), Integer::sum);
                }
            }
            this.ownedItemCounts = Collections.unmodifiableMap(copy);
        }
        this.hasInventorySpaceForOutput = hasInventorySpaceForOutput;
        this.matchingItemDroppedNearby = matchingItemDroppedNearby;
    }

    /** Exact matching target stacks strictly inside player inventory (excluding cursor and merchant output slot). */
    public int matchingTargetInInventory() {
        return matchingTargetInInventory;
    }

    /** Exact matching target stacks currently held on the player cursor. */
    public int matchingTargetInCursor() {
        return matchingTargetInCursor;
    }

    /** Total player-owned emeralds across player inventory, cursor, and open merchant payment slots. */
    public int totalEmeraldsOwned() {
        return totalEmeraldsOwned;
    }

    public int countOwnedItem(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return 0;
        }
        String k = itemId.trim().toLowerCase(Locale.ROOT);
        if (k.indexOf(':') < 0) k = "minecraft:" + k;
        if (TradeOfferSnapshot.EMERALD_ID.equals(k)) {
            return Math.max(totalEmeraldsOwned, ownedItemCounts.getOrDefault(k, 0));
        }
        return ownedItemCounts.getOrDefault(k, 0);
    }

    public boolean hasInventorySpaceForOutput() {
        return hasInventorySpaceForOutput;
    }

    public boolean matchingItemDroppedNearby() {
        return matchingItemDroppedNearby;
    }
}
