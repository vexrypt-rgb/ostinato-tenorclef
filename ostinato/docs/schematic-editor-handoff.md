# Schematic editor handoff

Updated 25 Nov 2026, 5:39 AM America/Phoenix. Assume you can be cut off at any moment.

## Branch and HEAD

- Worktree: `C:\Users\redfa\Documents\MinecraftDev\schematic-editor-wt`
- Branch: `swarm/schematic-editor` (off `swarm/rts-mode` at `029359b3d`)
- HEAD: `81982c271` plus uncommitted builder deadlock fix + setup.ps1 jar path + this handoff (about to commit, local only)
- Do not edit `rts-wt` (still clean at `029359b3d`)

## What's done and proven

- Design/Live editor in the command center, unit-tested: `scripts\center-app.ps1 test` — 202 tests, 0 failed.
- Harness: `if "editor" in PARTS` in `scripts/rtsl/rtsl.py` (commit `81982c271`). Window at 2600,40, `--layout editor`, no focus, muted clients, Paper 26.3 + Grim, `respawn_radius 0`.

## se-r1 (25 Nov 2026, 5:24:56–5:30:35 AM America/Phoenix) — FAILED completeness, Grim 0

- Command: `python ostinato\scripts\rtsl\rtsl.py se-r1 editor Copper,Azalea`
- Logs: `C:\Users\redfa\Documents\MinecraftDev\swarm-gate-logs\rtsl\se-r1.out` (err empty); `run\se-r1\window.log`; clients `run\client-Copper-052512.log`, `run\client-Azalea-052520.log`
- Fabric jar was still the rts-wt build (4:17 AM). Center-app jar from this worktree (5:24:32 AM).
- Paint: 12-cell 2-layer wall (x=-40..-38, z=42,43, y=-60 and -59). Selected Copper+Azalea. Live leases: Copper 8 cells (`x=-40,-39`), Azalea 4 then 3 after erase.
- Mid-erase of `(-38,-59,43)` after first placed; ost v12→v13; erased cell stayed air. Copper finished all 8 by 5:26:22.
- RESULT: **10/11 cobblestone**, erased air, mid-erase yes, **GRIM FLAGS 0, DEATHS 0**. Window closed, clients/server stopped cleanly. StopAll + `rcon stop`.
- Missing cell: **`(-38,-59,42)`** — Azalea's remaining upper cell next to the hole.
- Azalea placed both bottoms, pathed to `GoalAdjacent{-38,-59,42}`, logged "Wrong Y coordinate" x9, then "All done" at that goal, and never issued `place cell -38,-59,42 clear`. Silent until blueprint stop at 5:30:18.

### Root cause

Stock `BuilderProcess` cannot finish an open-sky second-layer cell when the only floored same-Y stand is gone or forbidden:

1. `placementGoal` checks horizontals before DOWN, so a neighbor-column block (here Copper's `(-39,-59,42)`) becomes GoalAdjacent's forbidden `no`.
2. `allowSameLevel` is false whenever the cell has air above, so standing at y-1 (ground, a vanilla click on the support) is not in-goal. Every remaining in-goal cell is mid-air.
3. `searchForPlacables` skips `dy==1` when the block above the candidate is air, so after she falls back to y=-60 she never clicks the support.

Do not "fix" this only by giving Azalea two columns or erasing a different cell.

## Fix (uncommitted at this writing)

In `BuilderProcess`:

- `placementGoal`: if the support below is a legal against-block, return `GoalAdjacent(pos, pos.below(), true)` so y-1 stands are in-goal and the support is the forbidden cell.
- `searchForPlacables`: still skip an open-sky `dy==1` only when there is *no* support below.
- Cadenza `CellFilter` (cadenza is off in this gate) matches the same rule, so enabling it later does not reintroduce the deadlock.

`scripts/rtsl/setup.ps1` now copies the fabric jar from this worktree (`ostinato/fabric/build/libs`), not a hardcoded `rts-wt` path.

## In progress right now

About to commit the fix (local only), build `:fabric:remapJar` from this worktree, run `setup.ps1`, then

```
python ostinato\scripts\rtsl\rtsl.py se-r2 editor Copper,Azalea
```

Same rules: mute, fullbright, no focus, controls-only, `respawn_radius 0`, local commits only, never `gradlew --stop`. Expect 11/11 + erased cell air + Grim 0 + deaths 0, then close everything.

## Running processes this session started

None right now. Leftover host Java (do not kill): pid 1756 (2:02 AM, JDK 21 Gradle-like), pid 13232 (3:37 AM, Gradle worker on JDK 25). Port 25582 should be free. Will close every client, window, and server se-r2 starts.

## Exact next steps

1. Commit BuilderProcess + setup.ps1 + both handoff copies (local only, no push).
2. `cd ostinato` and `.\gradlew.bat :fabric:remapJar` (JDK 25 toolchain). Never `--stop`.
3. `powershell -ExecutionPolicy Bypass -File scripts\rtsl\setup.ps1` — confirm the jar is the new remap, not the 4:17 AM rts-wt one, and options.txt is muted.
4. Run se-r2 as above; on failure, fix the root cause and rerun (se-r3…).
5. Record Grim/deaths, log paths, and RESULT here; close everything.

## How to run the gate

```
cd C:\Users\redfa\Documents\MinecraftDev\schematic-editor-wt\ostinato
.\gradlew.bat :fabric:remapJar
powershell -ExecutionPolicy Bypass -File scripts\rtsl\setup.ps1
python scripts\rtsl\rtsl.py se-r2 editor Copper,Azalea
```

Redirect stdout/err to `C:\Users\redfa\Documents\MinecraftDev\swarm-gate-logs\rtsl\se-r2.out`.
Muted clients, window at 2600,40 without focus, Paper 26.3 + Grim, `respawn_radius 0`, never `gradlew --stop`.
