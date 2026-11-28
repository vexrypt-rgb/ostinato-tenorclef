package adris.altoclef.tasks.replenish;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure policy specification for a single-item, single-source, single-destination,
 * single-courier worksite replenishment rule.
 * <p>
 * Example: "When the quarry has fewer than 32 torches, refill it to 64. Leave at least 16 at home."
 */
public final class ReplenishmentRule {

    public static final long DEFAULT_POLL_INTERVAL_MS = 10_000L;
    public static final long MIN_POLL_INTERVAL_MS = 500L;
    public static final int DEFAULT_MAX_STACK_SIZE = 64;

    private final String itemId;
    private final String sourceName;
    private final String destinationName;
    private final int triggerBelow;
    private final int targetCount;
    private final int sourceReserve;
    private final int maxStackSize;
    private final long pollIntervalMs;

    public ReplenishmentRule(String itemId,
                             String sourceName,
                             String destinationName,
                             int triggerBelow,
                             int targetCount,
                             int sourceReserve) {
        this(itemId, sourceName, destinationName, triggerBelow, targetCount, sourceReserve,
                DEFAULT_MAX_STACK_SIZE, DEFAULT_POLL_INTERVAL_MS);
    }

    public ReplenishmentRule(String itemId,
                             String sourceName,
                             String destinationName,
                             int triggerBelow,
                             int targetCount,
                             int sourceReserve,
                             int maxStackSize,
                             long pollIntervalMs) {
        this.itemId = normalizeItemId(itemId);
        this.sourceName = normalizeChestName(sourceName, "source");
        this.destinationName = normalizeChestName(destinationName, "destination");
        if (this.sourceName.equals(this.destinationName)) {
            throw new IllegalArgumentException("Source and destination chest names must differ: " + this.sourceName);
        }
        if (triggerBelow < 1) {
            throw new IllegalArgumentException("triggerBelow must be >= 1, got " + triggerBelow);
        }
        if (targetCount < triggerBelow) {
            throw new IllegalArgumentException("targetCount (" + targetCount + ") must be >= triggerBelow (" + triggerBelow + ")");
        }
        if (sourceReserve < 0) {
            throw new IllegalArgumentException("sourceReserve must be >= 0, got " + sourceReserve);
        }
        if (maxStackSize < 1 || maxStackSize > 99) {
            throw new IllegalArgumentException("maxStackSize must be 1..99, got " + maxStackSize);
        }
        this.triggerBelow = triggerBelow;
        this.targetCount = targetCount;
        this.sourceReserve = sourceReserve;
        this.maxStackSize = maxStackSize;
        this.pollIntervalMs = Math.max(MIN_POLL_INTERVAL_MS, pollIntervalMs);
    }

    public static String normalizeItemId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("item id must not be blank");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("item id must not be empty");
        }
        return s;
    }

    public static String normalizeChestName(String raw, String role) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(role + " chest name must not be blank");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                throw new IllegalArgumentException("Invalid " + role + " chest name '" + raw + "' (use letters, digits, _, -)");
            }
        }
        return s;
    }

    public String itemId() {
        return itemId;
    }

    public String sourceName() {
        return sourceName;
    }

    public String destinationName() {
        return destinationName;
    }

    public int triggerBelow() {
        return triggerBelow;
    }

    public int targetCount() {
        return targetCount;
    }

    public int sourceReserve() {
        return sourceReserve;
    }

    public int maxStackSize() {
        return maxStackSize;
    }

    public long pollIntervalMs() {
        return pollIntervalMs;
    }

    public ReplenishmentRule withMaxStackSize(int stackSize) {
        if (stackSize == this.maxStackSize) {
            return this;
        }
        return new ReplenishmentRule(itemId, sourceName, destinationName, triggerBelow, targetCount,
                sourceReserve, stackSize, pollIntervalMs);
    }

    public ReplenishmentRule withPollIntervalMs(long intervalMs) {
        return new ReplenishmentRule(itemId, sourceName, destinationName, triggerBelow, targetCount,
                sourceReserve, maxStackSize, intervalMs);
    }

    /** Stable identity key for journal persistence and restart reconciliation. */
    public String ruleKey() {
        return itemId + "|" + sourceName + "->" + destinationName;
    }

    /** Triggers strictly when destination count is below {@link #triggerBelow}. */
    public boolean shouldTrigger(int destinationCount) {
        return destinationCount < triggerBelow;
    }

    /** Remaining deficit to reach {@link #targetCount}. */
    public int deficit(int destinationCount) {
        return Math.max(0, targetCount - Math.max(0, destinationCount));
    }

    /** Source surplus strictly above {@link #sourceReserve}. */
    public int sourceSurplus(int sourceCount) {
        return Math.max(0, Math.max(0, sourceCount) - sourceReserve);
    }

    /**
     * Exact withdrawal cap at an open source chest:
     * {@code min(remainingDeficit, freeInventoryCapacity, sourceSurplusAboveReserve)}.
     */
    public int withdrawBound(int remainingDeficit, int freeInventoryCapacity, int sourceCount) {
        int want = Math.max(0, remainingDeficit);
        int free = Math.max(0, freeInventoryCapacity);
        int surplus = sourceSurplus(sourceCount);
        return Math.min(want, Math.min(free, surplus));
    }

    public String describe() {
        return itemId + " " + sourceName + "->" + destinationName
                + " below=" + triggerBelow + " target=" + targetCount + " reserve=" + sourceReserve;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReplenishmentRule that)) return false;
        return triggerBelow == that.triggerBelow
                && targetCount == that.targetCount
                && sourceReserve == that.sourceReserve
                && maxStackSize == that.maxStackSize
                && pollIntervalMs == that.pollIntervalMs
                && itemId.equals(that.itemId)
                && sourceName.equals(that.sourceName)
                && destinationName.equals(that.destinationName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, sourceName, destinationName, triggerBelow, targetCount,
                sourceReserve, maxStackSize, pollIntervalMs);
    }

    @Override
    public String toString() {
        return "ReplenishmentRule[" + describe() + "]";
    }
}
