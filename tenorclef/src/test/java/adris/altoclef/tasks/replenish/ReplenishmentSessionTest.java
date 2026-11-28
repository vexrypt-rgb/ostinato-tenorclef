package adris.altoclef.tasks.replenish;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the full 10-cycle acceptance suite + edge cases against {@link ReplenishmentSession},
 * {@link ReplenishmentJournal}, and {@link ContainerTransferPlanner}.
 */
class ReplenishmentSessionTest {

    private static final ReplenishmentRule TORCH_RULE = new ReplenishmentRule(
            "torch", "home", "quarry", 32, 64, 16, 64, 1_000L);

    private static final class SimWorld {
        int home;
        int quarry;
        int botInv;
        int freeInvCap = 36 * 64;
        int chestCap = 27 * 64;
        long nowMs = 10_000L;
        int minHomeObserved;

        SimWorld(int home, int quarry, int botInv) {
            this.home = home;
            this.quarry = quarry;
            this.botInv = botInv;
            this.minHomeObserved = home;
        }

        void stepOnce(ReplenishmentSession s) {
            s.tickScheduler(nowMs, botInv);
            switch (s.phase()) {
                case CHECK_DEST, DELIVER_DEST -> {
                    int initDest = quarry;
                    int destFree = Math.max(0, chestCap - quarry);
                    ReplenishmentSession.ScreenPlan plan = s.onDestinationOpened(quarry, destFree, botInv, nowMs);
                    int moved = 0;
                    if (plan.kind() == ReplenishmentSession.ScreenActionKind.DEPOSIT) {
                        moved = Math.min(plan.exactAmount(), Math.min(botInv, destFree));
                        botInv -= moved;
                        quarry += moved;
                    }
                    s.onDestinationVisitFinished(initDest, quarry, moved, Math.max(0, chestCap - quarry), botInv, nowMs);
                }
                case WITHDRAW_SOURCE, RETURN_SURPLUS -> {
                    boolean isReturn = s.phase() == ReplenishmentSession.Phase.RETURN_SURPLUS;
                    int initSrc = home;
                    int srcFree = Math.max(0, chestCap - home);
                    int invFree = Math.max(0, freeInvCap - botInv);
                    ReplenishmentSession.ScreenPlan plan = s.onSourceOpened(home, srcFree, invFree, botInv, nowMs);
                    int moved = 0;
                    if (plan.kind() == ReplenishmentSession.ScreenActionKind.WITHDRAW) {
                        moved = Math.min(plan.exactAmount(), Math.min(home, invFree));
                        home -= moved;
                        botInv += moved;
                        if (home < minHomeObserved) {
                            minHomeObserved = home;
                        }
                    } else if (plan.kind() == ReplenishmentSession.ScreenActionKind.DEPOSIT) {
                        moved = Math.min(plan.exactAmount(), Math.min(botInv, srcFree));
                        botInv -= moved;
                        home += moved;
                    }
                    s.onSourceVisitFinished(initSrc, home, moved, isReturn, Math.max(0, freeInvCap - botInv), botInv, nowMs);
                }
                default -> {
                }
            }
        }

        void settle(ReplenishmentSession s) {
            s.tickScheduler(nowMs, botInv);
            for (int i = 0; i < 12; i++) {
                if (s.phase() == ReplenishmentSession.Phase.IDLE_MONITORING
                        || s.phase() == ReplenishmentSession.Phase.BACKOFF
                        || s.isTerminal()) {
                    return;
                }
                stepOnce(s);
            }
            fail("Session did not settle within 12 visits; stuck in " + s.phase() + " (" + s.lastReport() + ")");
        }
    }

