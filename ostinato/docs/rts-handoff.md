# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:10 MST. Worktree `C:\Users\redfa\Documents\MinecraftDev\rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed. HEAD: see `git log -1` (last code commit 7cf90227a).

## Done and proven
- Round 1 (e783653c0, 6a4f408c2, 3094641ca): roles, RTS panel, blueprint painting/live build, single-client Grim gate
  (`scripts/rts-gate.py`, logs `headless-bot-logs/phase3/gate/client-audit/<tag>`).
- 7cf90227a (WIP): region/chest leases in role loops, ChestScheduler, `role deposit` runs beside the role loop,
  split live blueprint (`blueprint live <name> part=`), window In-area/layers/Y-step, `MixinHeadlessKeybinds`.
  - center-app tests: 177/177 green (`scripts\center-app.ps1 test`).
  - Live r4 (`swarm-gate-logs\rtsl\r4.out`): chest lease alternated Azalea/Spelunker gens 1-6, 12 coal deposited
    by open-screen quick-moves, 0 deaths, decoy bands intact. Grim flags 25: Timer/TimerLimit at join (Copper,
    Spelunker) and two mid-run bursts. Packet-log wall marks show the cause: a ~650 ms client stall then 20 ticks in
    0.6 s catch-up (Spelunker t=4780-4820, 03:34:08). Host contention suspected (4 cores, 3 clients + server).
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
- r5 (03:43-03:50 MST, logs `swarm-gate-logstsl-r5.out`, `swarm-gate-logstsl-r5-run\`) results:
  - (2)+(6) split live blueprint + mid-build erase: Copper 12/12 + Azalea 11/11 cells, RESULT 23/23 cobblestone,
    erased cell (-25,-59,31) stayed air, no cell placed twice. Functionally PROVEN (2 clients).
  - (1) mining: overlap lease refused, decoy + gap bands 4/4, chest lease scheduled Copper then Azalea x4.
    BUG found: both miners stopped after a deposit - the chest lease's end ran LeaseEndStop (stop processes + cancel
    path). Fixed: BotLeaseGuard.leaseEnded ignores CHEST keys (LeaseEndStopTest.aChestLeaseEnding...). Unit green.
  - Grim 7 flags, all Azalea 03:47:45/57 (Timer x5, TimerLimit x2): packetlog shows 20 ticks in 1256 ms then 20 in
    752 ms (main-thread stall, then vanilla catch-up burst). Fix: MixinHeadlessTickBurst caps headless frames at
    HeadlessPolicy.MAX_TICKS_PER_FRAME=2 ticks, dropping the debt (server sees lag, never speed). Harness mine poll 5s->15s.
- NEXT: r6 = `rtsl.py r6 mine,build,storage Copper,Azalea` with the chest-lease fix, tick cap and the new storage part
  (3 chests at -20/-17/-14,-60,26 mixed; want coal+wheat | cobblestone | oak_log+bone).

## Uncommitted changes
- BotLeaseGuard chest-end fix + LeaseEndStopTest; HeadlessPolicy.ticksThisFrame + MixinHeadlessTickBurst (+mixins json)
  + HeadlessPolicyTest; StoragePlan + StoragePlanTest (6 green) + RoleBehavior storage loop; PacketLog wall notes;
  scripts/rtsl (workers arg, robust setup, storage part, gentler poll).

## Processes I started
- None running (r5 driver stopped its clients and server at 03:49:52 MST). Gradle daemons are left alone (never --stop).

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
3. Run: `cd swarm-gate-logs\rtsl; python rtsl.py <tag> mine|build|mine,build [W1,W2] *> <tag>.out`. Starts server-ac-client
   (Grim only), 3 headless muted bots (Copper = coordinator), prints RESULT lines, GRIM FLAGS, DEATHS, then stops all.
4. Emergency stop: `powershell -File swarm-gate-logs\rtsl\gate.ps1 -StopAll` and rcon `stop` (25589, pw ostinato-place).
