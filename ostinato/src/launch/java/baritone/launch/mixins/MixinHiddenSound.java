/*
 * Ostinato: while hidden, report every sound source at volume 0. The sound engine is not destroyed and still ticks.
 */
package baritone.launch.mixins;

import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public class MixinHiddenSound {

    @Inject(method = "getFinalSoundSourceVolume", at = @At("HEAD"), cancellable = true)
    private void ostinato$silence(SoundSource source, CallbackInfoReturnable<Float> ci) {
        // Ostinato: volume 0, engine kept. Not reduced: the vanilla volume is unchanged.
        if (HiddenRenderPolicy.silenceSound(RunMode.current(), RenderSwap.isPresenting())) {
            ci.setReturnValue(0f);
        }
    }
}
