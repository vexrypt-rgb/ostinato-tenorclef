# Legit mode

`legitMode` controls whether ore and block targeting may use blocks the bot has not seen. It is a registered setting, so `#set` works today. The radial menu, the command center, and AI tool calls are not part of this change.

| Value | Effective mode |
| --- | --- |
| `auto` (default) | On only while the client is connected to a remote multiplayer server. Off in singleplayer, off on LAN, and off when no remote connection can be shown. |
| `on` | On, in every session. |
| `off` | Off. Pathing, mining, and targeting stay as they were before this setting. |

```
#set legitMode on
#set legitMode off
#set legitMode auto
```

Any other token is rejected. The message says the setting expects `on`, `off`, or `auto`.

`auto` does not mean "off". A singleplayer or LAN world does not turn the filter on by itself. The owner turns it on with `#set legitMode on` there.

This is separate from `legitMine`. `legitMine` is the older strip-mine explore flag. `legitMode` is the seen-only gate.

The check is `LegitMode.isActive(setting, session)`. Live code reads the session from `LegitSessions.current()`: an integrated server is singleplayer, an integrated server opened to LAN is LAN, and a connection with no integrated server is remote.

## Seen-only filter

While the effective mode is on, Mine and GetToBlock cannot take a block that fails `SeenOnly.knowable`. The ore cache is not read. A block is knowable only when a face is open to air or fluid, a block-grid line from the eye reaches that open cell, and the open cell's light is at least 1. An unknown cell blocks the line. A block that passed the check stays admitted for the rest of that mine or get-to-block request, in memory only.

`SeenOnlyShare.mayShare` is the hook a later world-knowledge service calls. When legit mode is on, it shares a block only if the filter passed. The service is not built here.

When the effective mode is off, `LegitTargets.select` returns the candidate list unchanged.

## What is still not built

Shared world knowledge, leases, and splitting caves or strip lanes between bots are Phase 2. This setting does not start that work.

## Tests

| Rule | Test |
| --- | --- |
| `auto` is off in singleplayer, LAN, and when no remote session is shown, and on for a remote session | `LegitModeTest.autoIsOffInSingleplayerLanAndWhenNoRemoteIsShown` |
| `on` and `off` ignore the session | `LegitModeTest.onAndOffIgnoreTheSession` |
| Default is `auto` | `LegitModeTest.defaultSettingIsAuto` |
| `#set` accepts `on`, `off`, and `auto`, and rejects other tokens | `LegitModeTest.setAcceptsOnOffAutoAndRejectsOtherTokens` |
| A fully buried ore is not knowable | `SeenOnlyTest.buriedOreIsNotKnowable` |
| An exposed, lit ore with a clear line is knowable | `SeenOnlyTest.exposedLitOreWithAClearLineIsKnowable` |
| A wall, darkness, or an unknown cell hides it | `SeenOnlyTest.aWallOrDarknessOrAnUnknownCellHidesTheOre` |
| A corner crossing needs both side cells open | `SeenOnlyTest.aCornerCrossingFailsUnlessBothSideCellsAreOpen` |
| Effective off does not filter; effective on drops the buried ore | `SeenOnlyTest.filterOffKeepsTheBuriedOreAndFilterOnDoesNot` |
| Mine and GetToBlock call that filter and skip the ore cache | `SeenOnlyTest.mineAndGetToBlockCallTheFilter` |
| A later share keeps a block only when legit is off or the block was knowable | `SeenOnlyTest.shareHookAllowsOnlyKnowableBlocksWhileLegitIsOn` |

Headless JUnit only. No Minecraft client is launched.

Client crashes, out-of-memory, and a live cave run are **needs a live run**. They are not marked as passed.
