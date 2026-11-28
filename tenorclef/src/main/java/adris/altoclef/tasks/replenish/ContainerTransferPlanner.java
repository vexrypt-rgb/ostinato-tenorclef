package adris.altoclef.tasks.replenish;

import adris.altoclef.tasks.replenish.ContainerSlotSnapshot.SlotEntry;

/**
 * Pure vanilla container click planner for exact-count withdrawals (respecting a source reserve)
 * and exact-count deposits across arbitrary split/partial stacks in both chest and player inventory.
 * <p>
 * Every planned click is an ordinary player mouse click ({@code PICKUP} left/right or {@code QUICK_MOVE})
 * computed fresh from the live {@link ContainerSlotSnapshot}.
 */
public final class ContainerTransferPlanner {

    public enum ClickKind {
        PICKUP,
        QUICK_MOVE
    }

    public record ClickAction(int windowSlot, int button, ClickKind kind, String reason) {
        public static ClickAction leftPickup(int slot, String reason) {
            return new ClickAction(slot, 0, ClickKind.PICKUP, reason);
        }

        public static ClickAction rightPickup(int slot, String reason) {
            return new ClickAction(slot, 1, ClickKind.PICKUP, reason);
        }

        public static ClickAction quickMove(int slot, String reason) {
            return new ClickAction(slot, 0, ClickKind.QUICK_MOVE, reason);
        }
    }

    private ContainerTransferPlanner() {
    }

    /**
     * Plans the next single vanilla click to withdraw up to {@code remainingToWithdraw} items of {@code itemId}
     * from the open container into the player's inventory without letting the container end below {@code sourceReserve}.
     *
     * @param snap                live snapshot of the open container screen
     * @param rawItemId           target item id (e.g. {@code "torch"})
     * @param maxStackSize        item max stack size (e.g. 64)
     * @param remainingToWithdraw how many MORE items must land in player inventory slots (excluding cursor)
     * @param sourceReserve       minimum count that must remain in the source container
     * @return next click to execute, or {@code null} when transfer is complete (with empty cursor) or impossible
     */
    public static ClickAction planNextWithdrawClick(ContainerSlotSnapshot snap,
                                                    String rawItemId,
                                                    int maxStackSize,
                                                    int remainingToWithdraw,
                                                    int sourceReserve) {
        String itemId = ContainerSlotSnapshot.normalizeOrEmpty(rawItemId);
        int reserve = Math.max(0, sourceReserve);
        int need = Math.max(0, remainingToWithdraw);

        // 1. Always resolve any item currently held on the cursor first.
        if (!snap.cursorEmpty()) {
            if (!snap.cursorMatches(itemId)) {
                return placeCursorInSide(snap, false, snap.cursorItemId(), snap.cursorMaxStack(), "park-unrelated-cursor");
            }
            int chestNow = snap.countInContainer(itemId);
            int maxAllowedForPlayer = Math.max(0, (chestNow + snap.cursorCount()) - reserve);
            int playerFree = snap.freeCapacityInPlayer(itemId, maxStackSize);
            int keepForPlayer = Math.min(snap.cursorCount(), Math.min(need, Math.min(playerFree, maxAllowedForPlayer)));
            int returnToChest = snap.cursorCount() - keepForPlayer;

            if (returnToChest <= 0) {
                return placeCursorInSide(snap, false, itemId, maxStackSize, "place-withdrawn-cursor");
            }
            if (keepForPlayer <= 0) {
                return placeCursorInSide(snap, true, itemId, maxStackSize, "return-excess-cursor-to-source");
            }
            // Minimize clicks: drop single items onto whichever side needs fewer individual placements,
            // then bulk left-click the remainder onto the other side on the following step.
            if (keepForPlayer <= returnToChest) {
                int playerSlot = findBestPlacementSlot(snap, false, itemId, maxStackSize, 1);
                if (playerSlot >= 0) {
                    return ClickAction.rightPickup(playerSlot, "drop-1-to-player");
                }
                return placeCursorInSide(snap, true, itemId, maxStackSize, "return-cursor-player-full");
            } else {
                int chestSlot = findBestPlacementSlot(snap, true, itemId, maxStackSize, 1);
                if (chestSlot >= 0) {
                    return ClickAction.rightPickup(chestSlot, "return-1-to-source");
                }
                return placeCursorInSide(snap, false, itemId, maxStackSize, "place-cursor-source-full");
            }
        }

        // 2. Cursor is empty: compute exact safe cap from live screen state.
        int chestNow = snap.countInContainer(itemId);
        int surplus = Math.max(0, chestNow - reserve);
        int playerFree = snap.freeCapacityInPlayer(itemId, maxStackSize);
        int safeCap = Math.min(need, Math.min(playerFree, surplus));
        if (safeCap <= 0) {
            return null;
        }

        // 3. Prefer largest whole container stack that fits cleanly within safeCap (1 click via QUICK_MOVE).
        SlotEntry bestWhole = null;
        for (SlotEntry s : snap.slots()) {
            if (!s.container() || !s.matches(itemId)) continue;
            if (s.count() <= safeCap) {
                if (bestWhole == null || s.count() > bestWhole.count()) {
                    bestWhole = s;
                }
            }
        }
        if (bestWhole != null) {
            return ClickAction.quickMove(bestWhole.windowSlot(), "quick-move-whole-withdraw-" + bestWhole.count());
        }

        // 4. Every remaining matching container stack exceeds safeCap: split the smallest stack via right-click (ceil(n/2)).
        SlotEntry smallestOver = null;
        for (SlotEntry s : snap.slots()) {
            if (!s.container() || !s.matches(itemId)) continue;
            if (smallestOver == null || s.count() < smallestOver.count()) {
                smallestOver = s;
            }
        }
        if (smallestOver == null) {
            return null;
        }
        return ClickAction.rightPickup(smallestOver.windowSlot(), "split-source-stack-" + smallestOver.count());
    }

