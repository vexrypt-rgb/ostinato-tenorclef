/*
 * Ostinato: a LoadingOverlay leaves the screen only from its extractRenderState, which headless cancels
 * (MixinHeadlessDraw). Vanilla's tick runs handleKeybinds only while no overlay is up, so a headless bot never
 * turned its pressed keys into clicks: no attack, no use, no hotbar select. Headless lets the keybind gate see past
 * a loading overlay nobody can see. handleKeybinds itself is unchanged, so clicks still go through vanilla's own
 * key handling and packet send, exactly as on a windowed client.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public class MixinHeadlessKeybinds {

    @Unique
    private static boolean ostinato$noted;

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;", ordinal = 0))
    private Overlay ostinato$keybindGate(Gui gui) {
        Overlay o = gui.overlay();
        // Ostinato: only headless, and only the loading overlay (it can never fade out without a draw).
        if (o != null && HeadlessPolicy.skipGpuWorkNow()) {
            if (!ostinato$noted) {
                ostinato$noted = true;
                System.out.println("[Ostinato] headless: keybind gate saw overlay " + o.getClass().getName()
                        + (o instanceof LoadingOverlay ? "; letting keybinds through" : "; left in place"));
            }
            if (o instanceof LoadingOverlay) {
                return null;
            }
        }
        return o;
    }
}
