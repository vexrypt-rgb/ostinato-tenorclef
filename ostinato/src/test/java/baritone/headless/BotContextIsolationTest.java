package baritone.headless;

import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.context.BotContext;
import baritone.api.context.BotContextScope;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.RayTraceUtils;
import net.minecraft.world.level.ClipContext;
import org.junit.Test;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class BotContextIsolationTest {

    @Test
    public void botContextsAreCompletelyIsolatedAcrossThreads() throws Exception {
        HeadlessRuntime.ensureBootstrapped();

        Settings s1 = new Settings();
        Settings s2 = new Settings();
        AltoClefSettings a1 = new AltoClefSettings();
        AltoClefSettings a2 = new AltoClefSettings();

        BotContext ctx1 = new BotContext("bot-1", Path.of("tmp-cfg", "bot-1"), true, s1, a1);
        BotContext ctx2 = new BotContext("bot-2", Path.of("tmp-cfg", "bot-2"), true, s2, a2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> f1 = pool.submit((Callable<Boolean>) () -> BotContextScope.callWith(ctx1, () -> {
                BaritoneAPI.getSettings().allowSprint.value = false;
                BaritoneAPI.getSettings().legitMine.value = true;
                AltoClefSettings.getInstance().setFlowingWaterPass(false);
                RayTraceUtils.setFluidHandling(ClipContext.Fluid.ANY);
                ctx1.setLastLookTurnDegrees(12.5f);
                TickEvent.createNextProvider();
                TickEvent.createNextProvider();
                Thread.sleep(20);
                return !BaritoneAPI.getSettings().allowSprint.value
                        && BaritoneAPI.getSettings().legitMine.value
                        && !AltoClefSettings.getInstance().isFlowingWaterPassAllowed()
                        && RayTraceUtils.currentFluidHandling() == ClipContext.Fluid.ANY
                        && Math.abs(baritone.behavior.LookBehavior.lastTurnDegrees() - 12.5f) < 1e-4f;
            }));

            Future<Boolean> f2 = pool.submit((Callable<Boolean>) () -> BotContextScope.callWith(ctx2, () -> {
                BaritoneAPI.getSettings().allowSprint.value = true;
                BaritoneAPI.getSettings().legitMine.value = false;
                AltoClefSettings.getInstance().setFlowingWaterPass(true);
                RayTraceUtils.setFluidHandling(ClipContext.Fluid.NONE);
                ctx2.setLastLookTurnDegrees(3.25f);
                int firstTick = ctx2.nextOverallTickCount();
                Thread.sleep(20);
                return firstTick == 0
                        && BaritoneAPI.getSettings().allowSprint.value
                        && !BaritoneAPI.getSettings().legitMine.value
                        && AltoClefSettings.getInstance().isFlowingWaterPassAllowed()
                        && RayTraceUtils.currentFluidHandling() == ClipContext.Fluid.NONE
                        && Math.abs(baritone.behavior.LookBehavior.lastTurnDegrees() - 3.25f) < 1e-4f;
            }));

            assertTrue("BotContext 1 should observe only its own settings/state", f1.get());
            assertTrue("BotContext 2 should observe only its own settings/state", f2.get());
            assertNotSame(s1, s2);
            assertNotSame(a1, a2);
            assertSame(HeadlessRuntime.sharedRegistries(), HeadlessRuntime.sharedRegistries());
        } finally {
            pool.shutdownNow();
        }
    }
}
