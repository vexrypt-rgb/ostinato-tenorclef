/*
 * Ostinato: headless clients never burst ticks after a stall (RTS r5: Azalea idled, its main thread stalled 1.26 s,
 * then caught up 20 ticks in 0.75 s and Grim flagged Timer x5 / TimerLimit x2). The ticks owed beyond
 * HeadlessPolicy.MAX_TICKS_PER_FRAME are dropped, so the server sees a bot that lagged, never one that ran fast.
 * Only the loop's tick count changes; inputs and the packets vanilla sends for each tick are untouched.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.run.RunMode;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DeltaTracker.Timer.class)
public class MixinHeadlessTickBurst {

    private static int ostinato$dropped;

    @Inject(method = "advanceGameTime", at = @At("RETURN"), cancellable = true)
    private void ostinato$cap(long ms, CallbackInfoReturnable<Integer> ci) {
        int owed = ci.getReturnValueI();
        int run = HeadlessPolicy.ticksThisFrame(RunMode.current(), owed);
        if (run != owed) {
            ci.setReturnValue(run);
            if (ostinato$dropped++ % 50 == 0) { // Ostinato: rare, keep the log readable
                System.out.println("[Ostinato] headless: tick burst capped " + owed + " -> " + run + " (" + ostinato$dropped + " so far)");
            }
        }
    }
}
