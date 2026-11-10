# Hidden mode notes

Conservative readings of the frozen design doc. The design doc was not edited.

Times below are America/Phoenix (MST).

## What this stage does

`runMode` is `normal` (default), `hidden`, or `headless`. The default matches the client from before this branch. `-Dostinato.runMode=` is the window-create policy, because settings load after the SDL window exists. A known token is copied onto the setting once at startup. After that, `#set runMode` wins. An unknown token is rejected. An unknown property does not overwrite a setting that was loaded from disk, and the initial window stays on the normal policy.

`headless` is parsed and stored. On `swarm/hidden-mode` the window and render policies treated it like `normal`. On `swarm/true-headless` it has its own policy: the window stays hidden, the world renderer is skipped, and the OpenAL playback device is not opened. The GPU device is still created. See `HEADLESS_MODE_NOTES.md`.

## Window

26.3 creates the window through SDL (`org.lwjgl.sdl.SDLVideo`), not GLFW. Hidden OR's `SDL_WINDOW_HIDDEN` into the create flags. The GPU backend still creates the GL context for that window. The code does not call `SDL_DestroyWindow`, `Window.close`, or `SDL_RaiseWindow`.

`normal` with no `-Dostinato.windowPos` does not change create flags, hints, show, or mouse grab.

`normal` with `-Dostinato.windowPos=x,y` still creates hidden, moves to that point, and `SDL_ShowWindow`s with `SDL_WINDOW_ACTIVATE_WHEN_SHOWN` and `SDL_WINDOW_ACTIVATE_WHEN_RAISED` set to `0`.

`hidden` creates hidden, does not show, sets the same no-activate hints, and suppresses OS mouse grab so a hidden bot cannot move the user's cursor. `#set runMode hidden` after startup hides on the next client tick. `#set runMode normal`, or `RenderSwap.requestPresent()`, shows at `-Dostinato.windowPos` or the default **2600, 40**, restores the saved size, and does not raise the window. `requestPresent` does not change the stored mode. It is the hook a later render-swap can call. This stage does not build the POV switcher.

The window-create mixin reads `RunMode.windowCreateMode()` only. It does not call `BaritoneAPI`.

## Render cost

The render thread is not skipped. `Minecraft.renderFrame` and `runTick` still run. `GameRenderer.render` still clears, blits, runs post, draws the GUI, and ends the frame. Only `GameRenderer.renderLevel` is cancelled on the in-between frames.

1 fps is the world-draw rate, not the argument to `FramerateLimiter.limitDisplayFPS`. That call sleeps the thread that also pumps ticks. The client applies at most 10 ticks per update (`MAX_TICKS_PER_UPDATE`). Sleeping at 1 Hz would cap the bot near 10 ticks per second. While hidden, the limiter argument is `min(caller, 20)`, or 20 when the caller is unlimited (`<= 0`). A caller cap below 20 is kept. The user's framerate option is not written.

The framebuffer asked for is 16. The 26.3 `Window` clamps to `MIN_WINDOW_WIDTH` 320 and `MIN_WINDOW_HEIGHT` 240, so the SDL window is set to **320x240** while hidden and the previous size is restored on show. Java framebuffer fields are not written by hand. The resize event is what updates them. Whether a hidden window delivers that event is a live-run question.

Skipped while hidden and not presenting:

- `RenderSection.compileAsync` and `resortTransparency` (queued async mesh). On hide, `SectionRenderDispatcher.clearCompileQueue()` drops work that has not started.
- Atlas stitch mip forced to 0 in `SpriteLoader.loadAndStitch`. This only helps if hidden is already on at stitch time, which the JVM property is.
- `TextureAtlas.cycleAnimationFrames` (that is what uploads animation frames).

Kept, because something on the render thread still needs them, or cancelling them can leak or crash:

- `RenderSection.compileSync`. The render thread can block on the section the camera is in. A compile that already owns a buffer is not cancelled.
- `TextureAtlas.upload` / the initial atlas contents. The sampler still needs a base atlas.
- `LevelExtractor.extract` and the rest of the frame. Skipping extract was not proven safe.
- The sound engine. `Options.getFinalSoundSourceVolume` returns 0 while reduced. The engine still ticks. It is not destroyed.

`CachedWorld` prune stays at 1024 blocks. Tightening it would change long-route path quality. TenorClef scan caches are not capped.

## gitignore

`run/` in `.gitignore` and `ostinato/.gitignore` matches any directory named `run`, including `ostinato/src/api/java/baritone/api/run/` and `ostinato/src/test/java/baritone/run/`. `RunMode.java` and `RunModeTest.java` are added with `git add -f` and an explicit path. Do not use `git add -A`.

## Needs a live run

Not measured here. No client was launched.

- Private bytes versus the Phase 0 visible 1.75 baseline: peak about 2341 MB, steady about 2290 MB. The design-doc estimate for a hidden window that still renders is 1200–1800 MB. That estimate is not a result.
- Path quality, tick health, CPU, and stability against that same baseline.
- The window stays hidden, does not take focus, and the GL context still exists.
- Unhiding (`#set runMode normal` or `RenderSwap.requestPresent`) shows a window at 2600,40, or at `-Dostinato.windowPos`, without focus, and world drawing resumes.
- The SDL resize while hidden actually lands on 320x240 in the Java window.
- Chunk meshes, the base atlas, and sound-at-volume-0 do not crash a real session.

## Suite note

2026-11-10 14:40 MST: one full `gradlew test` (1735 tests, 25 skipped) failed `LongRouteSimTest.tripsReachTheGoalOnBothArms` (`fallback:corridor search missed the goal`, 0.617 s) and `PiCheapPathTest.onMatchesOffOnShortAndOneMedium` (`BREAK_PLACE/MEDIUM/0` ok true then false, 20.0 s). Both use wall-clock search budgets. Re-run alone, they passed. They do not read `runMode`.

2026-11-10 14:53 MST: a second full `gradlew test` on the setting commit passed. 1735 completed, 0 failed, 25 skipped. That is the 1729 baseline plus 6 `RunModeTest` methods.

2026-11-10 15:03 MST: a third full `gradlew test` on the combined window and render tree passed. 1747 completed, 0 failed, 25 skipped. That is the 1735 setting-commit count plus 6 HiddenWindowPolicyTest methods and 6 HiddenRenderPolicyTest methods. compileLaunchJava for the SDL and render mixins passed earlier the same day. Mixin application on a live client was not run.