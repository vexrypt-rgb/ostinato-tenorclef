package adris.altoclef.compose;

import java.util.Locale;
import java.util.Objects;

/**
 * One semantic container-transfer step inferred from a player demonstration, or an explicitly
 * {@link Action#UNRESOLVED} observation that must be edited/confirmed before saving or replaying.
 * <p>
 * Intentionally stores only semantic intent (action, item ID, quantity, named station role) —
 * never raw slot indices or hardcoded world coordinates.
 */
public final class DemoStep {

    public enum Action {
        WITHDRAW,
        DEPOSIT,
        UNRESOLVED
    }

    private final Action action;
    private final String itemId;
    private final int count;
    private final String stationName;
    private final String stationKey;
    private final boolean resolved;
    private final String ambiguityReason;
    private final String observedBlockType;

    private DemoStep(Action action,
                     String itemId,
                     int count,
                     String stationName,
                     boolean resolved,
                     String ambiguityReason,
                     String observedBlockType) {
        this.action = Objects.requireNonNull(action, "action");
        this.itemId = normalizeItemId(itemId);
        this.count = Math.max(0, count);
        this.stationName = normalizeStationDisplay(stationName);
        this.stationKey = this.stationName.isEmpty() ? "" : normalizeStationKey(this.stationName);
        this.resolved = resolved;
        this.ambiguityReason = ambiguityReason == null ? "" : ambiguityReason.trim();
        this.observedBlockType = observedBlockType == null ? "" : observedBlockType.trim().toLowerCase(Locale.ROOT);
        if (this.resolved) {
            if (this.action == Action.UNRESOLVED) {
                throw new IllegalArgumentException("Resolved step cannot have UNRESOLVED action");
            }
            if (this.itemId.isEmpty()) {
                throw new IllegalArgumentException("Resolved step requires a non-empty item id");
            }
            if (this.count <= 0) {
                throw new IllegalArgumentException("Resolved step requires count > 0, got " + count);
            }
            if (this.stationKey.isEmpty()) {
                throw new IllegalArgumentException("Resolved step requires a non-empty station name");
            }
        }
    }

    public static DemoStep withdraw(String itemId, int count, String stationName, String observedBlockType) {
        return new DemoStep(Action.WITHDRAW, itemId, count, stationName, true, "", observedBlockType);
    }

    public static DemoStep deposit(String itemId, int count, String stationName, String observedBlockType) {
        return new DemoStep(Action.DEPOSIT, itemId, count, stationName, true, "", observedBlockType);
    }

    public static DemoStep resolved(Action action, String itemId, int count, String stationName, String observedBlockType) {
        if (action == null || action == Action.UNRESOLVED) {
            throw new IllegalArgumentException("Expected WITHDRAW or DEPOSIT, got " + action);
        }
        return new DemoStep(action, itemId, count, stationName, true, "", observedBlockType);
    }

    public static DemoStep unresolved(String stationName, String observedBlockType, String reason) {
        String why = (reason == null || reason.isBlank()) ? "Ambiguous demonstration step" : reason.trim();
        return new DemoStep(Action.UNRESOLVED, "", 0, stationName, false, why, observedBlockType);
    }

    /**
     * Creates an explicitly unresolved step that keeps best-effort candidate fields visible for editing,
     * while remaining unresolved until the player explicitly edits/confirms it.
     */
    public static DemoStep unresolvedCandidate(Action suggestedAction,
                                               String suggestedItemId,
                                               int suggestedCount,
                                               String stationName,
                                               String observedBlockType,
                                               String reason) {
        Action act = suggestedAction == null ? Action.UNRESOLVED : suggestedAction;
        String why = (reason == null || reason.isBlank()) ? "Ambiguous demonstration step" : reason.trim();
        return new DemoStep(act, suggestedItemId, suggestedCount, stationName, false, why, observedBlockType);
    }

