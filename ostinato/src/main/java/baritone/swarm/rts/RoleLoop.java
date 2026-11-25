package baritone.swarm.rts;

/**
 * Ostinato: RTS mode. The pure state machine behind a role: given what the work step and the deposit step report,
 * it says which step runs next. The game side starts or stops one Baritone process per change; it never decides
 * on its own. No game types, so it is unit-tested and shared with the headless bot.
 *
 * <pre>
 *   IDLE --assign--> WORK --full / batch / work ended with output--> DEPOSIT --done--> WORK
 *                     |                                                  |
 *                     +--work failed or ended empty--> WAIT --backoff--> WORK
 *                                                     ^                  |
 *                                                     +--deposit failed--+  (3 in a row: WAIT with a reason)
 * </pre>
 */
public final class RoleLoop {

    public enum Phase { IDLE, WORK, DEPOSIT, WAIT }

    /** What the game side measured this tick. */
    public record Inputs(long nowMs, int freeSlots, int output, boolean workActive, boolean workFailed,
                         boolean depositActive, boolean depositDone, String depositFailure) {}

    /** What changed. {@code start} is true on the tick a phase begins, so the caller starts its process once. */
    public record Step(Phase phase, boolean start, String note) {}

    public static final int MIN_FREE = 2;
    public static final long[] BACKOFF_MS = {5_000, 15_000, 45_000};
    /** Work that ends with nothing to show waits this long before the next try (no ripe crops yet). */
    public static final long EMPTY_WAIT_MS = 10_000;

    private final RoleOrder order;
    private Phase phase = Phase.IDLE;
    private Phase after = Phase.WORK;
    private long waitUntil;
    private int failures;
    private int deposits;
    private String note = "";
    private boolean workSeen;

    public RoleLoop(RoleOrder order) {
        this.order = order;
        if (order.role != Role.IDLE) {
            phase = Phase.WORK;
        }
    }

    public RoleOrder order() {
        return order;
    }

    public Phase phase() {
        return phase;
    }

    public String note() {
        return note;
    }

    public int deposits() {
        return deposits;
    }

    public int failures() {
        return failures;
    }

    private boolean hasChest() {
        return order.chest != null;
    }

    private Step advance(Inputs in) {
        if (order.role == Role.IDLE) {
            return new Step(Phase.IDLE, false, "");
        }
        boolean fresh = !started;
        started = true;
        switch (phase) {
            case WORK -> {
                if (fresh) {
                    workSeen = false;
                    return go(Phase.WORK, "working");
                }
                if (in.workActive()) {
                    workSeen = true;
                }
                boolean full = in.freeSlots() >= 0 && in.freeSlots() <= MIN_FREE;
                if (hasChest() && (full || in.output() >= order.batch)) {
                    return go(Phase.DEPOSIT, full ? "inventory full" : "batch of " + in.output());
                }
                if (in.workFailed()) {
                    return waitThen(Phase.WORK, backoff(), "work failed");
                }
                if (workSeen && !in.workActive()) {
                    if (hasChest() && in.output() > 0) {
                        return go(Phase.DEPOSIT, "work done, " + in.output() + " to store");
                    }
                    return waitThen(Phase.WORK, EMPTY_WAIT_MS, full ? "inventory full, no chest" : "nothing to do");
                }
                return new Step(Phase.WORK, false, note);
            }
            case DEPOSIT -> {
                if (in.depositDone()) {
                    deposits++;
                    failures = 0;
                    return go(Phase.WORK, "deposited");
                }
                if (in.depositFailure() != null) {
                    return waitThen(Phase.DEPOSIT, backoff(), "deposit failed: " + in.depositFailure());
                }
                return new Step(Phase.DEPOSIT, false, note);
            }
            case WAIT -> {
                if (in.nowMs() >= waitUntil) {
                    if (after == Phase.WORK) {
                        workSeen = false;
                    }
                    return go(after, after == Phase.WORK ? "working" : "retry deposit");
                }
                return new Step(Phase.WAIT, false, note);
            }
            default -> {
                return new Step(phase, false, note);
            }
        }
    }

    private boolean started;

    private long backoff() {
        long ms = BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
        failures++;
        return ms;
    }

    private Step go(Phase next, String why) {
        phase = next;
        note = why;
        if (next == Phase.WORK) {
            workSeen = false;
        }
        return new Step(next, true, why);
    }

    private Step waitThen(Phase next, long ms, String why) {
        after = next;
        phase = Phase.WAIT;
        waitUntil = lastNow + ms;
        note = failures >= BACKOFF_MS.length ? why + " (" + failures + " in a row)" : why;
        return new Step(Phase.WAIT, true, note);
    }

    private long lastNow;

    /** Call once per tick. The first call after construction returns {@code WORK} with {@code start}. */
    public Step step(Inputs in) {
        lastNow = in.nowMs();
        return advance(in);
    }
}
