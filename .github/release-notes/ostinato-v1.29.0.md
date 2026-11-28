Ostinato v1.29.0 for Minecraft 26.3 (Fabric, Java 25). Pair it with
[TenorClef v0.29.0](https://github.com/vexrypt-rgb/ostinato-tenorclef/releases/tag/tenorclef-v0.29.0)
(unchanged in this release).

For a normal install, put `ostinato-mc26.3-unoptimized-fabric-1.29.0.jar` in `mods/` with Fabric Loader. The
`standalone` jar is obfuscated, and `api` keeps the public API for other mods. Every jar carries the Rust library for
six platforms.

## Highlights

- **RTS mode** in the command-center map: farmer, miner, storage organizer and armor maker roles; region and chest
  leases; split a live blueprint across several bots; paint more than one layer; erase a cell while they build.
  Proven on Paper 26.3 with Grim 2.3.75, **0 flags and 0 deaths**, controls-only (`docs/rts-mode.md`).
- **3D schematic editor** (`--layout editor`): Design mode (orbit, place/erase per layer, palette, save/load `.ost`,
  import `.litematic` and Sponge `.schem`) and Live mode (the same view of in-world progress; edits go out as live
  blueprint changes). A 2-bot Live build with a mid-build erase finished 11/11 with the erased cell left air, Grim 0,
  deaths 0 (`docs/schematic-editor.md`).
- **Builder open-sky place fix.** A stacked schematic cell with air above can be placed from y-1 against the
  support, so a one-column strip still finishes after a mid-build erase removes the only same-Y stand.
- v1.28.0's controls-only input, true headless bot, swarm/command center and Microsoft sign-in are still in this
  release. TenorClef is unchanged at v0.29.0.

## Notes

- Full notes are in [`ostinato/RELEASE_NOTES.md`](https://github.com/vexrypt-rgb/ostinato-tenorclef/blob/ostinato-v1.29.0/ostinato/RELEASE_NOTES.md).
  The older Minecraft lines (1.21.11, 1.21.4, 1.16.1) have no changes and stay in
  [vexrypt-rgb/Ostinato](https://github.com/vexrypt-rgb/Ostinato/releases).
- Please report problems with the jar name and `latest.log`. Ostinato is LGPL-3.0, like Baritone.