    @Test
    void tenConsumptionCyclesAcceptanceSuite(@TempDir Path tempDir) {
        Path journalFile = tempDir.resolve("replenish-journal.properties");
        ReplenishmentJournal.Store store = new ReplenishmentJournal.FileStore(journalFile);
        ReplenishmentSession session = new ReplenishmentSession(TORCH_RULE, store);
        SimWorld world = new SimWorld(128, 20, 0);
        session.reconcileOnStartOrResume(world.botInv, world.nowMs);

        // Cycle 1: below threshold (20 < 32) -> refill to 64
        world.settle(session);
        assertEquals(64, world.quarry);
        assertEquals(84, world.home);
        assertEquals(0, world.botInv);
        assertEquals(ReplenishmentSession.Phase.IDLE_MONITORING, session.phase());

        // Cycle 2: at threshold (32 -> must NOT trigger)
        world.quarry = 32;
        world.nowMs += 2_000L;
        int destChecksBefore = session.metrics().snapshot().destChecks();
        int srcVisitsBefore = session.metrics().snapshot().sourceVisits();
        world.settle(session);
        assertEquals(destChecksBefore + 1, session.metrics().snapshot().destChecks());
        assertEquals(32, world.quarry);
        assertEquals(84, world.home);
        assertEquals(0, world.botInv);
        assertEquals(srcVisitsBefore, session.metrics().snapshot().sourceVisits());

        // Cycle 3: just below threshold (31 -> refills exact 33 to 64)
        world.quarry = 31;
        world.nowMs += 2_000L;
        world.settle(session);
        assertEquals(64, world.quarry);
        assertEquals(51, world.home);
        assertEquals(0, world.botInv);

        // Cycle 4: split/partial refill with personal baseline torches untouched (bot carries 5 personal torches)
        world.botInv = 5;
        world.quarry = 22;
        world.home = 64;
        world.minHomeObserved = 64;
        world.nowMs += 2_000L;
        // Reconcile fresh baseline when starting new order
        session.reconcileOnStartOrResume(world.botInv, world.nowMs);
        world.settle(session);
        assertEquals(64, world.quarry);
        assertEquals(22, world.home);
        assertEquals(5, world.botInv); // personal torches preserved

        // Cycle 5: insufficient source AT reserve (home=16, quarry=10) -> 0 withdrawn, backoff
        world.botInv = 0;
        world.home = 16;
        world.minHomeObserved = 16;
        world.quarry = 10;
        world.nowMs += 2_000L;
        session.reconcileOnStartOrResume(world.botInv, world.nowMs);
        world.settle(session);
        assertEquals(16, world.home);
        assertEquals(10, world.quarry);
        assertEquals(0, world.botInv);
        assertEquals(ReplenishmentSession.Phase.BACKOFF, session.phase());
        assertTrue(session.metrics().snapshot().shortageReports() >= 1);
        // Must not busy-loop during backoff
        int opensDuringBackoff = session.metrics().snapshot().totalChestOpens();
        world.nowMs += 1_000L;
        world.stepOnce(session);
        assertEquals(opensDuringBackoff, session.metrics().snapshot().totalChestOpens());

        // Cycle 6: insufficient source BELOW reserve (home=12, quarry=10) -> 0 withdrawn, backoff
        world.home = 12;
        world.nowMs = session.backoffUntilMs() + 100L;
        world.settle(session);
        assertEquals(12, world.home);
        assertEquals(10, world.quarry);
        assertEquals(0, world.botInv);
        assertEquals(ReplenishmentSession.Phase.BACKOFF, session.phase());

        // Cycle 7: near-reserve partial refill (home=26, quarry=20 -> withdraw exact 10, leave 16, deliver 10 -> 30, backoff)
        world.home = 26;
        world.minHomeObserved = 26;
        world.quarry = 20;
        world.nowMs = session.backoffUntilMs() + 100L;
        world.settle(session);
        assertEquals(16, world.home);
        assertEquals(16, world.minHomeObserved);
        assertEquals(30, world.quarry);
        assertEquals(0, world.botInv);
        assertEquals(ReplenishmentSession.Phase.BACKOFF, session.phase());

        // Cycle 8: human player partially refills destination while courier is in transit
        world.home = 100;
        world.minHomeObserved = 100;
        world.quarry = 16;
        world.nowMs = session.backoffUntilMs() + 100L;
        world.stepOnce(session); // CHECK_DEST -> WITHDRAW_SOURCE
        world.stepOnce(session); // WITHDRAW_SOURCE (takes 48) -> DELIVER_DEST
        assertEquals(ReplenishmentSession.Phase.DELIVER_DEST, session.phase());
        assertEquals(48, world.botInv);
        assertEquals(52, world.home);
        // Human puts 34 torches into quarry while bot walks over (quarry now 50, remaining deficit 14)
        world.quarry = 50;
        world.settle(session);
        assertEquals(64, world.quarry);
        assertEquals(86, world.home); // 52 + 34 returned surplus
        assertEquals(0, world.botInv);

        // Cycle 9: human player fully refills destination to 64 while courier is in transit
        world.home = 100;
        world.quarry = 20;
        world.nowMs += 2_000L;
        world.stepOnce(session); // CHECK_DEST -> WITHDRAW_SOURCE
        world.stepOnce(session); // WITHDRAW_SOURCE (takes 44) -> DELIVER_DEST
        assertEquals(ReplenishmentSession.Phase.DELIVER_DEST, session.phase());
        assertEquals(44, world.botInv);
        world.quarry = 64; // human filled to 64
        world.settle(session);
        assertEquals(64, world.quarry);
        assertEquals(100, world.home); // all 44 returned
        assertEquals(0, world.botInv);

        // Cycle 10: full bot restart from persisted FileStore journal while carrying torches in transit
        world.home = 100;
        world.quarry = 20;
        world.nowMs += 2_000L;
        world.stepOnce(session); // CHECK_DEST -> WITHDRAW_SOURCE
        world.stepOnce(session); // WITHDRAW_SOURCE (takes 44) -> DELIVER_DEST
        assertEquals(ReplenishmentSession.Phase.DELIVER_DEST, session.phase());
        assertEquals(44, world.botInv);
        assertEquals(56, world.home);

        // Simulate full bot restart: brand-new session reading the persisted journal file!
        ReplenishmentSession restarted = new ReplenishmentSession(TORCH_RULE, store);
        int srcVisitsPreRestart = session.metrics().snapshot().sourceVisits();
        restarted.reconcileOnStartOrResume(world.botInv, world.nowMs + 500L);
        assertEquals(ReplenishmentSession.Phase.DELIVER_DEST, restarted.phase());
        assertEquals(44, restarted.carriedForOrder());
        world.settle(restarted);
        assertEquals(64, world.quarry);
        assertEquals(56, world.home); // NEVER withdrew a second batch!
        assertEquals(0, world.botInv);
        assertEquals(srcVisitsPreRestart, restarted.metrics().snapshot().sourceVisits());
        assertTrue(restarted.metrics().snapshot().interruptRecoveries() >= 1);
    }

