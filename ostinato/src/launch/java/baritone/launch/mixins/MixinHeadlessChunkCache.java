/*
 * Ostinato: clamp ClientChunkCache view radius in headless mode to reduce per-instance chunk heap.
 * Normal and hidden modes pass through unchanged.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.run.RunMode;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientChunkCache.class)
public class MixinHeadlessChunkCache {

    @ModifyVariable(method = "updateViewRadius", at = @At("HEAD"), argsOnly = true)
    private int ostinato$capHeadlessViewRadius(int radius) {
        if (!HeadlessPolicy.skipGpuWorkNow()) {
            return radius;
        }
        return HeadlessPolicy.chunkRadiusCap(RunMode.current(), radius);
    }
}
