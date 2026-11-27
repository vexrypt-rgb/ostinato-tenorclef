/*
 * Ostinato: ScopedValue-backed BotContext propagation for single-JVM multi-instance execution.
 */
package baritone.api.context;

import java.util.concurrent.Callable;

/**
 * Binds a {@link BotContext} to the current execution frame using Java's {@link ScopedValue}.
 * Unbound callers observe {@code null} and fall back to default single-client singletons.
 */
public final class BotContextScope {

    public static final ScopedValue<BotContext> CURRENT = ScopedValue.newInstance();
    private static final ThreadLocal<BotContext> FALLBACK = new ThreadLocal<>();

    private BotContextScope() {
    }

    public static boolean isBound() {
        return CURRENT.isBound() || FALLBACK.get() != null;
    }

    public static BotContext currentOrNull() {
        if (CURRENT.isBound()) {
            return CURRENT.get();
        }
        return FALLBACK.get();
    }

    public static BotContext requireCurrent() {
        BotContext ctx = currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("No BotContext bound to current thread");
        }
        return ctx;
    }

    public static void runWith(BotContext ctx, Runnable task) {
        if (ctx == null) {
            task.run();
            return;
        }
        ScopedValue.where(CURRENT, ctx).run(task);
    }

    public static <T> T callWith(BotContext ctx, Callable<T> task) throws Exception {
        if (ctx == null) {
            return task.call();
        }
        return ScopedValue.where(CURRENT, ctx).call(task::call);
    }

    public static Runnable wrap(BotContext ctx, Runnable task) {
        if (ctx == null) {
            return task;
        }
        return () -> runWith(ctx, task);
    }

    public static Runnable wrapCurrent(Runnable task) {
        return wrap(currentOrNull(), task);
    }

    /**
     * Binds a fallback ThreadLocal for non-scoped lifecycle hooks; prefer {@link #runWith(BotContext, Runnable)}.
     */
    public static AutoCloseable openFallback(BotContext ctx) {
        BotContext prev = FALLBACK.get();
        FALLBACK.set(ctx);
        return () -> {
            if (prev == null) {
                FALLBACK.remove();
            } else {
                FALLBACK.set(prev);
            }
        };
    }
}
