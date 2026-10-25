#!/usr/bin/env bash
# Builds Ostinato (26.3, fabric) from ostinato/, then compiles and tests TenorClef's 26.3 module
# against that freshly built jar. No jar for 26.3 is committed in this repo.
# Extra Gradle args (for example toolchain paths) go in GRADLE_ARGS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_ARGS="${GRADLE_ARGS:-}"
TASKS="${TC_TASKS:-:26.3:compileJava :26.3:test}"

cd "$ROOT/ostinato"
# proguard can fail outside CI; the unoptimized jar is written before it runs, so keep going.
./gradlew --no-daemon --max-workers=2 $GRADLE_ARGS :fabric:build -x test --stacktrace || echo "[mono] ostinato build reported failure; looking for the unoptimized jar anyway"

shopt -s nullglob
jars=("$ROOT"/ostinato/dist/baritone-unoptimized-fabric-*.jar "$ROOT"/ostinato/fabric/build/libs/baritone-unoptimized-fabric-*.jar)
[ ${#jars[@]} -gt 0 ] || { echo "[mono] no baritone-unoptimized-fabric jar produced" >&2; exit 1; }
JAR="$(ls -t "${jars[@]}" | head -1)"
echo "[mono] Ostinato jar: $JAR"

cd "$ROOT/tenorclef"
scripts/check-baritone-imports.sh
./gradlew --no-daemon --max-workers=2 $GRADLE_ARGS "-PostinatoJar.26.3=$JAR" $TASKS --stacktrace