    @Test
    void changeEventTriggersImmediateWithdrawalWithoutExtraDestOpen() {
        ReplenishmentSession session = new ReplenishmentSession(TORCH_RULE, new ReplenishmentJournal.MemoryStore());
        session.reconcileOnStartOrResume(0, 1_000L);
        // Complete initial check at healthy 64
        session.onDestinationOpened(64, 1000, 0, 1_000L);
        session.onDestinationVisitFinished(64, 64, 0, 1000, 0, 1_000L);
        assertEquals(ReplenishmentSession.Phase.IDLE_MONITORING, session.phase());
        int destOpensBefore = session.metrics().snapshot().destOpens();

        // External observation (ContainerSubTracker) sees quarry dropped to 20
        session.onDestinationObservedExternally(20, 1_500L, 0, 1_500L);
        assertEquals(ReplenishmentSession.Phase.WITHDRAW_SOURCE, session.phase());
        assertEquals(destOpensBefore, session.metrics().snapshot().destOpens()); // saved a dest open!
        assertEquals(44, session.plannedDeficit());
    }

    @Test
    void boundsRetriesAndHonorsCancellation() {
        ReplenishmentSession session = new ReplenishmentSession(
                TORCH_RULE, new ReplenishmentJournal.MemoryStore(), 3, 4,
                new long[]{1_000L}, new long[]{500L});
        session.reconcileOnStartOrResume(0, 1_000L);

        session.onVisitFailed("chest unreachable", 1_000L);
        assertEquals(ReplenishmentSession.Phase.BACKOFF, session.phase());
        session.onVisitFailed("chest unreachable", 2_000L);
        assertEquals(ReplenishmentSession.Phase.BACKOFF, session.phase());
        session.onVisitFailed("chest unreachable", 3_000L);
        assertEquals(ReplenishmentSession.Phase.FAILED, session.phase());
        assertTrue(session.isTerminal());

        ReplenishmentSession cancelSession = new ReplenishmentSession(TORCH_RULE, new ReplenishmentJournal.MemoryStore());
        cancelSession.reconcileOnStartOrResume(0, 1_000L);
        cancelSession.cancel(1_200L);
        assertEquals(ReplenishmentSession.Phase.CANCELLED, cancelSession.phase());
        assertTrue(cancelSession.isTerminal());
    }
}
