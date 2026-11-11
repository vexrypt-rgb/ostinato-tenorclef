/*
 * Ostinato: Main names the main thread "Render thread" and then records it. Headless skips both.
 * Hidden and normal still do. The Minecraft constructor is not skipped.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.run.RunMode;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Main.class)
public class MixinHeadlessMain {

    // Ostinato: only the "Render thread" rename is skipped. Other names still apply.
    @Redirect(
            method = "main",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;setName(Ljava/lang/String;)V")
    )
    private static void ostinato$name(Thread thread, String name) {
        if (HeadlessPolicy.skipRenderThread(RunMode.windowCreateMode()) && "Render thread".equals(name)) {
            return;
        }
        thread.setName(name);
    }

    // Ostinato: do not record the render thread. initRenderThread itself also no-ops, this is the call site.
    @Redirect(
            method = "main",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;initRenderThread()V"
            )
    )
    private static void ostinato$renderThread() {
        if (HeadlessPolicy.skipRenderThread(RunMode.windowCreateMode())) {
            return;
        }
        RenderSystem.initRenderThread();
    }
}
