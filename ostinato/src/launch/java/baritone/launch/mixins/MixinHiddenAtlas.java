/*
 * Ostinato: while hidden, stitch atlases at mip 0. The base atlas is still uploaded. Only the mip chain is skipped,
 * and only when hidden at stitch time (the JVM property is set before the client loads resources).
 */
package baritone.launch.mixins;

import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.renderer.texture.SpriteLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SpriteLoader.class)
public class MixinHiddenAtlas {

    @ModifyVariable(method = "loadAndStitch", at = @At("HEAD"), argsOnly = true)
    private int ostinato$mip(int mip) {
        // Ostinato: loadAndStitch has one int, the mip level.
        return HiddenRenderPolicy.atlasMipLevel(RunMode.current(), RenderSwap.isPresenting(), mip);
    }
}
