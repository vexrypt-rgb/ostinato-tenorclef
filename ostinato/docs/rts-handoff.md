# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 03:30 MST. Worktree `C:\Users\redfa\Documents\MinecraftDev\rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed. HEAD: see `git log -1` (last code commit 7cf90227a).

## Done and proven
- Round 1 (e783653c0, 6a4f408c2, 3094641ca): roles, RTS panel, blueprint painting/live build, single-client Grim gate
  (`scripts/rts-gate.py`, logs `headless-bot-logs/phase3/gate/client-audit/<tag>`).
- 7cf90227a (WIP): region/chest leases in role loops, ChestScheduler, `role deposit` runs beside the role loop,
  split live blueprint (`blueprint live <name> part=`), window In-area/layers/Y-step, `MixinHeadlessKeybinds`.
  - center-app tests: 177/177 green (`scripts\center-app.ps1 test`).
  - Live (rtsl r1-r3, `swarm-gate-logs\rtsl\r1.out`, `rtsl-r2.out`, `rtsl\r3.out`): overlapping region lease refused
    ("busy: offered to Azalea"), decoy ore bands outside both regions 4/4 + 4/4 intact, chest lease offered to one bot
    at a time (second waits). Headless bots sent no clicks at all before MixinHeadlessKeybinds (LoadingOverlay never
    cleared without a draw, so handleKeybinds never ran); after it mining works (r3: 32 -> 24 ore).

## Not done / known problems
- Grim **Timer/TimerLimit flags** on headless bots (r2: Copper idle, 6; r3: Azalea x11 burst at 03:23:46, then
  setback). Free phys RAM was ~1.7 GB of 16 GB (4 idle Gradle daemons + 3 clients + server). Not yet root-caused.
- Split blueprint build: both bots loaded their 12-cell parts (r1) but placed nothing - that run was before the
  keybind fix; rerun needed (`rtsl.py <tag> build`).
- (3) storage organizer loop, (4) armor maker loop, (6) erase-mid-build gate step (driver has the erase step in the
  `build` part), (5) layers: code + unit tests done, live proof pending. docs/rts-mode.md not yet updated.

## In progress right now
- Investigating the Grim Timer flags (see top of "Next steps").

## Uncommitted changes
- none besides this file (check `git status`).

## Processes I started
- none running (all rtsl clients and the server-ac-client server stopped 03:26 MST).

## Next steps
1. Timer flags: rerun `rtsl.py r4 mine` with 2 clients if possible / more free RAM; read
   `rtsl\run\packetlog-*.txt` around the flag for a burst of movement packets after a stall.
2. `rtsl.py r5 build`: split blueprint across Azalea+Spelunker + mid-build erase; expect 23/24 cells, erased cell air.
3. Then (3) storage, (4) armorer, docs, final report.

## How to run the live gate
1. Build: `cd rts-wt\ostinato; $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot';
   .\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1g" --max-workers=2 --offline :fabric:remapJar` (never `--stop`).
2. Harness: `powershell -File swarm-gate-logs\rtsl-setup.ps1` (copy in `scripts/rtsl/setup.ps1`; copies p3w
   templates, sound 0.0, port 25582, newest jar; WIPES `rtsl\run`).
3. Run: `cd swarm-gate-logs\rtsl; python rtsl.py <tag> mine|build|mine,build *> <tag>.out`. Starts server-ac-client
   (Grim only), 3 headless muted bots (Copper = coordinator), prints RESULT lines, GRIM FLAGS, DEATHS, then stops all.
4. Emergency stop: `powershell -File swarm-gate-logs\rtsl\gate.ps1 -StopAll` and rcon `stop` (25589, pw ostinato-place).
