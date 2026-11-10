/*
 * Ostinato: true-headless decisions. Not part of upstream Baritone.
 * Mixins apply these flags. They do not cancel the client tick or the render thread.
 */
package baritone.api.headless;

import baritone.api.run.RunMode;

/**
 * What to cut while {@link RunMode.Value#HEADLESS} is on.
 * <p>
 * {@link RunMode.Value#NORMAL} and {@link RunMode.Value#HIDDEN} do not take this path. Hidden keeps its own
 * window and its 1 fps reducer. A null mode is not headless.
 * <p>
 * The 26.3 client creates the GPU device before the SDL window. {@code GpuBackend.createWindow} returning 0 throws
 * inside {@code Window}, and skipping every backend throws {@code Window.WindowInitFailed}. Those calls still run.
 * The crash-free stub is a real hidden SDL window that this policy never shows. {@link #keepsRealGpuDevice()} is
 * true for that reason. It is not a claim that a live process has no GPU.
 */
public final class HeadlessPolicy {

    /**
     * Upper bound passed to {@code limitDisplayFPS} while headless, when the caller's cap is higher or unlimited.
     * Same floor as hidden mode: the limiter sleeps the thread that pumps ticks, and the client catches up at most
     * 10 ticks per update. A 1 Hz sleep would stall the bot. A lower caller cap is kept.
     */
    public static final int LOOP_FLOOR_FPS = 20;

    private HeadlessPolicy() {}

    /** True only for {@link RunMode.Value#HEADLESS}. */
    public static boolean active(RunMode.Value mode) {
        return mode == RunMode.Value.HEADLESS;
    }

    /**
     * The startup GPU device is still created. Cancelling it crashes the client before the first tick.
     * This is true for every mode, including headless.
     */
    public static boolean keepsRealGpuDevice() {
        return true;
    }

    /** Cancel {@code GameRenderer.renderLevel} on every frame. {@code GameRenderer.render} and the render thread stay. */
    public static boolean skipWorldDraw(RunMode.Value mode) {
        return active(mode);
    }

    /**
     * Cap passed to {@code limitDisplayFPS}. Not headless: the caller's cap. Headless: {@code min(caller, 20)},
     * or 20 when the caller is unlimited ({@code <= 0}). Never raises the caller's cap.
     */
    public static int displayFpsCap(RunMode.Value mode, int callerCap) {
        if (!active(mode)) {
            return callerCap;
        }
        if (callerCap <= 0) {
            return LOOP_FLOOR_FPS;
        }
        return Math.min(callerCap, LOOP_FLOOR_FPS);
    }

    /** Skip {@code RenderSection.compileAsync} and {@code resortTransparency}. {@code compileSync} is not skipped. */
    public static boolean skipAsyncChunkMesh(RunMode.Value mode) {
        return active(mode);
    }

    /**
     * Always false. The render thread can block on the section the camera is in, and cancelling a compile that
     * already owns a buffer can leak it. Not proven safe to skip.
     */
    public static boolean skipCompileSync(RunMode.Value mode) {
        return false;
    }

    /** Skip {@code TextureAtlas.cycleAnimationFrames}. */
    public static boolean skipTextureAnimation(RunMode.Value mode) {
        return active(mode);
    }

    /** Mip level for atlas stitch. Headless forces 0. Otherwise the caller's level. */
    public static int atlasMipLevel(RunMode.Value mode, int callerMip) {
        return active(mode) ? 0 : callerMip;
    }

    /**
     * Always false. {@code TextureAtlas.upload} still runs. The sampler needs a base atlas, and this stage does
     * not stub the GPU device. Cancelling the upload was not proven crash-free.
     */
    public static boolean skipAtlasUpload(RunMode.Value mode) {
        return false;
    }

    /**
     * Cancel {@code SoundEngine.reload}, which is what calls {@code Library.init} and opens the OpenAL playback
     * device. The {@code SoundEngine} object still exists. {@code play} returns not-started while it was never loaded.
     */
    public static boolean skipSoundDeviceOpen(RunMode.Value mode) {
        return active(mode);
    }

    /** Report sound volume 0. Pairs with {@link #skipSoundDeviceOpen}. */
    public static boolean silenceSound(RunMode.Value mode) {
        return active(mode);
    }
}
