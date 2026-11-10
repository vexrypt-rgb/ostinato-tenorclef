/*
 * Ostinato: while hidden, cap the display limiter. Do not pass 1.
 * limitDisplayFPS sleeps this thread, and this thread also pumps ticks.
 */
package baritone.launch.mixins;

import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Minecraft.class)
public class MixinHiddenFps {

    @ModifyArg(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"), index = 0)
    private int ostinato$hiddenFps(int cap) {
        // Ostinato: min(caller, 20) while hidden and not presenting. The caller's cap is never raised.
        return HiddenRenderPolicy.displayFpsCap(RunMode.current(), RenderSwap.isPresenting(), cap);
    }
}
