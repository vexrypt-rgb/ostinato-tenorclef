# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:20 MST. Worktree `C:/Users/redfa/Documents/MinecraftDev/rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed.

## Done and proven
- Round 1 (e783653c0, 6a4f408c2, 3094641ca): roles, RTS panel, blueprint painting/live build, single-client Grim gate
  (`scripts/rts-gate.py`, logs `headless-bot-logs/phase3/gate/client-audit/<tag>`).
- 7cf90227a: region/chest leases in role loops, ChestScheduler, `role deposit` runs beside the role loop,
  split live blueprint (`blueprint live <name> part=`), window In-area/layers/Y-step, `MixinHeadlessKeybinds`
  (headless clicks were dead: LoadingOverlay never cleared without a draw, so handleKeybinds never ran).
- a6f18c821: BotLeaseGuard no longer stops the role when a chest lease ends (r5 bug: both miners died after a
  deposit); MixinHeadlessTickBurst caps headless frames at 2 ticks (drops stall debt so Grim never sees a speed
  burst); StoragePlan + RoleBehavior.storageTick; PacketLog wall notes.
- Live r6 (04:03-04:13 MST, jar 04:02 = a6f18c821; logs `swarm-gate-logs/rtsl-r6.out`,
  `swarm-gate-logs/rtsl-r6-run/`): **GRIM FLAGS 0, DEATHS 0**, 2 clients (Copper = coordinator + worker, Azalea).
  - (1) leases: overlap region lease refused, decoy + gap bands intact 4/4, coal_ore left 0, chest 28 coal,
    4+4 deposits under the chest scheduler (miners kept going after each chest lease ended).
  - (2)+(6) split live blueprint + mid-build erase: 23/23 cobblestone, erased cell (-25,-59,31) stayed air.
  - (3) storage organizer: sorted in ~5 s (Copper "pass moved 4 stacks" then 0). Harness RESULT said NOT SORTED
    only because the server cuts long `data get` replies; per-index rcon at 04:10 MST:
    [-20]=coal 18 + wheat 5, [-17]=cobblestone 32, [-14]=oak_log 20 + bone 3 = wanted.
    Harness now reads Items[i] one at a time.

## Not done / known problems
- (4) armor maker: coded and unit-tested (ArmorPlanTest 4/4, center-app 187/187), not yet live-proven.
- (5) multi-layer blueprint painting: code + unit tests earlier; live proof still pending.
- docs/rts-mode.md not yet updated.

## In progress right now
- Armorer compiled into jar 04:17 MST (`:fabric:remapJar` green). About to commit, then live r7:
  `python rtsl.py r7 mine,build,storage,armor Copper,Azalea`.

## Uncommitted changes
- ArmorPlan + ArmorPlanTest, RoleBehavior armorTick + CraftScript + Deposit.craft, RoleOrder.line keeps
  armorer chests=, rtsl.py armor part and per-index NBT parse. Will commit with this handoff before r7.

## Processes I started
- None running (r6 stopped at 04:13:29 MST). Gradle daemons left alone (never --stop).
  java 1756 (jdk-21, 02:02) and 13232 (jdk-25, 03:37) were already on the host, not harness clients.

## Next steps
1. Commit the armorer + this handoff, then r7 as above (setup.ps1 first; it WIPES rtsl/run; r6 logs already
   copied aside). Prove (4) under Grim with 0 flags.
2. Live-prove (5) multi-layer painting if r7 is green.
3. Update docs/rts-mode.md.

## How to run the live gate
1. Build: `cd rts-wt/ostinato; $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot';
   .\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1g" --max-workers=2 --offline :fabric:remapJar` (never `--stop`).
2. Harness: `powershell -File swarm-gate-logs/rtsl-setup.ps1` (copy in `scripts/rtsl/setup.ps1`; copies p3w
   templates, sound 0.0, port 25582, newest jar; WIPES `rtsl/run`).
3. Run: `cd swarm-gate-logs/rtsl; python rtsl.py <tag> mine,build,storage,armor Copper,Azalea`. Starts
   server-ac-client (Grim only), 2 headless muted bots (Copper = coordinator), prints RESULT / GRIM FLAGS /
   DEATHS, then stops all.
4. Emergency stop: `powershell -File swarm-gate-logs/rtsl/gate.ps1 -StopAll` and rcon `stop`
   (25589, pw ostinato-place).
