# Schematic editor handoff

Updated 25 Nov 2026, 5:48 AM America/Phoenix. Assume you can be cut off at any moment.

## Branch and HEAD

- Worktree: `C:\Users\<user>\Documents\MinecraftDev\schematic-editor-wt`
- Branch: `swarm/schematic-editor` (off `swarm/rts-mode` at `029359b3d`)
- HEAD: `93079aaa1` (builder deadlock fix) plus this handoff / design-doc status update (about to commit, local only)
- Do not edit `rts-wt` (still clean at `029359b3d`)
- Local only; never pushed.

## What's done and proven

- Design/Live editor in the command center, unit-tested: `scripts\center-app.ps1 test` — 202 tests, 0 failed.
- Harness: `if "editor" in PARTS` in `scripts/rtsl/rtsl.py` (`81982c271`). Window at 2600,40, `--layout editor`, no focus.
- **Live mode is Grim-proven (se-r2).**

## se-r2 (25 Nov 2026, 5:46:23-5:48:18 AM America/Phoenix) — PASS

- Command: `python ostinato\scripts\rtsl\rtsl.py se-r2 editor Copper,Azalea` from the worktree root (python pid 15584).
- Logs: `C:\Users\<user>\Documents\MinecraftDev\swarm-gate-logs\rtsl\se-r2.out` (err empty); `run\se-r2\window.log`; clients `run\client-Copper-054639.log`, `run\client-Azalea-054647.log`.
- Fabric jar: this worktree `baritone-fabric-1.28.0.jar` 9971301 bytes, 5:44:15 AM (`93079aaa1` remap). Center-app jar already from this worktree. `setup.ps1` now copies from this worktree; mute verified (`soundCategory_*` all 0.0). `respawn_radius 0` already set. Logins at `-64.5, -60.0, 0.5`.
- Paint: 12-cell 2-layer wall. Selected Copper+Azalea. Live at 5:47:44. Mid-erase of `(-38,-59,43)` at 5:47:52 (after first placements; 8/11 then). Azalea then `place cell -38,-59,42 clear` at 5:47:53 — the cell that hung in se-r1.
- RESULT (5:48:00): **11/11 cobblestone**, erased cell `(-38,-59,43)` is air, mid-erase yes. Window closed 5:48:01. **GRIM FLAGS 0, DEATHS 0**. STOPALL + `rcon stop`, server relay exit 0 at 5:48:18. Port 25582 free. No leftover clients/window.

## se-r1 (25 Nov 2026, 5:24:56-5:30:35 AM America/Phoenix) — FAILED completeness, Grim 0

- Same shape and 2-bot split; fabric jar was still the rts-wt 4:17 AM build.
- RESULT: **10/11**, erased air, mid-erase yes, GRIM 0, DEATHS 0, closed cleanly.
- Missing `(-38,-59,42)` (Azalea's remaining upper cell). She pathed to `GoalAdjacent{-38,-59,42}`, "Wrong Y" x9, "All done", never placed.
- Artifacts preserved at `swarm-gate-logs/rtsl/se-r1-artifacts/` (setup.ps1 wipes `run/`).

### Root cause and fix (`93079aaa1`)

Stock `BuilderProcess` could not finish an open-sky second-layer cell when the only floored same-Y stand was gone or forbidden:

1. `placementGoal` checked horizontals before DOWN, so Copper's `(-39,-59,42)` became GoalAdjacent's forbidden `no`.
2. `allowSameLevel` was false whenever the cell had air above, so standing at y-1 was not in-goal.
3. `searchForPlacables` skipped `dy==1` when the block above the candidate was air.

Fix: if the support below is a legal against-block, `placementGoal` returns `GoalAdjacent(pos, pos.below(), true)`; `searchForPlacables` (and the Cadenza cell filter, cadenza off in this gate) only skip an open-sky `dy==1` when there is no support below.

## Running processes this session started

None left from se-r2. Leftover host Java (do not kill): pid 1756 (2:02 AM, JDK 21 Gradle-like), pid 13232 (3:37 AM, Gradle worker on JDK 25), plus gradle daemons this remap started (pids 376 and 19016 at 5:43 AM). Never `gradlew --stop`.

## Exact next steps

Live editor gate is proven. Remaining optional: nothing required for this request. Keep handoff current if more editor work lands. Local commits only, no push.

## How to rerun the gate

```
cd C:\Users\<user>\Documents\MinecraftDev\schematic-editor-wt\ostinato
.\gradlew.bat :fabric:remapJar
powershell -ExecutionPolicy Bypass -File scripts\rtsl\setup.ps1
python scripts\rtsl\rtsl.py se-r3 editor Copper,Azalea
```

Preserve previous `run/` logs first (`setup.ps1` wipes `run/`). Muted clients, window at 2600,40 without focus, Paper 26.3 + Grim, `respawn_radius 0`, never `gradlew --stop`.
