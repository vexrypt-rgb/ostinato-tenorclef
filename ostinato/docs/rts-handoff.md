# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:41 MST. Worktree `C:/Users/redfa/Documents/MinecraftDev/rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed. HEAD 4d45b591c plus uncommitted r9 follow-up.

## Done and proven
- a73218d89 armor maker; a6f18c821 chest-lease end + tick-burst cap + StoragePlan.
- Live r6 (`swarm-gate-logs/rtsl-r6.out`, `rtsl-r6-run/`): GRIM 0, DEATHS 0. (1)(2)(3)(6).
- Live r7 (`swarm-gate-logs/rtsl-r7.out`, `rtsl-r7-run/`): GRIM 0, DEATHS 0. Storage SORTED, Azalea 4/4 copper armor.
- Live r8 (`rtsl-r8.out`, `rtsl-r8-run/`): window painted h=2 wall 16 blocks, Y-step erase to 15. Azalea built her
  7 cells (both layers). Copper fell off the pad (z=37). RESULT 7/15, GRIM 0, DEATHS 1.
- Live r9 (`rtsl-r9.out`, `rtsl-r9-run/`): pad fix held. Window again 16 then 15, erased cell air. Copper placed
  all 8 of his 2-layer cells. Azalea placed 5/7 then stalled on -46,-59,40 and -46,-59,41 (strip edge).
  RESULT 13/15, GRIM 0, DEATHS 1 (Azalea died at spawn on join, 04:35:08, before the layers tp).

## Not done / known problems
- (5) painting is proven (ost1 has both Y layers, Y-step erase works, Copper built a full 2-layer strip). Clean
  15/15 still wanted. Next: r10, tp onto the course immediately on join; Build live with one bot (W1) so the
  two edge cells are not at a lease strip boundary.

## In progress right now
- Those two harness fixes are in rtsl.py, not yet committed. About to commit and run r10 layers.

## Uncommitted changes
- rtsl.py: immediate safe tp after join; layers Build live selects only W1. This handoff.

## Processes I started
- None running (r9 stopped 04:38:58 MST). Gradle daemons left alone.

## Next steps
1. Commit, r10 `layers` Copper,Azalea. Expect 15/15, erased air, Grim 0, deaths 0.
2. If green, (1)-(6) are all live-proven.

## How to run the live gate
1. Build: `cd rts-wt/ostinato; $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot';
   .\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1g" --max-workers=2 --offline :fabric:remapJar` (never `--stop`).
   Center-app jar: `powershell -File scripts/center-app.ps1 jar` (JDK 21).
2. Harness: `powershell -File swarm-gate-logs/rtsl-setup.ps1` (WIPES `rtsl/run`).
3. Run: `cd swarm-gate-logs/rtsl; python rtsl.py <tag> layers Copper,Azalea`.
4. Emergency stop: `powershell -File swarm-gate-logs/rtsl/gate.ps1 -StopAll` and rcon `stop`
   (25589, pw ostinato-place). Close the command-center window process too.
