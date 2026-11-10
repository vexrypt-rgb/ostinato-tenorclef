/*
 * Ostinato: a small hook a later render-swap can call. Not a POV switcher and not a command-center button.
 */
package baritone.api.hidden;

/**
 * Presents one hidden bot's window without changing the stored {@code runMode}.
 * <p>
 * {@link #requestPresent()} turns render reduction off and, on the next client tick, shows the SDL window at
 * {@code -Dostinato.windowPos} or the default 2600,40. The window is not raised and the OS cursor is not grabbed.
 * {@link #releasePresent()} hides it again when the mode is still hidden. Phase 4 builds the switcher on top of this.
 */
public final class RenderSwap {

    private static volatile boolean presenting;

    private RenderSwap() {}

    /** Show this bot and resume its rendering. Does not write {@code runMode}. */
    public static void requestPresent() {
        presenting = true;
    }

    /** Hide again if the mode is still hidden, and resume render reduction. */
    public static void releasePresent() {
        presenting = false;
    }

    public static boolean isPresenting() {
        return presenting;
    }

    /** Tests only. */
    public static void resetForTests() {
        presenting = false;
    }
}
