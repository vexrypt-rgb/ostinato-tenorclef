package adris.altoclef.tasks.replenish;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerTransferPlannerTest {

    private static ContainerSlotSnapshot chestAndPlayer(int[] chestTorches, int[] playerTorches, int blockedPlayerSlots) {
        List<ContainerSlotSnapshot.SlotEntry> entries = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            int c = i < chestTorches.length ? chestTorches[i] : 0;
            entries.add(new ContainerSlotSnapshot.SlotEntry(i, true, c > 0 ? "torch" : "", c, 64));
        }
        for (int i = 0; i < 36; i++) {
            int win = 27 + i;
            if (i < playerTorches.length && playerTorches[i] > 0) {
                entries.add(new ContainerSlotSnapshot.SlotEntry(win, false, "torch", playerTorches[i], 64));
            } else if (i < playerTorches.length + blockedPlayerSlots) {
                entries.add(new ContainerSlotSnapshot.SlotEntry(win, false, "cobblestone", 64, 64));
            } else {
                entries.add(new ContainerSlotSnapshot.SlotEntry(win, false, "", 0, 64));
            }
        }
        return new ContainerSlotSnapshot(27, entries, "", 0, 64);
    }

    private static ContainerSlotSnapshot runWithdraw(ContainerSlotSnapshot snap, int want, int reserve) {
        int initialPlayer = snap.countInPlayer("torch");
        int steps = 0;
        while (steps++ < 100) {
            int gained = snap.countInPlayer("torch") - initialPlayer;
            int remaining = Math.max(0, want - gained);
            ContainerTransferPlanner.ClickAction act = ContainerTransferPlanner.planNextWithdrawClick(
                    snap, "torch", 64, remaining, reserve);
            if (act == null) {
                break;
            }
            snap = snap.simulate(act);
        }
        assertTrue(steps < 100, "Withdraw planner must terminate in bounded clicks");
        assertTrue(snap.cursorEmpty(), "Cursor must be empty after withdrawal completes");
        return snap;
    }

    private static ContainerSlotSnapshot runDeposit(ContainerSlotSnapshot snap, int want) {
        int initialChest = snap.countInContainer("torch");
        int steps = 0;
        while (steps++ < 100) {
            int added = snap.countInContainer("torch") - initialChest;
            int remaining = Math.max(0, want - added);
            ContainerTransferPlanner.ClickAction act = ContainerTransferPlanner.planNextDepositClick(
                    snap, "torch", 64, remaining);
            if (act == null) {
                break;
            }
            snap = snap.simulate(act);
        }
        assertTrue(steps < 100, "Deposit planner must terminate in bounded clicks");
        assertTrue(snap.cursorEmpty(), "Cursor must be empty after deposit completes");
        return snap;
    }

    @Test
    void withdrawsExactAmountAcrossSplitStacksPreservingReserve() {
        // Source split stacks: 11 + 15 + 18 + 20 = 64, want 42, reserve 16 -> leave 22 (>= 16)
        ContainerSlotSnapshot start = chestAndPlayer(new int[]{11, 15, 18, 20}, new int[]{}, 0);
        ContainerSlotSnapshot end = runWithdraw(start, 42, 16);

        assertEquals(42, end.countInPlayer("torch"));
        assertEquals(22, end.countInContainer("torch"));
        assertTrue(end.countInContainer("torch") >= 16);
    }

    @Test
    void respectsSourceReserveStrictlyWhenNearReserve() {
        // Source has 26, reserve 16 -> surplus 10; deficit 44 -> must withdraw exact 10, leaving exact 16
        ContainerSlotSnapshot start = chestAndPlayer(new int[]{26}, new int[]{}, 0);
        ContainerSlotSnapshot end = runWithdraw(start, 44, 16);

        assertEquals(10, end.countInPlayer("torch"));
        assertEquals(16, end.countInContainer("torch"));
    }

    @Test
    void withdrawsZeroWhenSourceAtOrBelowReserve() {
        ContainerSlotSnapshot atReserve = chestAndPlayer(new int[]{16}, new int[]{}, 0);
        assertNull(ContainerTransferPlanner.planNextWithdrawClick(atReserve, "torch", 64, 40, 16));

        ContainerSlotSnapshot belowReserve = chestAndPlayer(new int[]{7, 5}, new int[]{}, 0);
        assertNull(ContainerTransferPlanner.planNextWithdrawClick(belowReserve, "torch", 64, 40, 16));
    }

    @Test
    void boundsWithdrawByFreeInventoryCapacityWithPartialStack() {
        // Player has 1 slot with 50 torches (room for 14) and remaining 35 slots filled with cobble.
        ContainerSlotSnapshot start = chestAndPlayer(new int[]{64}, new int[]{50}, 35);
        ContainerSlotSnapshot end = runWithdraw(start, 30, 16);

        assertEquals(64, end.countInPlayer("torch")); // gained exact 14
        assertEquals(50, end.countInContainer("torch"));
    }

    @Test
    void withdrawsExactSingleItemFromFullStackCleanly() {
        ContainerSlotSnapshot start = chestAndPlayer(new int[]{64}, new int[]{}, 0);
        ContainerSlotSnapshot end = runWithdraw(start, 1, 16);

        assertEquals(1, end.countInPlayer("torch"));
        assertEquals(63, end.countInContainer("torch"));
    }

    @Test
    void depositsExactRemainingDeficitAcrossSplitStacksLeavingSurplusInPlayer() {
        // Human topped up quarry to 50 (split 10 + 40); courier carries 48 (split 32 + 16); deficit = 14.
        ContainerSlotSnapshot start = chestAndPlayer(new int[]{10, 40}, new int[]{32, 16}, 0);
        ContainerSlotSnapshot end = runDeposit(start, 14);

        assertEquals(64, end.countInContainer("torch"));
        assertEquals(34, end.countInPlayer("torch"));
    }

    @Test
    void cursorCleanupReturnsHeldItemsBeforeClosing() {
        List<ContainerSlotSnapshot.SlotEntry> entries = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            entries.add(new ContainerSlotSnapshot.SlotEntry(i, true, i == 0 ? "torch" : "", i == 0 ? 18 : 0, 64));
        }
        for (int i = 0; i < 36; i++) {
            entries.add(new ContainerSlotSnapshot.SlotEntry(27 + i, false, "", 0, 64));
        }
        ContainerSlotSnapshot midSplit = new ContainerSlotSnapshot(27, entries, "torch", 18, 64);
        ContainerTransferPlanner.ClickAction cleanup = ContainerTransferPlanner.planCursorCleanupClick(midSplit, true);
        assertNotNull(cleanup);
        ContainerSlotSnapshot after = midSplit.simulate(cleanup);
        assertTrue(after.cursorEmpty());
        assertEquals(36, after.countInContainer("torch"));
    }
}
