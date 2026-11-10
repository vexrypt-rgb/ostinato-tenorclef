# Headless mode notes

Conservative readings of the frozen design doc. The design doc was not edited.

Times below are America/Phoenix (MST). Checked against the 26.3 Fabric merged jar `minecraft-26.3-fabric-merged+fixed-official.jar`, not the 1.21.11 mojmap jar.

## No-GL startup does not survive the constructor

The design says true headless has no GL context and no window. The 26.3 client does this before the first tick:

1. `RenderSystem.initBackendSystem()`.
2. For each graphics backend, `GpuBackend.loadLibrary()` then `GpuBackend.createDevice` **before** any `Window`.
3. On success, `RenderSystem.initRenderer(GpuDevice)` and `getDeviceInfo()`.
4. If every backend fails, a message box, then `Window.WindowInitFailed`.
5. `new Window`, which calls `GpuBackend.createWindow(title, width, height, flags)`. The flags constant in this jar is `8224L` (`0x2020`).
6. A handle of 0 throws `IllegalStateException` inside `createWindow`, before the constructor returns.
7. `SDL_SetWindowMinimumSize(handle, 320, 240)`. A missing monitor then calls `SDL_GetWindowPosition`; failure throws.

A stub `GpuDevice` would not stop the real backend object from creating a real SDL window. Replacing the device, the textures, the command encoder, and the SDL calls is a large rewrite. The prompt says to prefer a crash-free stub over that rewrite.

Reading taken: headless still lets `createDevice` and `createWindow` run. The window is created with `SDL_WINDOW_HIDDEN`, resized to 320x240, never shown, never raised, and the OS cursor is not grabbed. `HeadlessPolicy.keepsRealGpuDevice()` is true so tests do not claim the device was skipped. This is not a measured no-GL client. A live run may sit closer to the hidden estimate (1200–1800 MB) than to the no-render estimate (800–1100 MB).

`normal` with `-Dostinato.windowPos` still hides, places, and shows without focus. `hidden` is unchanged: hidden window, GL context kept, 1 fps world draw, present shows at `windowPos` or 2600,40.

## Render thread

`GameRenderer.renderLevel` is cancelled on every headless frame. `GameRenderer.render` (clear, blit, post, gui, end of frame) still runs. `Minecraft.renderFrame` and `runTick` are not cancelled. The display limiter is `min(caller, 20)`, or 20 when the caller is unlimited. It is not 1, for the same tick-sleep reason as hidden mode.

`compileSync` is kept. Async mesh and transparency resort are skipped. Atlas stitch mip is forced to 0. `TextureAtlas.cycleAnimationFrames` is skipped. `TextureAtlas.upload` is kept, because the sampler still needs a base atlas and the GPU device was not stubbed.

`LevelExtractor.extract` is kept. `CachedWorld` stays at 1024 blocks. TenorClef scan caches are not capped.

## Sound

`Library.<init>` does not open a device. `SoundEngine.reload` calls `loadLibrary`, which calls `Library.init`, which calls `openDeviceOrFallback`. `play` returns `NOT_STARTED` when `loaded` is false.

While headless, `SoundEngine.reload` is cancelled, and each client tick calls `SoundManager.destroy()` (a no-op when the engine was never loaded). Leaving headless calls `reload()` once. The `SoundEngine` object is not removed. The constructor still builds a `DeviceTracker`, and that path queries the device list, so the OpenAL native can still load. The playback device is the part that is not opened. Whether `tick` stays healthy with `reload` cancelled is a live-run question. `shouldChangeDevice` can still poll the device list.

## View hook

`ViewCapability.canRenderSwap` is true only for `hidden`. `ViewCapability.canStreamView` is true only for `headless`. `RenderSwap.requestPresent` does nothing unless `canRenderSwap` is true. No socket, no IPC stream, no POV switcher, no command-center button.

## Needs a live run

No client was launched.

- Private bytes. The 0.8–1.1 GB figure is the design-doc estimate for a client with no renderer. This stub still has a GPU device and a hidden window.
- Tick health, path quality against the visible 1.75 baseline (peak about 2341 MB), and a real server join.
- The sound tick after `reload` is cancelled.
- Hidden mode still hides, keeps the context, and can show at 2600,40 without focus.

Do not pass `-XX:+UseCompactObjectHeaders` or `-XX:NativeMemoryTracking`. Both broke the 26.3 client in Phase 0.
