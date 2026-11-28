package adris.altoclef.shop;

import adris.altoclef.Debug;

import java.lang.reflect.Proxy;

/**
 * Registers TenorClef's {@link ShoppingRunner} as the backend for Ostinato's {@code #shop} command
 * and in-game / Command Center Shopping List menus. Uses reflection over {@code baritone.api.shop.*}
 * so older Ostinato builds without the shopping API stay compatible.
 */
public final class ShoppingBridge {

    private ShoppingBridge() {}

    public static boolean install() {
        try {
            Class<?> iface = Class.forName("baritone.api.shop.IShoppingRunner");
            Class<?> registry = Class.forName("baritone.api.shop.ShoppingRunners");
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (self, method, args) -> {
                switch (method.getName()) {
                    case "gather":
                        return ShoppingRunner.gather((String) args[0]);
                    case "status":
                        return ShoppingRunner.status();
                    case "stop":
                        return ShoppingRunner.stop();
                    case "isGatherable":
                        return ShoppingRunner.isGatherable((String) args[0]);
                    case "name":
                    case "toString":
                        return "TenorClef";
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "equals":
                        return self == args[0];
                    default:
                        return null;
                }
            });
            registry.getMethod("register", iface).invoke(null, proxy);
            Debug.logInternal("[shop] TenorClef registered as Ostinato #shop runner");
            return true;
        } catch (ClassNotFoundException noApi) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Debug.logInternal("[shop] could not register #shop runner: " + e);
            return false;
        }
    }
}
