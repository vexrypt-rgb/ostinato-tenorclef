# Ostinato + TenorClef (26.3)

A two-module monorepo for the Minecraft 26.3 line:

- `ostinato/`: the pathing engine (a Baritone fork, LGPL-3.0; see `ostinato/LICENSE`).
- `tenorclef/`: the agent mod (an AltoClef fork, MIT; see `tenorclef/LICENSE`).

The two keep their own Gradle builds and licences. They are not merged into one source tree. TenorClef's 26.3 module compiles against the Ostinato jar built from `ostinato/` in the same checkout, so an API change and its consumer change land in one commit. TenorClef may only use `baritone.api.*`; `tenorclef/scripts/check-baritone-imports.sh` enforces this.

The older lines (1.21.11, 1.21.4, 1.16.1) are out of scope here. They stay in the separate Ostinato branches and the TenorClef repo, and use pinned jars in `tenorclef/libs/`.

## Build

You need JDK 21 (to run Gradle) and JDK 25 (the 26.3 toolchain).

```bash
# Run Gradle with JDK 21; tell it where JDK 25 is:
export JAVA_HOME=/path/to/jdk-21
GRADLE_ARGS="-Porg.gradle.java.installations.paths=/path/to/jdk-25" scripts/build-263.sh
```

The script does three things:
1. Builds `ostinato` with `:fabric:build` and picks up `baritone-unoptimized-fabric-*.jar`. Proguard may fail outside CI; the unoptimized jar is still produced, so the script continues.
2. Runs TenorClef's Baritone import gate.
3. Runs `:26.3:compileJava :26.3:test` in `tenorclef` with `-PostinatoJar.26.3=<that jar>`.

To get the TenorClef mod jar, set `TC_TASKS=":26.3:build"`.

Gradle runs with `--no-daemon --max-workers=2`.

## CI

`.github/workflows/ci.yml` runs on every push and PR to `main`:
- Ostinato tests.
- The full script above.

## Why not one Gradle composite build

Ostinato and TenorClef use different plugin stacks (TenorClef: Loom + replaymod preprocessor). TenorClef takes Ostinato as a flat file dependency, not a Maven coordinate, so `includeBuild` dependency substitution has nothing to replace. Building the jar first, then passing its path to TenorClef, gives the same from-source coupling without reconciling the two plugin stacks. Switching to a true composite build would mean publishing Ostinato's fabric jar as a coordinate. That's a possible follow-up.
