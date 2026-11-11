# Headless mode notes

Conservative readings of the frozen design doc. The design doc was not edited.

Times below are America/Phoenix (MST). Checked against the 26.3 Fabric merged jar `minecraft-26.3-fabric-merged+fixed-official.jar`, not the 1.21.11 mojmap jar.

## No-GL startup

The design says true headless has no GL context and no window. The 26.3 client, left alone, does this before the first tick:

1. `RenderSystem.initBackendSystem()`, which calls `SdlDebug.init` and `SDL_Init`.
2. `new MonitorManager()`, which calls `SDL_GetDisplays`.
3. For each graphics backend, `GpuBackend.loadLibrary()` then `GpuBackend.createDevice` **before** any `Window`.
4. On success, `RenderSystem.initRenderer(GpuDevice)`.
5. If every backend fails, a message box, then `Window.WindowInitFailed`.
6. `new Window`, which calls `GpuBackend.createWindow`. A handle of 0 throws inside `createWindow`. The constructor then calls `SDL_SetWindowMinimumSize`, and a missing monitor calls `SDL_GetWindowPosition`.

An earlier reading kept the real device and a hidden 320x240 SDL window, because cancelling those calls threw. That is the path the 2026-11-10 live run measured at about 1605 MB private. This branch replaces it.

Reading taken, only when `-Dostinato.runMode=headless` (the window-create property, not a later `#set`):

- `initBackendSystem` returns `System::nanoTime` and does not call SDL.
- `MonitorManager` is told there are no displays.
- `loadLibrary` is a no-op. `createDevice` returns the stub in `HeadlessDevices`. `VulkanBackend.checkBackendAvailable` is not called.
- `initRenderer` still runs, so `RenderSystem.getDevice()` is the stub. `DynamicGpuData` and `SamplerCache` are built against that stub. Cancelling `initRenderer` would leave the device null and crash the first `getDevice()`.
- `Window.createWindow` returns `HeadlessPolicy.FAKE_WINDOW_HANDLE` (`1L`) and does not call `GpuBackend.createWindow` or `SDL_SetWindowMinimumSize`. The 8-arg constructor's `SDL_GetPrimaryDisplay` returns 0 and `SDL_GetWindowPosition` reports 0,0. `setMode`, `refreshFramebufferSize`, `setIcon`, `close`, and `updateFullscreenIfChanged` do not call SDL.
- `Main` does not name or record a render thread. `isOnRenderThread` is true so `assertOnRenderThread` does not throw. Hidden and normal still record the thread.
- `Minecraft.close` does not call `SDL_Quit`. The SDL native is not loaded (`NativeLibrariesBootstrap.loadSdl`).

`HeadlessPolicy.keepsRealGpuDevice()` is false for that process. `skipAtlasUpload`, `skipGameRenderer`, `skipRenderThread`, and `skipCompileSync` are true for the headless mode. A later `#set runMode normal` does not create the device. `skipGpuWorkNow()` stays on.

`normal` with `-Dostinato.windowPos` still hides, places, and shows without focus. `hidden` is unchanged: hidden SDL window, GL context kept, render thread recorded, 1 fps world draw, present shows at `windowPos` or 2600,40. `compileSync` stays for hidden and normal.

## What the frame still does

`GameRenderer.render` is cancelled. `Minecraft.renderFrame` is not. It still calls `GameRenderer.update` and `GameRenderer.extract`, then the cancelled `render`. The stub surface is never acquired, so blit and present are skipped. `LevelRenderer.endFrame` still runs. The display limiter is `min(caller, 20)`, or 20 when the caller is unlimited.

`FontManager.reload` and `ShaderManager.reload` return `barrier.wait(null)`. That joins the pack-reload barrier and does not upload glyphs or compile shaders. Returning a completed future without `wait` would leave the reload stuck. The `Font` object from before reload can still exist. A tick that measures text may throw. That is not proven here.

`Blaze3D.getTime` is `SDL_GetTicksNS`. Headless uses `System.nanoTime` so the frame clock does not touch SDL. `InputQuirks.isQuitShortcutDown` starts with `SDL_GetModState` and is forced false.

## Calls that can still touch SDL

The known startup and tick callers are stubbed. These are not, and a live client that reaches them can native-crash because `SDL_Init` did not run:

- `Monitor.tryCreate` and `Window.getActiveDisplayMode`, if something other than the cancelled `findBestMonitor` / `getActiveVideoMode` asks for a display.
- `MessageBox`, if every backend failed. The stub device is returned first, so that path should not run.
- `Blaze3D.openUri`.
- `InputConstants` key-name lookup (`SDL_GetKeyName`). Baritone writes its own input and does not need key names for pathing. A screen that builds key labels can still call it.
- `SdlDebug` is skipped because it is the first call inside `initBackendSystem`.

`findBestMonitor` returns null. A video-settings screen that assumes a monitor can throw. The bot does not open that screen.

## Sound

`Library.<init>` does not open a device. `SoundEngine.reload` calls `loadLibrary`, which calls `Library.init`, which calls `openDeviceOrFallback`. `play` returns `NOT_STARTED` when `loaded` is false.

While headless, `SoundEngine.reload` is cancelled, and each client tick calls `SoundManager.destroy()` (a no-op when the engine was never loaded). Leaving headless calls `reload()` once. The `SoundEngine` object is not removed. The constructor still builds a `DeviceTracker`, and that path queries the device list, so the OpenAL native can still load. The playback device is the part that is not opened. Whether `tick` stays healthy with `reload` cancelled is a live-run question. `shouldChangeDevice` can still poll the device list.

## View hook

`ViewCapability.canRenderSwap` is true only for `hidden`. `ViewCapability.canStreamView` is true only for `headless`. `RenderSwap.requestPresent` does nothing unless `canRenderSwap` is true. No socket, no IPC stream, no POV switcher, no command-center button.

## Needs a live run

No client was launched.

- Private bytes. The 0.8–1.1 GB figure is the design-doc estimate for a client with no renderer. This branch's unit tests do not measure it. The 2026-11-10 run of the old stub was about 1605 MB private with a real device and a hidden window. That number is not a result for this path.
- Tick health, path quality against the visible 1.75 baseline (peak about 2341 MB), and a real server join.
- The sound tick after `reload` is cancelled.
- A tick that measures text after the font reload is skipped.
- Any SDL call listed above that a live tick actually reaches.
- Hidden mode still hides, keeps the context, and can show at 2600,40 without focus.

Do not pass `-XX:+UseCompactObjectHeaders` or `-XX:NativeMemoryTracking`. Both broke the 26.3 client in Phase 0.