    /**
     * Plans the next single vanilla click to deposit up to {@code remainingToDeposit} items of {@code itemId}
     * from the player's inventory into the open container without overshooting {@code remainingToDeposit}.
     *
     * @param snap               live snapshot of the open container screen
     * @param rawItemId          target item id (e.g. {@code "torch"})
     * @param maxStackSize       item max stack size (e.g. 64)
     * @param remainingToDeposit how many MORE items must land in container slots (excluding cursor)
     * @return next click to execute, or {@code null} when deposit is complete (with empty cursor) or impossible
     */
    public static ClickAction planNextDepositClick(ContainerSlotSnapshot snap,
                                                   String rawItemId,
                                                   int maxStackSize,
                                                   int remainingToDeposit) {
        String itemId = ContainerSlotSnapshot.normalizeOrEmpty(rawItemId);
        int need = Math.max(0, remainingToDeposit);

        // 1. Resolve cursor first.
        if (!snap.cursorEmpty()) {
            if (!snap.cursorMatches(itemId)) {
                return placeCursorInSide(snap, false, snap.cursorItemId(), snap.cursorMaxStack(), "park-unrelated-cursor");
            }
            int chestFree = snap.freeCapacityInContainer(itemId, maxStackSize);
            int putInChest = Math.min(snap.cursorCount(), Math.min(need, chestFree));
            int returnToPlayer = snap.cursorCount() - putInChest;

            if (returnToPlayer <= 0) {
                return placeCursorInSide(snap, true, itemId, maxStackSize, "place-deposit-cursor");
            }
            if (putInChest <= 0) {
                return placeCursorInSide(snap, false, itemId, maxStackSize, "return-excess-cursor-to-player");
            }
            if (putInChest <= returnToPlayer) {
                int chestSlot = findBestPlacementSlot(snap, true, itemId, maxStackSize, 1);
                if (chestSlot >= 0) {
                    return ClickAction.rightPickup(chestSlot, "drop-1-to-dest");
                }
                return placeCursorInSide(snap, false, itemId, maxStackSize, "return-cursor-dest-full");
            } else {
                int playerSlot = findBestPlacementSlot(snap, false, itemId, maxStackSize, 1);
                if (playerSlot >= 0) {
                    return ClickAction.rightPickup(playerSlot, "return-1-to-player");
                }
                return placeCursorInSide(snap, true, itemId, maxStackSize, "place-cursor-player-full");
            }
        }

        // 2. Cursor is empty: compute exact safe cap from live screen state.
        int playerNow = snap.countInPlayer(itemId);
        int chestFree = snap.freeCapacityInContainer(itemId, maxStackSize);
        int safeCap = Math.min(need, Math.min(playerNow, chestFree));
        if (safeCap <= 0) {
            return null;
        }

        // 3. Prefer largest whole player stack that fits within safeCap (1 click via QUICK_MOVE).
        SlotEntry bestWhole = null;
        for (SlotEntry s : snap.slots()) {
            if (s.container() || !s.matches(itemId)) continue;
            if (s.count() <= safeCap) {
                if (bestWhole == null || s.count() > bestWhole.count()) {
                    bestWhole = s;
                }
            }
        }
        if (bestWhole != null) {
            return ClickAction.quickMove(bestWhole.windowSlot(), "quick-move-whole-deposit-" + bestWhole.count());
        }

        // 4. Split smallest player stack exceeding safeCap via right-click.
        SlotEntry smallestOver = null;
        for (SlotEntry s : snap.slots()) {
            if (s.container() || !s.matches(itemId)) continue;
            if (smallestOver == null || s.count() < smallestOver.count()) {
                smallestOver = s;
            }
        }
        if (smallestOver == null) {
            return null;
        }
        return ClickAction.rightPickup(smallestOver.windowSlot(), "split-player-stack-" + smallestOver.count());
    }

