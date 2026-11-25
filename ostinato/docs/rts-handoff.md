# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:28 MST. Worktree `C:/Users/redfa/Documents/MinecraftDev/rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed. HEAD a73218d89 plus uncommitted layers harness / docs.

## Done and proven
- Round 1 (e783653c0, 6a4f408c2, 3094641ca): roles, RTS panel, blueprint painting/live build, single-client Grim gate.
- 7cf90227a: region/chest leases, ChestScheduler, `role deposit` beside the loop, split live blueprint, window
  In-area/layers/Y-step, MixinHeadlessKeybinds.
- a6f18c821: chest-lease end no longer stops the role; MixinHeadlessTickBurst (max 2 ticks/frame); StoragePlan.
- a73218d89: ArmorPlan + RoleBehavior armorTick (gather, craft via open-screen clicks, equip, optional deliver).
- Live r6 (logs `swarm-gate-logs/rtsl-r6.out`, `swarm-gate-logs/rtsl-r6-run/`): GRIM 0, DEATHS 0.
  (1) overlap lease refused, decoy+gap 4/4, 28 coal, 4+4 deposits. (2)+(6) 23/23 + erased cell air.
  (3) storage sorted (per-index rcon).
- Live r7 (04:20-04:25 MST, jar 04:17 = a73218d89; logs `swarm-gate-logs/rtsl-r7.out`,
  `swarm-gate-logs/rtsl-r7-run/`): **GRIM FLAGS 0, DEATHS 0**.
  - (1)(2)(6)(3) again: mine decoy+gap 4/4, build 23/23 erase air, storage RESULT SORTED.
  - (4) armor: Azalea gathered 24 copper_ingot from -21,-60,14, crafted helmet/chestplate/leggings/boots
    at the table (-23,-60,14) and equipped each; at 04:24:43 "set worn"; RESULT "Azalea wears 4/4 copper pieces".
    Client log `rtsl-r7-run/client-Azalea-*.log`. Supply chest emptied.

## Not done / known problems
- (5) multi-layer painting: unit test green; rtsl `layers` part written (paints h=2 through CenterApp at 2600,40,
  Y-steps, erases one top cell, Build live with both bots). Not yet run live.
- This handoff, docs/rts-mode.md status, and the layers harness part are uncommitted.

## In progress right now
- About to commit the layers harness + docs, build/confirm center-app jar, then
  `python rtsl.py r8 layers Copper,Azalea`.

## Uncommitted changes
- scripts/rtsl/rtsl.py layers part; docs/rts-mode.md status; this handoff.

## Processes I started
- None running (r7 stopped 04:25:05 MST). Gradle daemons left alone. java 1756 (jdk-21, 02:02) and 13232
  (jdk-25, 03:37) were already on the host.

## Next steps
1. Commit, then r8 layers-only under Grim, 0 flags expected, 15/15 cobblestone and erased cell air.
2. If r8 is green, (1)-(6) are all live-proven.

## How to run the live gate
1. Build: `cd rts-wt/ostinato; $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot';
   .\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1g" --max-workers=2 --offline :fabric:remapJar` (never `--stop`).
   Center-app jar (for layers): `powershell -File scripts/center-app.ps1` (JDK 21).
2. Harness: `powershell -File swarm-gate-logs/rtsl-setup.ps1` (WIPES `rtsl/run`; r7 logs already copied aside).
3. Run: `cd swarm-gate-logs/rtsl; python rtsl.py <tag> mine,build,storage,armor,layers Copper,Azalea`.
4. Emergency stop: `powershell -File swarm-gate-logs/rtsl/gate.ps1 -StopAll` and rcon `stop`
   (25589, pw ostinato-place). Close the command-center window process too.
