Ostinato v1.28.0 for Minecraft 26.3 (Fabric, Java 25). This is the first Ostinato release from the monorepo. Pair it
with [TenorClef v0.29.0](https://github.com/vexrypt-rgb/ostinato-tenorclef/releases/tag/tenorclef-v0.29.0).

For a normal install, put `ostinato-mc26.3-unoptimized-fabric-1.28.0.jar` in `mods/` with Fabric Loader. The
`standalone` jar is obfuscated, and `api` keeps the public API for other mods. Every jar carries the Rust library for
six platforms.

## Highlights

- **Controls-only input.** The bot plays only through vanilla key presses and one visible mouse step per tick.
  Vanilla sends every gameplay packet: no silent look, no yaw swaps, no sprint or flight writes, and no direct
  attack, use, break or hotbar calls. Clicks wait for a settled view, and bridges hold sneak until the server keeps
  the block. `freeLook`, `blockFreeLook` and `antiCheatCompatibility` no longer change how the bot looks.
- **Anticheat results** on Paper 26.3 with free anticheats on default configs. The full course was ladder, stairs,
  parkour, walk, long goto, 14-block bridge, 5-block pillar, mining and melee. Grim 2.3.75, NoCheatPlus Updated b286
  and LightAntiCheat+ 2.0.2 gave **0 flags**. Spartan 4.0.9.0's gravity check flags unmodded vanilla 26.3 the same
  way, so it's a false positive.
- **True headless bot** (experimental, from source): `cd ostinato && ./gradlew runHeadlessBot -PbotArgs="<host> <port> <name> <seconds>"`.
  It's a plain-JVM 26.3 protocol client with no Minecraft client JVM. It can goto, mine, place, bridge, pillar,
  fight, craft, smelt, use chests, pick up items and equip armor, and it sends only what vanilla would for the same
  inputs. It joins offline-mode servers only.
- **Swarm Phase 2 and 3** (off until you enable the swarm settings): leases and reservations, shared world
  observations, crash-safe retries, chest and container leases, region-leased mining and tunnels, `#swarm` build from
  a schematic, and automatic reports.
- **Command center**: a standalone window with a war room map, bot selection and control groups, area orders, tile
  details, themes and a saved layout (`docs/command-center.md`).
- **Microsoft sign-in** with Ostinato's Azure app id (`f941c94f-04b8-41d2-ba08-5a0318811df1`). **It's waiting on
  Mojang's approval.** Until then, sign-in stops at the Minecraft step with "app ID awaiting Mojang approval".
  Offline accounts and launcher token import still work.
- Also in this release from the betas: legit mode (`legitMode auto`), the signed swarm command protocol, `#swarm spawn`
  and hidden or headless client run modes.

## Notes

- Full notes are in [`ostinato/RELEASE_NOTES.md`](https://github.com/vexrypt-rgb/ostinato-tenorclef/blob/ostinato-v1.28.0/ostinato/RELEASE_NOTES.md).
  The older Minecraft lines (1.21.11, 1.21.4, 1.16.1) have no changes and stay in
  [vexrypt-rgb/Ostinato](https://github.com/vexrypt-rgb/Ostinato/releases).
- Please report problems with the jar name and `latest.log`. Ostinato is LGPL-3.0, like Baritone.