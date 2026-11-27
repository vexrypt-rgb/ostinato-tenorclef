/*
 * Ostinato: jitter-free 20 TPS (50ms) multi-instance tick scheduler.
 * Runs a single monotonic LockSupport loop and fans out isolated BotContext ticks
 * across a bounded worker pool.
 */
package baritone.headless;

import baritone.api.context.BotContextScope;
import baritone.swarm.TickRateMeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class HeadlessTickScheduler {

    public static final long TICK_INTERVAL_NANOS = 50_000_000L; // 20 TPS
    private static final long SPIN_THRESHOLD_NANOS = 1_500_000L; // 1.5ms short busy-wait window
    private static final long MAX_CATCHUP_NANOS = 250_000_000L; // cap burst catch-up after GC pause

    private final CopyOnWriteArrayList<HeadlessBot> bots = new CopyOnWriteArrayList<>();
    private final ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong overrunCount = new AtomicLong();
    private final AtomicLong maxDriftNanos = new AtomicLong();
    private final AtomicLong lastTickDurationNanos = new AtomicLong();
    private final AtomicLong totalTickDurationNanos = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final TickRateMeter tickRate = new TickRateMeter();
    private volatile Thread loopThread;

    public HeadlessTickScheduler(int workerThreads) {
        int threads = Math.max(1, workerThreads);
        AtomicInteger id = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "headless-tick-worker-" + id.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.workers = Executors.newFixedThreadPool(threads, factory);
    }

    public void register(HeadlessBot bot) {
        Objects.requireNonNull(bot, "bot");
        bots.addIfAbsent(bot);
    }

    public boolean unregister(HeadlessBot bot) {
        return bot != null && bots.remove(bot);
    }

    public int activeBots() {
        int count = 0;
        for (HeadlessBot bot : bots) {
            if (bot.isConnected() && !bot.isStopRequested()) {
                count++;
            }
        }
        return count;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::runLoop, "headless-tick-clock");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        this.loopThread = t;
        t.start();
    }

    public void stop() {
        running.set(false);
        Thread t = this.loopThread;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long tickCount() {
        return tickCount.get();
    }

    public long overrunCount() {
        return overrunCount.get();
    }

    public long maxDriftNanos() {
        return maxDriftNanos.get();
    }

    public long lastTickDurationNanos() {
        return lastTickDurationNanos.get();
    }

    public long avgTickDurationNanos() {
        long count = tickCount.get();
        return count == 0 ? 0L : totalTickDurationNanos.get() / count;
    }

    public long errorCount() {
        return errorCount.get();
    }

    /** Tenths of a tick per second over the recent 5s window, or -1 if insufficient samples. */
    public int tpsX10() {
        return tickRate.tpsX10(System.currentTimeMillis());
    }

    private void runLoop() {
        long nextDeadline = System.nanoTime() + TICK_INTERVAL_NANOS;
        while (running.get()) {
            waitUntil(nextDeadline);
            if (!running.get()) {
                break;
            }
            tickRate.tick(System.currentTimeMillis());
            long actualStart = System.nanoTime();
            long drift = Math.max(0L, actualStart - nextDeadline);
            updateMax(maxDriftNanos, drift);

            runSingleTick();

            long duration = System.nanoTime() - actualStart;
            lastTickDurationNanos.set(duration);
            totalTickDurationNanos.addAndGet(duration);
            if (duration > TICK_INTERVAL_NANOS) {
                overrunCount.incrementAndGet();
            }
            tickCount.incrementAndGet();

            nextDeadline += TICK_INTERVAL_NANOS;
            long now = System.nanoTime();
            if (now - nextDeadline > MAX_CATCHUP_NANOS) {
                // Skip missed ticks smoothly instead of bursting after a major GC or host stall.
                nextDeadline = now + TICK_INTERVAL_NANOS;
            }
        }
    }

    private void runSingleTick() {
        List<HeadlessBot> snapshot = new ArrayList<>(bots.size());
        for (HeadlessBot bot : bots) {
            if (bot.isConnected() && !bot.isStopRequested()) {
                snapshot.add(bot);
            }
        }
        if (snapshot.isEmpty()) {
            return;
        }
        if (snapshot.size() == 1) {
            tickOneBot(snapshot.get(0));
            return;
        }
        CountDownLatch latch = new CountDownLatch(snapshot.size());
        for (HeadlessBot bot : snapshot) {
            workers.execute(() -> {
                try {
                    tickOneBot(bot);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void tickOneBot(HeadlessBot bot) {
        try {
            BotContextScope.runWith(bot.botContext(), () -> {
                if (bot.hostReady() && bot.playerLoaded() && !bot.hostAttached()) {
                    bot.ensureHost();
                }
                bot.tick();
            });
        } catch (Throwable t) {
            errorCount.incrementAndGet();
            System.err.println("[HeadlessTickScheduler] tick error for " + bot.botId() + ": " + t);
        }
    }

    private void waitUntil(long deadlineNanos) {
        while (running.get()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            if (remaining > SPIN_THRESHOLD_NANOS) {
                LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NANOS);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private static void updateMax(AtomicLong max, long value) {
        long prev;
        do {
            prev = max.get();
            if (value <= prev) {
                return;
            }
        } while (!max.compareAndSet(prev, value));
    }
}
