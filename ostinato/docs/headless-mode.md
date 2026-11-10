# Headless mode

`runMode headless` is the true-headless path. It is off unless `-Dostinato.runMode=headless` or `#set runMode headless` turns it on. `normal` stays the client from before this setting, including `-Dostinato.windowPos`. `hidden` stays the hidden-window path in [Hidden mode](hidden-mode.md).

```
#set runMode normal
#set runMode hidden
#set runMode headless
```

Any other token is rejected. The JVM property is the mode at window creation. A known token is copied onto the setting once. After that, `#set` wins.

## What headless cuts

The client tick and the render thread still run. `runTick` is not skipped.

| Cut | While `headless` |
| --- | --- |
| Window | Created hidden, never shown, never raised, no OS mouse grab. `windowPos` does not show it. A render-swap present does not show it. |
| World draw | `GameRenderer.renderLevel` is cancelled every frame. `GameRenderer.render` still runs. |
| Display limiter | At most 20, and never higher than the user's cap. Not 1. `limitDisplayFPS` sleeps the tick thread. |
| Chunk meshes | Async builds and transparency resorts are skipped. `compileSync` is kept. |
| Textures | Atlas stitch uses mip 0. Animation uploads stop. The base atlas upload is kept. |
| Sound | `SoundEngine.reload` is cancelled, so the OpenAL playback device is not opened. Volume is reported as 0. The `SoundEngine` object still exists. Leaving headless reloads it once. |

`normal` and `hidden` do not take these flags. Hidden still uses its own 1 fps reducer and still keeps the GL context on purpose.

## What startup still creates

26.3 creates the GPU device before the SDL window. A zero window handle throws inside `Window`, and skipping every backend throws `Window.WindowInitFailed`. Headless does not cancel those calls. The crash-free stub is a real GPU device and a hidden SDL window at the client minimum, 320x240.

`ViewCapability.canRenderSwap` is true only for `hidden`. `ViewCapability.canStreamView` is true only for `headless`. Nothing here opens a socket or draws a stream. A later full stream would send position, view angle, dimension, nearby chunks, entities, health, hunger, and inventory.

The gate checklist is `docs/headless-mode-gate.md`. The conservative readings are in `HEADLESS_MODE_NOTES.md`. Live memory, tick health, path quality, and a real server join were not run. The design-doc estimate of about 0.8–1.1 GB private is not a result. This stub still has a GPU device, so a live run may land closer to the hidden estimate (1200–1800 MB) than to that band.
