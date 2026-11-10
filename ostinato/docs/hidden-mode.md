# Hidden mode

`runMode` chooses how a swarm bot's client window and renderer run. It is off the normal path unless a flag or `#set` turns it on. Pathing, networking, and TenorClef tasks are not switched off by the setting itself.

| Value | What this stage does |
| --- | --- |
| `normal` (default) | The window, rendering, mouse grab, and pathing stay as they were before this setting. |
| `hidden` | The SDL window stays hidden, the GL context stays alive, and rendering is reduced. The render thread still runs. |
| `headless` | Accepted and stored. `RunMode.isHeadless()` is true. True headless (no GL context) is not implemented in this stage, so the window and the renderer stay on the normal policy. |

```
#set runMode normal
#set runMode hidden
#set runMode headless
```

Any other token is rejected. The message says the setting expects `normal`, `hidden`, or `headless`.

## JVM property

The window is created before `baritone/settings.txt` loads. The initial window policy reads only:

```
-Dostinato.runMode=normal|hidden|headless
```

A missing or blank property is `normal`. A known token is copied onto the setting when settings finish loading, so the property wins that initial mode. After that, `#set runMode` changes the mode. An unknown property token is rejected, the window stays on the normal policy, and the loaded setting is left as it was.

## Where it lives

`RunMode.current()`, `isHidden()`, and `isHeadless()` are the helper. Before settings are installed they follow the property. Afterwards they follow the setting.

Window placement, the kept GL context, and the render cuts are described in the gate checklist once those commits are in. Live memory, CPU, tick health, and path comparisons are not run from this unit-test stage.
