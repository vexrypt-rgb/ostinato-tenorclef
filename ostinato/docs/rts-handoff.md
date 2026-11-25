# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:44 MST. Worktree `C:/Users/redfa/Documents/MinecraftDev/rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed. HEAD 3538cb205 plus this final handoff/docs update.

## Done and proven
All six RTS list items are live under Grim with 0 flags.

- Round 1 (e783653c0, 6a4f408c2, 3094641ca): roles, RTS panel, single-layer window paint + live build.
- 7cf90227a: region/chest leases, ChestScheduler, `role deposit` beside the loop, split live blueprint,
  window In-area/layers/Y-step, MixinHeadlessKeybinds.
- a6f18c821: chest-lease end no longer stops the role; MixinHeadlessTickBurst (max 2 ticks/frame); StoragePlan.
- a73218d89: ArmorPlan + RoleBehavior armorTick (gather, craft via open-screen clicks, equip, optional deliver).
- f2ef8ae32 / 4d45b591c / 3538cb205: rtsl layers part, pad and join-tp fixes, docs.

Live evidence (America/Phoenix):
- **r6** 04:03-04:13 MST (`swarm-gate-logs/rtsl-r6.out`, `rtsl-r6-run/`): GRIM 0, DEATHS 0.
  (1) overlap lease refused, decoy+gap 4/4, 28 coal, 4+4 chest-scheduled deposits.
  (2)+(6) 23/23 split cells, erased cell air. (3) storage sorted (per-index rcon).
- **r7** 04:20-04:25 MST (`rtsl-r7.out`, `rtsl-r7-run/`, jar 04:17 = a73218d89): GRIM 0, DEATHS 0.
  Same (1)(2)(3)(6); storage RESULT SORTED. (4) Azalea gathered 24 copper_ingot, crafted and equipped
  helmet/chestplate/leggings/boots; "role armorer: set worn" at 04:24:43; RESULT 4/4.
- **r10** 04:40-04:42 MST (`rtsl-r10.out`, `rtsl-r10-run/`): GRIM 0. (5) command-center window at 2600,40
  painted h=2 wall (`blueprint wall 16 blocks`), Y-step erased (-45,-59,41) to 15 cells, Copper built
  15/15 cobblestone, erased cell stayed air. ost1 `center/blueprints/wall.ost` is 4x2x2 minus that cell.
  DEATHS 2 were spawn falls at join (Copper fell out of the world 04:40:59, Azalea fell from a high place
  04:41:08) before the paint; they were then tped onto the pad and built.

Earlier incomplete layers runs: r8 7/15 (Copper void off pad z=37), r9 13/15 (Azalea stalled on 2
upper cells at the lease strip edge; Copper had already built his full 2-layer 8/8).

## Not done / known problems
- Join spawn on this course still kills the bots (void / high fall) before the first safe tp. Does not
  affect Grim flags or the proven loops; worth a spawn-on-course fix if another live run happens.
- docs/rts-mode.md status table is being updated with this commit.

## In progress right now
- Writing the final handoff/docs commit. No live run running.

## Uncommitted changes
- This handoff and docs/rts-mode.md (r10 15/15).

## Processes I started
- None running (r10 stopped 04:42:06 MST). Window closed. java 1756 (jdk-21, 02:02) and 13232
  (jdk-25, 03:37) were already on the host; left alone (never gradlew --stop).

## Next steps
1. Commit this handoff + docs. List (1)-(6) is live-proven under Grim 0 flags.
2. Optional later: land bots on the course before they can fall at join; split-build the 15-cell wall
   without stalling on the two edge cells (r9 leftover).

## How to run the live gate
1. Build: `cd rts-wt/ostinato; $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot';
   .\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1g" --max-workers=2 --offline :fabric:remapJar` (never `--stop`).
   Center-app jar: `powershell -File scripts/center-app.ps1 jar` (JDK 21).
2. Harness: `powershell -File swarm-gate-logs/rtsl-setup.ps1` (WIPES `rtsl/run`; copy prior runs aside first).
3. Run: `cd swarm-gate-logs/rtsl; python rtsl.py <tag> mine,build,storage,armor,layers Copper,Azalea`.
4. Emergency stop: `powershell -File swarm-gate-logs/rtsl/gate.ps1 -StopAll` and rcon `stop`
   (25589, pw ostinato-place). Close the command-center window process too.
