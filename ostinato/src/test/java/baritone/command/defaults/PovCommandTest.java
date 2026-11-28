package baritone.command.defaults;

import baritone.api.headless.ViewCapability;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class PovCommandTest {

    @After
    public void cleanup() {
        RenderSwap.resetForTests();
        System.clearProperty(RunMode.PROPERTY);
        RunMode.resetForTests();
    }

    @Test
    public void renderSwapAndCapabilitiesMatchRunModes() {
        assertTrue(ViewCapability.canRenderSwap(RunMode.Value.HIDDEN));
        assertFalse(ViewCapability.canRenderSwap(RunMode.Value.HEADLESS));
        assertFalse(ViewCapability.canRenderSwap(RunMode.Value.NORMAL));
        assertTrue(ViewCapability.canStreamView(RunMode.Value.HEADLESS));

        // No-op in normal mode
        System.clearProperty(RunMode.PROPERTY);
        RunMode.resetForTests();
        RenderSwap.requestPresent();
        assertFalse(RenderSwap.isPresenting());

        // Active in hidden mode
        System.setProperty(RunMode.PROPERTY, "hidden");
        RunMode.resetForTests();
        RenderSwap.requestPresent();
        assertTrue(RenderSwap.isPresenting());
        RenderSwap.releasePresent();
        assertFalse(RenderSwap.isPresenting());
    }

    @Test
    public void povCommandFormsDeclareWatchAndReturn() {
        assertEquals(2, PovCommand.windowForms().size());
        assertEquals("pov-show", PovCommand.windowForms().get(0).id());
        assertEquals("pov-hide", PovCommand.windowForms().get(1).id());
    }
}
