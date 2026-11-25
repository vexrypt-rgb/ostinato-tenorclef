# Schematic editor handoff

Updated 25 Nov 2026, 5:14 AM America/Phoenix. Assume you can be cut off at any moment.

## Branch and HEAD

- Worktree: `C:\Users\redfa\Documents\MinecraftDev\schematic-editor-wt`
- Branch: `swarm/schematic-editor` (off `swarm/rts-mode` at `029359b3d`)
- HEAD: e4fff0bcc Schematic editor: Design and Live 3D view in the command center, ost1 save/load, litematic/schem import
- `rts-wt` (`swarm/rts-mode` at `029359b3d`) is leftover RTS work and must not be edited for this task.

## What's done and proven

- Design doc: `ostinato/docs/schematic-editor.md` (first commit `65e5ee4f3`).
- Third command-center layout `editor` (`CenterLayout.EDITOR`, header "Editor", `--layout editor`, `#center layout editor`).
- Design mode: CPU orbit view (`EditorCamera` / `EditorCanvas`), place/erase on the current layer (`h` stacks), palette + typed id, save/load `.ost` through `Blueprint` / `OstSchematic`, no file dialogs.
- Import: JDK-only `.litematic` and Sponge `.schem` (v1/v2) via `Nbt` + `SchemImport`. Block-state properties dropped; classic `.schematic` (numeric ids) refused.
- Live mode wiring (not yet Grim-proven): Design/Live toggle, debounce write of `<feed>/blueprints/<name>.ost`, *Build live* reuses `RtsOrders` (`blueprint live` or a leased split), status files color placed cells, mid-build erase is a new ost version (bots already watch the file).
- Auto driver: `@set|ed:name|…`, `@|ed:save`, `@map|ed:paint:x1,z1,x2,z2`, `@map|ed:erase:…`.
- Tests: `powershell -ExecutionPolicy Bypass -File scripts\center-app.ps1 test` — **202 tests, 0 failed**. New: `EditorCameraTest`, `EditorViewTest`, `SchemImportTest`. Screenshot: `center-app/build/screens/editor-dark.png` (and `demo-editor-*.png`).

Nothing is live-proven under Grim yet.

## In progress right now

Just finished the unit-tested editor implementation. Next: a Grim-gated Live run (0 flags, 0 deaths) on Paper 26.3.

## Uncommitted changes

None once this handoff is committed with the editor sources.

## Running processes this session started

None. Two leftover `java` processes were already on the host at session start (pids 1756 at 2:02 AM, 13232 at 3:37 AM America/Phoenix). Not started here; do not kill them if they belong to someone else. Close every client and server *this* work starts, after each run. Never `gradlew --stop`.

## Exact next steps

1. Add an `editor` part to `scripts/rtsl/rtsl.py` (or a sibling `sel.py`) that:
   - starts the muted Paper 26.3 + Grim course the same way as RTS (`respawn_radius 0`, `setworldspawn -65 -60 0`, immediate `tp` onto the pad);
   - launches the command center at `2600,40` with `--layout editor` and `--auto-file`;
   - paints a small 3D shape (`ed:h=2`), presses *Build live* on one or two selected bots, erases one cell mid-build, asserts remaining cells placed and the erased cell air;
   - requires Grim 0 and deaths 0.
2. Run it (`se-r1` or similar), record log paths here, close every client and the server.
3. Only then mark Live as proven in `docs/schematic-editor.md`.

## How to run the gate

Same machine rules as RTS: muted clients (`soundCategory_master:0.0` and every other `soundCategory_*` 0.0 in each run dir *before* launch), window at `2600,40` without focus, Paper 26.3 + Grim, close everything after the run, never `gradlew --stop`.

Unit tests anytime:

```
cd ostinato
powershell -ExecutionPolicy Bypass -File scripts\center-app.ps1 test
```

The live harness does not exist yet; add the `editor` part before the first se-r1 run.