    /**
     * Plans a safe cleanup click when cancelling or closing while the cursor still holds an item.
     * Prefer returning to the originating side first, then falling back to the other side.
     */
    public static ClickAction planCursorCleanupClick(ContainerSlotSnapshot snap, boolean preferContainer) {
        if (snap == null || snap.cursorEmpty()) {
            return null;
        }
        ClickAction primary = placeCursorInSide(snap, preferContainer, snap.cursorItemId(), snap.cursorMaxStack(), "cleanup-cursor-primary");
        if (primary != null) {
            return primary;
        }
        return placeCursorInSide(snap, !preferContainer, snap.cursorItemId(), snap.cursorMaxStack(), "cleanup-cursor-fallback");
    }

    private static ClickAction placeCursorInSide(ContainerSlotSnapshot snap,
                                                 boolean toContainer,
                                                 String itemId,
                                                 int maxStackSize,
                                                 String reason) {
        int slot = findBestPlacementSlot(snap, toContainer, itemId, maxStackSize, snap.cursorCount());
        if (slot < 0) {
            return null;
        }
        return ClickAction.leftPickup(slot, reason);
    }

    /**
     * Finds the best slot on the requested side to place {@code incomingCount} items of {@code itemId}:
     * <ol>
     *     <li>A matching partial stack that can absorb all of {@code incomingCount}</li>
     *     <li>An empty slot</li>
     *     <li>Any matching partial stack with at least 1 free space</li>
     * </ol>
     */
    private static int findBestPlacementSlot(ContainerSlotSnapshot snap,
                                             boolean container,
                                             String itemId,
                                             int maxStackSize,
                                             int incomingCount) {
        int partialFitsAll = -1;
        int emptySlot = -1;
        int partialAny = -1;
        for (SlotEntry s : snap.slots()) {
            if (s.container() != container) continue;
            if (s.matches(itemId)) {
                int space = s.freeSpaceFor(itemId, maxStackSize);
                if (space >= incomingCount && partialFitsAll < 0) {
                    partialFitsAll = s.windowSlot();
                } else if (space > 0 && partialAny < 0) {
                    partialAny = s.windowSlot();
                }
            } else if (s.isEmpty() && emptySlot < 0) {
                emptySlot = s.windowSlot();
            }
        }
        if (partialFitsAll >= 0) return partialFitsAll;
        if (emptySlot >= 0) return emptySlot;
        return partialAny;
    }
}