    public static String normalizeItemId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return "air".equals(s) ? "" : s;
    }

    public static String normalizeStationDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * Canonical lookup key for a station role name so {@code "serving barrel"}, {@code "Serving Barrel"},
     * {@code "serving_barrel"}, and {@code "serving-barrel"} all bind cleanly to the same role.
     */
    public static String normalizeStationKey(String raw) {
        String display = normalizeStationDisplay(raw);
        if (display.isEmpty()) {
            return "";
        }
        String key = display.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                throw new IllegalArgumentException("Invalid station name '" + raw + "' (use letters, digits, spaces, _, -)");
            }
        }
        return key;
    }

    public Action action() {
        return action;
    }

    public String itemId() {
        return itemId;
    }

    public int count() {
        return count;
    }

    public String stationName() {
        return stationName;
    }

    public String stationKey() {
        return stationKey;
    }

    public boolean resolved() {
        return resolved;
    }

    public String ambiguityReason() {
        return ambiguityReason;
    }

    public String observedBlockType() {
        return observedBlockType;
    }

    /** Renames the station role while preserving resolved/unresolved status. */
    public DemoStep withStation(String newStationName) {
        String display = normalizeStationDisplay(newStationName);
        if (display.isEmpty()) {
            throw new IllegalArgumentException("Station name must not be blank");
        }
        normalizeStationKey(display);
        return new DemoStep(action, itemId, count, display, resolved, ambiguityReason, observedBlockType);
    }

    /**
     * Edits any non-null/positive fields. Passing {@code confirm == true} resolves an unresolved step
     * only if action, itemId, count, and stationName are all valid.
     */
    public DemoStep withEdit(Action newAction,
                             String newItemId,
                             Integer newCount,
                             String newStationName,
                             boolean confirm) {
        Action nextAction = newAction != null ? newAction : this.action;
        String nextItem = newItemId != null ? normalizeItemId(newItemId) : this.itemId;
        int nextCount = newCount != null ? newCount : this.count;
        String nextStation = newStationName != null ? normalizeStationDisplay(newStationName) : this.stationName;
        if (!nextStation.isEmpty()) {
            normalizeStationKey(nextStation);
        }
        boolean nextResolved = this.resolved || confirm;
        String nextReason = nextResolved ? "" : this.ambiguityReason;
        if (nextResolved) {
            if (nextAction == Action.UNRESOLVED) {
                throw new IllegalArgumentException("Cannot confirm step without action (withdraw or deposit)");
            }
            if (nextItem.isEmpty()) {
                throw new IllegalArgumentException("Cannot confirm step without a valid item id");
            }
            if (nextCount <= 0) {
                throw new IllegalArgumentException("Cannot confirm step without count > 0");
            }
            if (nextStation.isEmpty()) {
                throw new IllegalArgumentException("Cannot confirm step without a station name");
            }
        }
        return new DemoStep(nextAction, nextItem, nextCount, nextStation, nextResolved, nextReason, observedBlockType);
    }

    public String describeShort() {
        if (!resolved) {
            String where = stationName.isEmpty() ? "unknown station" : "\"" + stationName + "\"";
            return "[UNRESOLVED] at " + where;
        }
        String prep = action == Action.WITHDRAW ? "from" : "to";
        return action.name() + " " + count + "x " + itemId + " " + prep + " \"" + stationName + "\"";
    }

    public String formatSummaryLine(int oneBasedIndex) {
        if (resolved) {
            return oneBasedIndex + ". " + describeShort();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(oneBasedIndex).append(". [UNRESOLVED]");
        if (!stationName.isEmpty()) {
            sb.append(" at \"").append(stationName).append("\"");
        }
        if (action != Action.UNRESOLVED && !itemId.isEmpty() && count > 0) {
            sb.append(" (candidate: ").append(action.name()).append(" ").append(count).append("x ").append(itemId).append(")");
        }
        if (!ambiguityReason.isEmpty()) {
            sb.append(" — ").append(ambiguityReason);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DemoStep that)) return false;
        return count == that.count
                && resolved == that.resolved
                && action == that.action
                && itemId.equals(that.itemId)
                && stationName.equals(that.stationName)
                && stationKey.equals(that.stationKey)
                && ambiguityReason.equals(that.ambiguityReason)
                && observedBlockType.equals(that.observedBlockType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, itemId, count, stationName, stationKey, resolved, ambiguityReason, observedBlockType);
    }

    @Override
    public String toString() {
        return describeShort();
    }
}
