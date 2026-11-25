# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:57 MST. Worktree `C:/Users/redfa/Documents/MinecraftDev/rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed. HEAD 08e9f9f75 plus uncommitted respawn_radius rename.

## Done and proven
- (1)-(6) RTS list items live under Grim 0 flags (r6/r7/r10).
- Join-spawn deaths: root cause was leftover playerdata. The course recarves a void trench at
  x=-58..-45 every run; client-gate only wiped HBotAc, so Copper/Azalea rejoined at the old layers
  wall in that trench (r10: Copper login -47.59,-58.0,41.40 then void; Azalea -45.42,-59.0,40.75 then
  high fall) before any teleport.
  Fix: wipe all playerdata; `setworldspawn -65 -60 0`; layers yard moved east of the trench
  (`paint -40,40,-37,41`). `gamerule spawnRadius` is rejected on 26.3; the name is `respawn_radius`
  (being committed now).
- Live r11 (04:48-04:54 MST, `swarm-gate-logs/rtsl-r11.out`, `rtsl-r11-run/`): **GRIM 0, DEATHS 0**.
  Logins on the course (Copper -74.5,-60.0,4.5; Azalea -58.5,-60.0,7.5 — still a 10-block radius
  because spawnRadius failed; they did not fall). Mine decoy+gap 4/4, build 23/23 erase air,
  storage SORTED, Azalea 4/4 copper armor, layers 15/15 erase air.

## Not done / known problems
- Switch the gamerule to `respawn_radius 0` so they spawn on the exact block, not a 10-block spread
  that can still touch the trench lip (Azalea r11 login x=-58.5).
- gate.py with seg_stairs tp `-89.5 -54 0.5` not yet rerun this session.

## In progress right now
- About to commit the respawn_radius rename, then run
  `python headless-bot-logs/phase3/gate/gate.py grim stairs254 seg_ladder,seg_stairs,seg_parkour,seg_walk`
  (server-ac, ports 25572/25579, headless protocol bot).

## Uncommitted changes
- rtsl.py `respawn_radius`. This handoff.
- Shared: client-gate.py `respawn_radius` (pending patch apply).

## Processes I started
- None running (r11 stopped 04:54:07 MST). java 1756 and 13232 left alone.

## Next steps
1. Commit, run gate.py stairs cluster, record RESULT lines, stop the server-ac process.
2. Update this file with gate evidence.

## How to run the live gate
- RTS: `powershell -File swarm-gate-logs/rtsl-setup.ps1` then
  `python swarm-gate-logs/rtsl/rtsl.py <tag> mine,build,storage,armor,layers Copper,Azalea`.
- Protocol gate: `cd headless-bot-logs/phase3/gate; python gate.py grim <tag> [steps]`
  (never `gradlew --stop`). Emergency: rcon stop on 25579 (pw ostinato-place).
