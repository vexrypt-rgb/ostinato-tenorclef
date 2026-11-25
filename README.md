# Ostinato + TenorClef (Minecraft 26.3)

A two-module monorepo for the Minecraft 26.3 line:

- `ostinato/`: the pathing and movement engine, a [Baritone](https://github.com/cabaletta/baritone) fork (LGPL-3.0;
  see `ostinato/LICENSE`). Start with `ostinato/README.md` and `ostinato/USAGE.md`.
- `tenorclef/`: the agent mod that plays the game on top of it, an [AltoClef](https://github.com/MiranCZ/altoclef)
  fork (MIT; see `tenorclef/LICENSE`). Start with `tenorclef/README.md`.

See [LICENSING.md](LICENSING.md) for how the two licences fit together, and [IMPORTED_FROM.md](IMPORTED_FROM.md) for
the source commits of this snapshot.

The two keep their own Gradle builds and licences. They are not merged into one source tree. TenorClef's 26.3 module
compiles against the Ostinato jar built from `ostinato/` in the same checkout, so an API change and its consumer
change land in one commit. TenorClef may only use `baritone.api.*`; `tenorclef/scripts/check-baritone-imports.sh`
enforces this.

The older lines (1.21.11, 1.21.4, 1.16.1) are out of scope here. They stay in the separate Ostinato branches and the
TenorClef repo, and use pinned jars in `tenorclef/libs/`.

## Install (players)

Download the 26.3 jars from this repository's [releases](https://github.com/vexrypt-rgb/ostinato-tenorclef/releases).
Current pair: **Ostinato v1.28.0** (tag `ostinato-v1.28.0`) and **TenorClef v0.29.0** (tag `tenorclef-v0.29.0`).
You need Fabric Loader, Java 25, `ostinato-mc26.3-unoptimized-fabric-1.28.0.jar` and `tenorclef-mc26.3-v0.29.0.jar`
in `mods/`. Older Minecraft lines are still in the [Ostinato](https://github.com/vexrypt-rgb/Ostinato/releases) and
[TenorClef](https://github.com/vexrypt-rgb/TenorClef/releases) repositories.

## What's new in Ostinato v1.28.0 / TenorClef v0.29.0

- **Controls-only input**: the bot plays only through vanilla key presses and visible mouse look. On Paper 26.3 it
  ran the full course with 0 flags under Grim, NoCheatPlus Updated and LightAntiCheat+. Spartan's gravity check also
  flags plain vanilla 26.3, so its flags are a false positive (`ostinato/docs/CONTROLS_ONLY_AUDIT.md`).
- **True headless bot** (experimental): a plain-JVM 26.3 protocol client with no Minecraft client
  (`cd ostinato && ./gradlew runHeadlessBot`). Offline-mode servers only (`ostinato/HEADLESS_BOT_NOTES.md`).
- **Swarm and command center**: signed swarm commands, leases, shared observations, region builds, and the command
  center window with a war room map. Swarm features stay off until you turn them on.
- **Microsoft sign-in** for bot accounts with Ostinato's own Azure app id. It waits on Mojang's approval: until
  then, sign-in stops at the Minecraft step with "app ID awaiting Mojang approval".

Full notes: `ostinato/RELEASE_NOTES.md` and `tenorclef/CHANGELOG.md`.

## Experimental features

Everything experimental ships **off**. Nothing below changes behaviour unless you turn it on:

- Ostinato settings: `presto`, `legato`, `legatoSearch`, `maestro`, `cadenza*`, `secondoEnabled`, `unifiedExecutor`,
  `inputArbitration`, `simEdgeSearch`, `pathingStateIdCache`, `pathingHierarchical`, `verifiedGetToBlock`,
  `predictionTolerance`, `latticeExperiment`, `swarmCommandProtocol` (all `false`).
- Grace Note (fall rescue) runs in `observe` mode unless started with `-Dostinato.graceNote=live`.
- TenorClef: Fugue is opt-in (`@fugue`), and its stall recovery stays off.

Each one has a pre-registered evaluation and a decision record in `ostinato/docs/ostinato2/` (DECISIONS, STATUS) or
the handoff files in `tenorclef/`.

## Build

You need JDK 21 (to run Gradle) and JDK 25 (the 26.3 toolchain).

```bash
# Run Gradle with JDK 21; tell it where JDK 25 is:
export JAVA_HOME=/path/to/jdk-21
GRADLE_ARGS="-Porg.gradle.java.installations.paths=/path/to/jdk-25" scripts/build-263.sh
```

On Windows, run the same script from Git Bash, or the steps by hand:

```powershell
cd ostinato
.\gradlew.bat --no-daemon --max-workers=2 :fabric:build -x test
cd ..\tenorclef
.\gradlew.bat --no-daemon --max-workers=2 "-Porg.gradle.java.installations.paths=C:\path\to\jdk-25" `
  "-PostinatoJar.26.3=..\ostinato\fabric\build\libs\baritone-unoptimized-fabric-<version>.jar" :26.3:build
```

The script does three things:
1. Builds `ostinato` with `:fabric:build` and picks up `baritone-unoptimized-fabric-*.jar`. Proguard needs a JDK with
   `jmods/` (CI has one; some local JDK 25 builds do not). If it fails, the unoptimized jar is still produced and the
   script continues.
2. Runs TenorClef's Baritone import gate.
3. Runs `:26.3:compileJava :26.3:test` in `tenorclef` with `-PostinatoJar.26.3=<that jar>`.

To get the TenorClef mod jar, set `TC_TASKS=":26.3:build"`.

The first TenorClef build downloads `versions/mapping-26.3-1.21.11.txt` (not committed; derived from Mojang's names)
from a pinned TenorClef commit and checks its SHA-256. Offline, copy it in by hand or pass `-Ptc.mapping263.url=<url>`.

Gradle runs with `--no-daemon --max-workers=2`.

## Tests

- Ostinato: `cd ostinato && ./gradlew test -x nativeHost` (about 2,400 headless tests; no Minecraft client).
- TenorClef: `:26.3:test` through the script above.

Live harnesses (lap courses, maze, allblocks) start a real client and are documented next to their results in
`ostinato/docs/` and `tenorclef/docs/`.

## CI

`.github/workflows/release.yml` publishes a GitHub release for each `ostinato-v*` or `tenorclef-v*` tag (notes in
`.github/release-notes/`).

`.github/workflows/ci.yml` runs on every push and PR to `main`:
- Ostinato tests.
- The full script above.

## Contributing

Issues and pull requests are welcome. Keep Baritone-derived changes marked with `// Ostinato:` comments, keep new
experimental behaviour behind a default-off setting, and keep TenorClef on `baritone.api.*`.

## Why not one Gradle composite build

Ostinato and TenorClef use different plugin stacks (TenorClef: Loom + replaymod preprocessor). TenorClef takes
Ostinato as a flat file dependency, not a Maven coordinate, so `includeBuild` dependency substitution has nothing to
replace. Building the jar first, then passing its path to TenorClef, gives the same from-source coupling without
reconciling the two plugin stacks. Switching to a true composite build would mean publishing Ostinato's fabric jar as
a coordinate. That's a possible follow-up.