package adris.altoclef.tasks.replenish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable pure-Java snapshot of an open vanilla chest/container screen (container slots,
 * player inventory slots, and cursor stack). Zero Minecraft imports so {@link ContainerTransferPlanner}
 * is exhaustively unit-testable with realistic vanilla click simulation.
 */
public final class ContainerSlotSnapshot {

    public record SlotEntry(int windowSlot, boolean container, String itemId, int count, int maxStackSize) {
        public SlotEntry {
            itemId = normalizeOrEmpty(itemId);
            count = itemId.isEmpty() ? 0 : Math.max(0, count);
            maxStackSize = Math.max(1, maxStackSize);
            if (count == 0) {
                itemId = "";
            }
        }

        public boolean isEmpty() {
            return count <= 0 || itemId.isEmpty();
        }

        public boolean matches(String targetItemId) {
            return !isEmpty() && itemId.equals(targetItemId);
        }

        public int freeSpaceFor(String targetItemId, int targetMaxStack) {
            if (isEmpty()) {
                return Math.max(1, targetMaxStack);
            }
            if (itemId.equals(targetItemId)) {
                int cap = Math.min(maxStackSize, Math.max(1, targetMaxStack));
                return Math.max(0, cap - count);
            }
            return 0;
        }
    }

    private final int containerSlotCount;
    private final List<SlotEntry> slots;
    private final String cursorItemId;
    private final int cursorCount;
    private final int cursorMaxStack;

    public ContainerSlotSnapshot(int containerSlotCount,
                                 List<SlotEntry> slots,
                                 String cursorItemId,
                                 int cursorCount,
                                 int cursorMaxStack) {
        if (containerSlotCount < 0) {
            throw new IllegalArgumentException("containerSlotCount must be >= 0");
        }
        this.containerSlotCount = containerSlotCount;
        this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
        String normCursor = normalizeOrEmpty(cursorItemId);
        this.cursorCount = normCursor.isEmpty() ? 0 : Math.max(0, cursorCount);
        this.cursorItemId = this.cursorCount == 0 ? "" : normCursor;
        this.cursorMaxStack = Math.max(1, cursorMaxStack);
    }

