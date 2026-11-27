/*
 * Ostinato: skip client lighting engine work in headless mode unless keepLight or legitMode=ON is active.
 * Normal and hidden modes pass through unchanged.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.run.RunMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLightEngine.class)
public class MixinHeadlessLightEngine {

    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void ostinato$skipCheckBlock(BlockPos pos, CallbackInfo ci) {
        if (HeadlessPolicy.skipClientLightUpdates(RunMode.current())) {
            ci.cancel();
        }
    }

    @Inject(method = "runLightUpdates", at = @At("HEAD"), cancellable = true)
    private void ostinato$skipRunLightUpdates(CallbackInfoReturnable<Integer> cir) {
        if (HeadlessPolicy.skipClientLightUpdates(RunMode.current())) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "queueSectionData", at = @At("HEAD"), cancellable = true)
    private void ostinato$skipQueueSectionData(LightLayer layer, SectionPos pos, DataLayer data, CallbackInfo ci) {
        if (HeadlessPolicy.skipClientLightUpdates(RunMode.current())) {
            ci.cancel();
        }
    }

    @Inject(method = "propagateLightSources", at = @At("HEAD"), cancellable = true)
    private void ostinato$skipPropagateLightSources(ChunkPos pos, CallbackInfo ci) {
        if (HeadlessPolicy.skipClientLightUpdates(RunMode.current())) {
            ci.cancel();
        }
    }
}
