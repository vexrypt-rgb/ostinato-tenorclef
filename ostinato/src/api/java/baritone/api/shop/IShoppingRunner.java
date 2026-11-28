package baritone.api.shop;

/**
 * Ostinato: client-side shopping list gather runner (implemented and registered by TenorClef).
 * Lets the in-game Shopping List desk, the Command Center, and {@code #shop} dispatch multi-item
 * gather orders without coupling Ostinato to TenorClef internals.
 */
public interface IShoppingRunner {

    /**
     * Starts gathering every item and amount in {@code spec}, formatted as whitespace-separated
     * {@code item_id:count} pairs (for example {@code oak_log:32 cobblestone:64 diamond_pickaxe:1}).
     *
     * @return short human-readable status message
     */
    String gather(String spec);

    /** Returns current shopping list gather status. */
    String status();

    /** Stops the active shopping list gather, if any. */
    String stop();

    /** Whether {@code itemId} has a known survival gather path in the registered runner. */
    default boolean isGatherable(String itemId) {
        return true;
    }

    /** Human-readable name of the registered runner (e.g. {@code TenorClef}). */
    default String name() {
        return "runner";
    }
}