    public static String normalizeOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return "air".equals(s) ? "" : s;
    }

    public int containerSlotCount() {
        return containerSlotCount;
    }

    public List<SlotEntry> slots() {
        return slots;
    }

    public String cursorItemId() {
        return cursorItemId;
    }

    public int cursorCount() {
        return cursorCount;
    }

    public int cursorMaxStack() {
        return cursorMaxStack;
    }

    public boolean cursorEmpty() {
        return cursorCount <= 0 || cursorItemId.isEmpty();
    }

    public boolean cursorMatches(String itemId) {
        return !cursorEmpty() && cursorItemId.equals(normalizeOrEmpty(itemId));
    }

    public int countInContainer(String itemId) {
        String id = normalizeOrEmpty(itemId);
        int total = 0;
        for (SlotEntry s : slots) {
            if (s.container() && s.matches(id)) {
                total += s.count();
            }
        }
        return total;
    }

    public int countInPlayer(String itemId) {
        String id = normalizeOrEmpty(itemId);
        int total = 0;
        for (SlotEntry s : slots) {
            if (!s.container() && s.matches(id)) {
                total += s.count();
            }
        }
        return total;
    }

    public int freeCapacityInPlayer(String itemId, int maxStackSize) {
        String id = normalizeOrEmpty(itemId);
        int cap = 0;
        for (SlotEntry s : slots) {
            if (!s.container()) {
                cap += s.freeSpaceFor(id, maxStackSize);
            }
        }
        return cap;
    }

    public int freeCapacityInContainer(String itemId, int maxStackSize) {
        String id = normalizeOrEmpty(itemId);
        int cap = 0;
        for (SlotEntry s : slots) {
            if (s.container()) {
                cap += s.freeSpaceFor(id, maxStackSize);
            }
        }
        return cap;
    }

    /**
     * Simulates a vanilla Minecraft container click ({@code PICKUP} left/right or {@code QUICK_MOVE})
     * for pure unit testing.
     */
    public ContainerSlotSnapshot simulate(ContainerTransferPlanner.ClickAction action) {
        if (action == null) {
            return this;
        }
        List<SlotEntry> next = new ArrayList<>(slots);
        int idx = -1;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).windowSlot() == action.windowSlot()) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException("Unknown windowSlot " + action.windowSlot());
        }
        SlotEntry target = next.get(idx);
        String cItem = this.cursorItemId;
        int cCount = this.cursorCount;
        int cMax = this.cursorMaxStack;

        if (action.kind() == ContainerTransferPlanner.ClickKind.QUICK_MOVE) {
            if (!target.isEmpty()) {
                boolean toContainer = !target.container();
                int rem = target.count();
                // Vanilla quick-move fills existing partial stacks first, then empty slots.
                // For chest -> player, vanilla fills hotbar/inventory in reverse order; order does not affect totals.
                for (int pass = 0; pass < 2 && rem > 0; pass++) {
                    for (int i = 0; i < next.size() && rem > 0; i++) {
                        SlotEntry dest = next.get(i);
                        if (dest.container() != toContainer) continue;
                        if (pass == 0 && !dest.matches(target.itemId())) continue;
                        if (pass == 1 && !dest.isEmpty()) continue;
                        int space = dest.freeSpaceFor(target.itemId(), target.maxStackSize());
                        if (space <= 0) continue;
                        int add = Math.min(rem, space);
                        next.set(i, new SlotEntry(dest.windowSlot(), dest.container(),
                                target.itemId(), dest.count() + add, target.maxStackSize()));
                        rem -= add;
                    }
                }
                next.set(idx, new SlotEntry(target.windowSlot(), target.container(),
                        rem > 0 ? target.itemId() : "", rem, target.maxStackSize()));
            }
        } else {
            // PICKUP: button 0 = left click, button 1 = right click
            if (action.button() == 0) {
                if (cCount == 0) {
                    if (!target.isEmpty()) {
                        cItem = target.itemId();
                        cCount = target.count();
                        cMax = target.maxStackSize();
                        next.set(idx, new SlotEntry(target.windowSlot(), target.container(), "", 0, 64));
                    }
                } else {
                    if (target.isEmpty()) {
                        next.set(idx, new SlotEntry(target.windowSlot(), target.container(), cItem, cCount, cMax));
                        cItem = "";
                        cCount = 0;
                    } else if (target.itemId().equals(cItem)) {
                        int cap = Math.min(target.maxStackSize(), cMax);
                        int add = Math.min(cCount, Math.max(0, cap - target.count()));
                        next.set(idx, new SlotEntry(target.windowSlot(), target.container(), cItem, target.count() + add, cap));
                        cCount -= add;
                        if (cCount <= 0) {
                            cItem = "";
                            cCount = 0;
                        }
                    } else {
                        // Swap cursor and slot
                        next.set(idx, new SlotEntry(target.windowSlot(), target.container(), cItem, cCount, cMax));
                        cItem = target.itemId();
                        cCount = target.count();
                        cMax = target.maxStackSize();
                    }
                }
            } else if (action.button() == 1) {
                if (cCount == 0) {
                    if (!target.isEmpty()) {
                        int take = (target.count() + 1) / 2; // vanilla ceil(count / 2)
                        int leave = target.count() - take;
                        cItem = target.itemId();
                        cCount = take;
                        cMax = target.maxStackSize();
                        next.set(idx, new SlotEntry(target.windowSlot(), target.container(),
                                leave > 0 ? target.itemId() : "", leave, target.maxStackSize()));
                    }
                } else {
                    if (target.isEmpty()) {
                        next.set(idx, new SlotEntry(target.windowSlot(), target.container(), cItem, 1, cMax));
                        cCount--;
                        if (cCount <= 0) {
                            cItem = "";
                            cCount = 0;
                        }
                    } else if (target.itemId().equals(cItem)) {
                        int cap = Math.min(target.maxStackSize(), cMax);
                        if (target.count() < cap) {
                            next.set(idx, new SlotEntry(target.windowSlot(), target.container(), cItem, target.count() + 1, cap));
                            cCount--;
                            if (cCount <= 0) {
                                cItem = "";
                                cCount = 0;
                            }
                        }
                    } else {
                        // Swap on right-click onto mismatched stack
                        next.set(idx, new SlotEntry(target.windowSlot(), target.container(), cItem, cCount, cMax));
                        cItem = target.itemId();
                        cCount = target.count();
                        cMax = target.maxStackSize();
                    }
                }
            }
        }
        return new ContainerSlotSnapshot(containerSlotCount, next, cItem, cCount, cMax);
    }
}
