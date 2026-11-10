/*
 * Ostinato: draw the world about once a second while hidden. The rest of GameRenderer.render still runs,
 * so the frame lifecycle and the GL context stay alive. The render thread is not skipped.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinHiddenWorld {

    @Unique
    private long ostinato$lastWorldDrawNs = -1L;

    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void ostinato$worldDraw(CallbackInfo ci) {
        // Ostinato: cancel only renderLevel. Blit, post, gui, and endFrame in render() still run.
        RunMode.Value mode = RunMode.current();
        boolean presenting = RenderSwap.isPresenting();
        long now = System.nanoTime();
        // Ostinato: headless draws no world. The rest of GameRenderer.render still runs.
        if (HeadlessPolicy.skipWorldDraw(mode)) {
            ci.cancel();
            return;
        }
        if (!HiddenRenderPolicy.shouldDrawWorld(mode, presenting, ostinato$lastWorldDrawNs, now)) {
            ci.cancel();
            return;
        }
        ostinato$lastWorldDrawNs = HiddenRenderPolicy.reduced(mode, presenting) ? now : -1L;
    }
}
