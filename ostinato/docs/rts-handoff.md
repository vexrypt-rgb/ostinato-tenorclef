# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:59 MST. Worktree `C:/Users/<user>/Documents/MinecraftDev/rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed. HEAD 49a3fc64d plus this handoff update.

## Done and proven
- (1)-(6) RTS list items live under Grim 0 flags (r6/r7/r10/r11).
- Join-spawn deaths, root cause and fix:
  The course recarves a 14-wide void trench at x=-58..-45 every run. client-gate only wiped HBotAc
  playerdata, so Copper/Azalea rejoined at the leftover layers wall in that trench (r10: Copper login
  -47.59,-58.0,41.40 then void; Azalea -45.42,-59.0,40.75 then high fall) before any teleport.
  Fix: wipe all playerdata; `setworldspawn -65 -60 0`; 26.3 gamerule `respawn_radius 0` (spawnRadius
  is rejected); layers yard moved east of the trench (`paint -40,40,-37,41`).
- Live r11 (04:48-04:54 MST, `swarm-gate-logs/rtsl-r11.out`, `rtsl-r11-run/`): **GRIM 0, DEATHS 0**.
  Logins on the course (Copper -74.5,-60.0,4.5; Azalea -58.5,-60.0,7.5 — still the default 10-block
  radius because that run used the rejected spawnRadius name). Mine 4/4+4/4, build 23/23 erase air,
  storage SORTED, Azalea 4/4 copper, layers 15/15 erase air. The `respawn_radius 0` rename is in
  49a3fc64d, ready for the next run to land exactly on -65,-60,0.
- Protocol gate `gate.py grim stairs254` (04:57-04:58 MST, `headless-bot-logs/phase3/gate/stairs254/`,
  also `stairs254.out`): seg_stairs used `tp HBotAc -89.5 -54 0.5 -90 0`. All four steps done
  (arrived=true), **0 Grim flags**:
  - seg_ladder 7s, end at y=-54
  - seg_stairs 4s, end at -82.11,-60.0,0.50
  - seg_parkour 5s
  - seg_walk 5s
  Bot quit, server rcon stop, GATE END. No harness processes left.

## Not done / known problems
- r11 still spread ~10 blocks from world spawn (gamerule name typo, now fixed). No further live
  confirmation of the exact-block spawn; residual only.
- Optional earlier leftover: split-building a 15-cell wall can stall on 2 edge cells (r9).

## In progress right now
- Writing this handoff commit. Nothing running.

## Uncommitted changes
- This handoff (and docs mirror).

## Processes I started
- None running. java 1756 (jdk-21, 02:02) and 13232 (jdk-25, 03:37) were already on the host;
  never `gradlew --stop`.

## Next steps
- Optional: one short rtsl join-only or layers run to show login coords exactly at -65,-60,0.
- Otherwise the assigned work is done.

## How to run the live gate
- RTS: `powershell -File swarm-gate-logs/rtsl-setup.ps1` then
  `python swarm-gate-logs/rtsl/rtsl.py <tag> mine,build,storage,armor,layers Copper,Azalea`.
  Emergency: `powershell -File swarm-gate-logs/rtsl/gate.ps1 -StopAll` and rcon stop (25589, ostinato-place).
- Protocol gate: `cd headless-bot-logs/phase3/gate; python gate.py grim <tag> [steps]`
  (server-ac, ports 25572/25579). Emergency: rcon stop on 25579, pw ostinato-place.
