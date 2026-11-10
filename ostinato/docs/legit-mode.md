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

## What is still not built

Shared world knowledge, leases, and splitting caves or strip lanes between bots are Phase 2. This setting does not start that work.

## Tests

| Rule | Test |
| --- | --- |
| `auto` is off in singleplayer, LAN, and when no remote session is shown, and on for a remote session | `LegitModeTest.autoIsOffInSingleplayerLanAndWhenNoRemoteIsShown` |
| `on` and `off` ignore the session | `LegitModeTest.onAndOffIgnoreTheSession` |
| Default is `auto` | `LegitModeTest.defaultSettingIsAuto` |
| `#set` accepts `on`, `off`, and `auto`, and rejects other tokens | `LegitModeTest.setAcceptsOnOffAutoAndRejectsOtherTokens` |

Headless JUnit only. No Minecraft client is launched.

Client crashes, out-of-memory, and a live cave run are **needs a live run**. They are not marked as passed.
