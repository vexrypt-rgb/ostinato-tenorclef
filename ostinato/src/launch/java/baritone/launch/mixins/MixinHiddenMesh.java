/*
 * Ostinato: skip async chunk mesh builds while hidden. compileSync is not cancelled: the render thread may
 * block on the section the camera is in, and cancelling a compile that already owns a buffer can leak it.
 */
package baritone.launch.mixins;

import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public class MixinHiddenMesh {

    @Inject(method = "compileAsync", at = @At("HEAD"), cancellable = true)
    private void ostinato$skipAsync(RenderSectionRegion region, CallbackInfo ci) {
        // Ostinato: no new async mesh while hidden. An already-running task is not cancelled.
        if (HiddenRenderPolicy.skipChunkMeshBuild(RunMode.current(), RenderSwap.isPresenting())) {
            ci.cancel();
        }
    }

    @Inject(method = "resortTransparency", at = @At("HEAD"), cancellable = true)
    private void ostinato$skipResort(CallbackInfo ci) {
        // Ostinato: transparency resort schedules the same kind of mesh work.
        if (HiddenRenderPolicy.skipChunkMeshBuild(RunMode.current(), RenderSwap.isPresenting())) {
            ci.cancel();
        }
    }
}
