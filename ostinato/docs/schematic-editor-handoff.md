# Schematic editor handoff

Updated 25 Nov 2026, 5:20 AM America/Phoenix. Assume you can be cut off at any moment.

## Branch and HEAD

- Worktree: `C:\Users\redfa\Documents\MinecraftDev\schematic-editor-wt`
- Branch: `swarm/schematic-editor` (off `swarm/rts-mode` at `029359b3d`)
- HEAD: about to commit the `editor` part of `scripts/rtsl/rtsl.py`, then run `se-r1`
- Previous implementation: `e4fff0bcc` (Design/Live/import). Handoff hash note: `c977c17cf`.

## What's done and proven

- Design/Live editor in the command center, unit-tested: `scripts\center-app.ps1 test` — 202 tests, 0 failed.
- Live mode is **not** Grim-proven yet.

## In progress right now

Adding `if "editor" in PARTS` to `ostinato/scripts/rtsl/rtsl.py` (also mirrored to `swarm-gate-logs/rtsl/rtsl.py`).
The part launches the new center-app jar at `2600,40` with `--layout editor`, paints a 2-layer 12-cell wall at
x=-40..-38, z=42,43, Y=-60 and -59, selects both workers, *Build live*, erases one cell after the first block
is placed, and requires 11 cobblestone + erased cell air, Grim 0, deaths 0.

About to: build `ostinato-center-app.jar` from this worktree, run `scripts/rtsl/setup.ps1`, then

```
python ostinato\scripts\rtsl\rtsl.py se-r1 editor Copper,Azalea
```

Logs: `swarm-gate-logs/rtsl/se-r1.out` (and `run/se-r1/window.log`). Clients muted via setup.ps1 options.txt.
Close everything after the run. Never `gradlew --stop`.

## Uncommitted changes

`ostinato/scripts/rtsl/rtsl.py` (editor part) and this handoff, until committed.

## Running processes this session started

None yet. Leftover host Java: pid 1756 (2:02 AM, JDK 21 Gradle-like), pid 13232 (3:37 AM, Gradle worker on JDK 25).
Port 25582 is free. Not killing those. Will close every client, window, and server this run starts.

## Exact next steps

1. Commit the harness + this handoff (local only).
2. `scripts\center-app.ps1 jar` in schematic-editor-wt.
3. `scripts\rtsl\setup.ps1` (fabric jar still from rts-wt; bots do not need a new remapped jar).
4. Run `se-r1` as above; on failure, fix the root cause and rerun (se-r2…).
5. Record Grim/deaths, log paths, and RESULT here; close everything.

## How to run the gate

```
cd C:\Users\redfa\Documents\MinecraftDev\schematic-editor-wt\ostinato
powershell -ExecutionPolicy Bypass -File scripts\center-app.ps1 jar
powershell -ExecutionPolicy Bypass -File scripts\rtsl\setup.ps1
python scripts\rtsl\rtsl.py se-r1 editor Copper,Azalea
```

Redirect stdout/err to `C:\Users\redfa\Documents\MinecraftDev\swarm-gate-logs\rtsl\se-r1.out`.
Muted clients, window at 2600,40 without focus, Paper 26.3 + Grim, `respawn_radius 0`, never `gradlew --stop`.
