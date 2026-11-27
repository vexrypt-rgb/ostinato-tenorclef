/*
 * Ostinato: one-time shared JVM runtime for multi-instance headless bots.
 * Initializes vanilla Bootstrap, immutable registries, BlockState flyweights, and a single shared
 * Netty EventLoopGroupHolder once per JVM instead of once per instance.
 */
package baritone.headless;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.level.block.Block;

public final class HeadlessRuntime {

    private static volatile boolean initialized;
    private static volatile RegistryAccess.Frozen sharedRegistries;
    private static volatile EventLoopGroupHolder sharedEventLoop;

    private HeadlessRuntime() {
    }

    public static void ensureBootstrapped() {
        if (initialized) {
            return;
        }
        synchronized (HeadlessRuntime.class) {
            if (initialized) {
                return;
            }
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            sharedRegistries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            // Touch Block.BLOCK_STATE_REGISTRY so immutable BlockState flyweights are initialized once.
            Block.BLOCK_STATE_REGISTRY.size();
            initialized = true;
        }
    }

    public static RegistryAccess.Frozen sharedRegistries() {
        ensureBootstrapped();
        return sharedRegistries;
    }

    public static EventLoopGroupHolder sharedEventLoop() {
        ensureBootstrapped();
        EventLoopGroupHolder holder = sharedEventLoop;
        if (holder == null) {
            synchronized (HeadlessRuntime.class) {
                holder = sharedEventLoop;
                if (holder == null) {
                    holder = EventLoopGroupHolder.remote(false);
                    sharedEventLoop = holder;
                }
            }
        }
        return holder;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
