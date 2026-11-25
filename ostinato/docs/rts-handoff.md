# RTS mode handoff (swarm/rts-mode)

Updated: 2026-11-25 04:34 MST. Worktree `C:/Users/redfa/Documents/MinecraftDev/rts-wt`, branch `swarm/rts-mode`
(off main 3c0755cde). Local commits only, never pushed. HEAD f2ef8ae32 plus uncommitted r8 pad tp fix.

## Done and proven
- a73218d89: ArmorPlan + RoleBehavior armorTick. a6f18c821: chest-lease end fix, tick-burst cap, StoragePlan.
- Live r6 (`swarm-gate-logs/rtsl-r6.out`, `rtsl-r6-run/`): GRIM 0, DEATHS 0. (1)(2)(3)(6).
- Live r7 (04:20-04:25 MST, jar 04:17 = a73218d89; `swarm-gate-logs/rtsl-r7.out`, `rtsl-r7-run/`):
  **GRIM 0, DEATHS 0**. Mine, 23/23 split+erase, storage SORTED, Azalea crafted+equipped 4/4 copper armor.
- Live r8 (04:28-04:33 MST, `rtsl-r8.out` — copy to rtsl-r8-run after this note): window at 2600,40 painted
  a 2-layer wall (`blueprint wall 16 blocks`), Y-step + erase to 15 blocks, Build live split Copper/Azalea.
  Azalea placed all 7 of her cells (both Y layers). Erased cell stayed air. **GRIM 0**. NOT complete:
  Copper was teleported to z=37 (off the grass pad z=38-44), died, "Missing materials 8x cobblestone",
  so RESULT 7/15, DEATHS 1. Fix: tp onto the pad at z=40, then give cobblestone.

## Not done / known problems
- (5) paint path itself worked in r8; need a clean 15/15 rerun (r9) after the pad tp fix.

## In progress right now
- Pad tp fix in rtsl.py. Next: copy r8 logs aside, commit the fix, r9 `layers` only.

## Uncommitted changes
- rtsl.py: layers tp -50/-42,-60,40 (on the pad) + 2 s then give cobblestone. This handoff.

## Processes I started
- None running (r8 stopped 04:33:03 MST). Window process closed. Gradle daemons left alone.

## Next steps
1. Save r8 logs, commit the pad fix + handoff, r9 layers. Expect 15/15, erased air, Grim 0, deaths 0.
2. If r9 is green, (1)-(6) are all live-proven.

## How to run the live gate
1. Build: `cd rts-wt/ostinato; $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot';
   .\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1g" --max-workers=2 --offline :fabric:remapJar` (never `--stop`).
   Center-app jar: `powershell -File scripts/center-app.ps1 jar` (JDK 21).
2. Harness: `powershell -File swarm-gate-logs/rtsl-setup.ps1` (WIPES `rtsl/run`).
3. Run: `cd swarm-gate-logs/rtsl; python rtsl.py <tag> layers Copper,Azalea`.
4. Emergency stop: `powershell -File swarm-gate-logs/rtsl/gate.ps1 -StopAll` and rcon `stop`
   (25589, pw ostinato-place). Close the command-center window process too.
