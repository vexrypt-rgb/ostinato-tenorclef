# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:50 MST. Worktree `C:/Users/redfa/Documents/MinecraftDev/rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed.

## Done and proven
- (1)-(6) RTS list items live under Grim 0 flags (r6/r7/r10). See prior section in git 2ed56f160.
- Root cause of join spawn deaths (not yet live-proven): r10 logins were at the leftover layers wall
  (`Copper ... at ([minecraft:overworld]-47.59, -58.0, 41.40)`, Azalea at -45.42, -59.0, 40.75). That
  wall sits inside the course's 14-wide void trench (`fill -58 -64 -60 -45 -61 60 air`, recarved every
  run). client-gate only wiped HBotAc's playerdata, so RTS bots rejoined at last position over the
  trench and fell before the post-join teleport.

## Not done / known problems
- Join-spawn fix is written but not yet live-proven (0 deaths, GRIM 0).
- gate.py seg_stairs (-89.5 -54 0.5) not yet rerun this session.

## In progress right now
- Fixes ready: client-gate.py wipes all playerdata and `setworldspawn -65 -60 0` / `spawnRadius 0`;
  rtsl.py restates that spawn after its fills, logs login coordinates, and moved the layers yard east
  of the trench (`paint -40,40,-37,41`). Next: commit, then `rtsl.py r11 mine,build,storage,armor,layers
  Copper,Azalea`. Then rerun `headless-bot-logs/phase3/gate/gate.py grim <tag>` (stairs tp already -54).

## Uncommitted changes
- ostinato/scripts/rtsl/rtsl.py (spawn + layers yard). This handoff.
- Shared (not in the ostinato repo): `headless-bot-logs/phase3/gate/client-gate.py` playerdata wipe + setworldspawn.

## Processes I started
- None running. java 1756 (jdk-21, 02:02) and 13232 (jdk-25, 03:37) were already on the host.

## Next steps
1. Commit rtsl + handoff, run r11, expect login coords near -65,-60,0, DEATHS 0, GRIM 0, same RESULT lines.
2. `python gate.py grim <tag>` (or at least seg_ladder,seg_stairs,seg_parkour) on server-ac, confirm stairs pass.
3. Update this file with evidence.

## How to run the live gate
1. Build: `cd rts-wt/ostinato; $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot';
   .\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1g" --max-workers=2 --offline :fabric:remapJar` (never `--stop`).
2. Harness: `powershell -File swarm-gate-logs/rtsl-setup.ps1` (WIPES `rtsl/run`).
3. Run: `cd swarm-gate-logs/rtsl; python rtsl.py r11 mine,build,storage,armor,layers Copper,Azalea`.
4. Emergency stop: `powershell -File swarm-gate-logs/rtsl/gate.ps1 -StopAll` and rcon `stop`
   (25589, pw ostinato-place).
