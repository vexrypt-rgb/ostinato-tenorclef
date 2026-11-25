# Schematic editor handoff

Updated 25 Nov 2026, 5:05 AM America/Phoenix. Assume you can be cut off at any moment.

## Branch and HEAD

- Worktree: `C:\Users\redfa\Documents\MinecraftDev\schematic-editor-wt`
- Branch: `swarm/schematic-editor` (off `swarm/rts-mode` at `029359b3d`)
- HEAD: `029359b3d` (design doc + this handoff about to be committed)
- `rts-wt` (`swarm/rts-mode` at `029359b3d`) is leftover RTS work and must not be edited for this task.

## What's done and proven

- RTS list (leases, split blueprint, storage, armorer, multi-layer paint, mid-build erase) is done on
  `swarm/rts-mode` `029359b3d`. Last clean full run: r11, Grim 0, deaths 0.
- This worktree exists and is clean at that commit. Design doc: `ostinato/docs/schematic-editor.md`.

Nothing of the editor itself is implemented or live-proven yet.

## In progress right now

Writing the design doc and this handoff, then committing both (and mirroring this file to
`headless-bot-logs/phase3/schematic-editor-handoff.md`). Next: implement Design mode in `center-app`
(`EditorCamera`, raster canvas, `EditorView` layout, save/load), with unit tests, then import, then Live mode,
then a Grim-gated live run.

## Uncommitted changes

The two new docs above, until the first commit lands.

## Running processes this session started

None. Two leftover `java` processes were already on the host at 5:00 AM America/Phoenix (pids 1756 started
2:02 AM, 13232 started 3:37 AM). They were not started in this session; identify before killing, and do not
touch them if they belong to someone else. Close every client and server *this* work starts, after each run.
Never `gradlew --stop`.

## Exact next steps

1. Commit `ostinato/docs/schematic-editor.md` and `ostinato/docs/schematic-editor-handoff.md` (local only, no
   push).
2. Add `CenterLayout.EDITOR` and an `EditorView` card in `CenterShell` (header label "Editor", `--layout editor`).
3. `EditorCamera` (orbit / dolly / pan / project / pick) + CPU cube raster + place/erase on the current layer.
4. Palette, typed block id, save/load `.ost` through `Blueprint` / `OstSchematic`.
5. JDK-only `.litematic` / `.schem` import into ost1 (ids only, drop properties).
6. Live mode: merge status files, debounce writes, *Build live* via `RtsOrders`, mid-build erase.
7. `EditorViewTest` / `SchemImportTest`; then a Grim-gated live run (0 flags, 0 deaths).

## How to run the gate (once Live exists)

Same machine rules as RTS: muted clients (`soundCategory_master:0.0` and every other `soundCategory_*` 0.0 in
each run dir *before* launch), window at `2600,40` without focus, Paper 26.3 + Grim, close everything after the
run, never `gradlew --stop`.

Expected shape (to be filled in when the harness exists):

```
powershell -ExecutionPolicy Bypass -File scripts\center-app.ps1 test
# then a live editor part, likely extending rtsl.py or a sibling script, tag e.g. se-r1
```

Clients go on the second monitor. Prove Live with 0 Grim flags and 0 deaths, with log paths recorded here.
