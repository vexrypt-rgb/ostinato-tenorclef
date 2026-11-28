package baritone.api.shop;

/** Ostinato: registry where a client mod (TenorClef) registers its {@link IShoppingRunner}. */
public final class ShoppingRunners {

    private static volatile IShoppingRunner runner;

    private ShoppingRunners() {}

    public static void register(IShoppingRunner value) {
        runner = value;
    }

    public static IShoppingRunner get() {
        return runner;
    }
}
