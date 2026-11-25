# 3D schematic editor (command center)

Written 25 Nov 2026 (America/Phoenix). Branch `swarm/schematic-editor` off `swarm/rts-mode` at `029359b3d`.

A Design mode and a Live mode in the command-center window, sharing the `ost1` / `Blueprint` model the RTS map
painter and the bots already use. Nothing here talks to the world except through the existing
`<swarmCenterDir>/blueprints/<name>.ost` write and `#blueprint live` (with leases when the build is split).

Hard rules carry over: controls-only inputs on the bots, no packets, local commits only, clients muted and on the
second monitor (`-Dostinato.windowPos=2600,40`) without taking focus, never `gradlew --stop`. The window itself
stays a JDK-only Swing app (no Minecraft types in `center-app`).

## 1. Shared model

- `OstSchematic` is the portable `ost1` text: local coordinates, block names only (`cobblestone`, no
  `minecraft:` prefix, no block-state properties), air omitted, `MAX_EDGE` 512, `MAX_CELLS` 20_000.
- `Blueprint` is the world-anchored editor document: the same cells plus a name, a version, and an origin written
  as a `# blueprint <name> origin x y z version n` comment that `OstSchematic.read` already skips.
- Files: `<swarmCenterDir>/blueprints/<name>.ost` (atomic temp + rename, debounced as today). Status comes back as
  `<name>.status` or `<name>@<bot>.status`. Live start is still `blueprint live <name>` or
  `swarm lease … blueprint live <name> part=…` via `RtsOrders`.

Design and Live edit the same `Blueprint` in memory. Design just does not start a build or color cells from
status files. The war-room map's 2D paint tool remains; it reads and writes the same files.

The in-game `baritone.gui.studio` view is a different renderer (Minecraft GUI). This editor does not depend on
it. Both already speak `ost1`.

## 2. Design mode

A third window layout, `editor` (`CenterLayout.EDITOR`, header label "Editor", `--layout editor`). One card,
`EditorView`, implements `LayoutView`.

The canvas is a software 3D orbit view (CPU raster, no OpenGL): cubes colored with `BlockColors`, painted back to
front with a z-buffer.

- Orbit: left-drag rotates yaw/pitch around a target; wheel dollies; shift+drag or middle-drag pans. The camera
  is a new JDK-only class (`EditorCamera`) with the same orbit math as the in-game `StudioCamera`, copied rather
  than imported so `center-app` stays Minecraft-free.
- Place / erase: left click places the current block on the current layer (or on the hit face of an existing
  cell); right click erases the hit cell. Drags paint a run of cells on that layer. The active Y is a layer
  stepper (`Y` / `h` as on the RTS panel); a "all layers" ghost draws the rest of the volume faintly.
- Block picker: a short palette (cobblestone, oak_planks, glass, dirt, stone, copper_block, …) plus a typed id
  that goes through `Blueprint.block`.
- Save / Load: write or read `.ost` through `Blueprint.write` / `Blueprint.read`. New empty blueprints start at
  a chosen world origin (typed, defaulting to the last painted origin or `0, -60, 0`).
- Import (best effort): `.litematic` and Sponge `.schem` (v1/v2) through a JDK-only gzip NBT reader in
  `center-app`. Palette entries become ost1 ids (strip `minecraft:`, drop `[properties]`, skip air). Files that
  exceed `MAX_CELLS` / `MAX_EDGE`, or whose palette we cannot read, are refused with a footer message. We do not
  import chests, entities, or block-state orientation; a stair comes in as `oak_stairs`. Classic `.schematic`
  (MCEdit) is the same shape of NBT if it falls out easily, otherwise later.

Edits stay local until Save (Design) or until the auto-write used by Live (below). An unsaved Design document is
kept in the view across theme rebuilds, same as typed map fields.

## 3. Live mode

The same canvas and tools, with a Design / Live toggle on the editor card.

- Status files are merged the way the map already merges them. Placed cells render solid, remaining cells as a
  ghost, and a cell the blueprint no longer wants (erased mid-build) drops out of the ghost.
- Every accepted paint or erase rewrites the `.ost` (version++, debounce 500 ms). Bots already watch that file
  (`BlueprintBehavior`) and restart the ordinary builder on a new version; cells already right in the world are
  skipped by the builder's own diff. No new bot protocol.
- *Build live* reuses `RtsOrders.live` / `splitBlueprint`: one selected bot gets `blueprint live <name>`; several
  selected bots get one column strip and one region lease each. The editor does not invent a second split.
- *Stop* sends the existing stop / idle path, not a new command.

Proof is a Grim-gated live run (Paper 26.3 + Grim, 0 flags, 0 deaths): the editor window (2600,40, no focus)
paints a small 3D shape in Live, starts the build, erases one cell mid-build, and the bots finish the remaining
cells with the erased cell left air.

## 4. Driving it from tests and the gate

The window already accepts named-component auto lines (`@map|…`, `@set|…`). The editor adds:

```
@set|ed:name|wall
@set|ed:mode|design        (or live)
@set|ed:block|cobblestone
@set|ed:y|-60
@|ed:paint:x1,z1,x2,z2     (current layer; y from ed:y)
@|ed:erase:x,z
@|ed:import|<path>
@|ed:save
@|ed:live                  (Build live, selection already made)
```

`EditorCamera` project / pick, ost1 round-trip, import fixtures, and the auto lines are unit-tested in
`center-app` (`EditorViewTest`, `SchemImportTest`) under `java.awt.headless`.

## 5. Not in this slice

- Block-state editing (facing, half, axis). Import keeps the id only.
- Unifying the in-game Studio renderer with this canvas. They share files, not pixels.
- Remote-PC blueprint sync (same-PC files, as for the rest of the window).
- A 3D view of the war-room terrain itself.

## Status

| Piece | State | Evidence |
|---|---|---|
| Design doc, branch, worktree | written | this file; worktree `schematic-editor-wt` on `swarm/schematic-editor` |
| Design mode (orbit, place/erase, palette, save/load `.ost`) | unit-tested | `EditorViewTest`, `EditorCameraTest`; `center-app` 202 tests, 0 failed; `center-app/build/screens/editor-dark.png` |
| Import `.litematic` / `.schem` (ids only) | unit-tested | `SchemImportTest` (Sponge v2 + litematic fixtures, classic `.schematic` refused) |
| Live mode (status colors, write, Build live, mid-build erase) | unit-tested, not live-proven | `EditorViewTest.liveBuildSendsBlueprintLiveAndStatusColorsPlacedCells`. Grim-gated run still required |
