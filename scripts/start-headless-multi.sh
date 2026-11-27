#!/usr/bin/env bash
set -euo pipefail

# Single-JVM multi-instance headless launcher for ostinato-tenorclef
# Defaults to G1GC with string deduplication, 2 GiB max heap for multi-bot runs, bounded Netty direct memory, chunk radius cap 6, and 150ms login stagger.
# Pass --zgc to switch to Generational ZGC, --accounts-file <path> for offline/online accounts, --control-port <port> for 127.0.0.1 HTTP/WS fleet control, or --stagger-ms <ms> to customize login stagger.
# Override heap sizing with HEAP_MAX / HEAP_INIT environment variables (defaults: HEAP_MAX=2g, HEAP_INIT=256m).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEAP_MAX="${HEAP_MAX:-2g}"
HEAP_INIT="${HEAP_INIT:-256m}"
USE_ZGC=0
EXTRA_ARGS=()

for arg in "$@"; do
  if [[ "$arg" == "--zgc" ]]; then
    USE_ZGC=1
  else
    EXTRA_ARGS+=("$arg")
  fi
done

if [[ "$USE_ZGC" -eq 1 ]]; then
  GC_FLAGS="-XX:+UseZGC -XX:+ZGenerational"
else
  GC_FLAGS="-XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+UseStringDeduplication"
fi

JVM_OPTS=(
  "-Xms${HEAP_INIT}"
  "-Xmx${HEAP_MAX}"
  ${GC_FLAGS}
  "-XX:MaxDirectMemorySize=512m"
  "-Dio.netty.allocator.numHeapArenas=2"
  "-Dio.netty.allocator.numDirectArenas=2"
  "-Dio.netty.allocator.maxOrder=9"
  "-Djava.awt.headless=true"
  "-Dostinato.runMode=headless"
  "-Dostinato.headless.chunkRadius=6"
  "--enable-native-access=ALL-UNNAMED"
)

if [[ -n "${HEADLESS_CP:-}" ]]; then
  exec java "${JVM_OPTS[@]}" -cp "${HEADLESS_CP}" baritone.headless.HeadlessMultiLauncher "${EXTRA_ARGS[@]}"
fi

cd "${ROOT_DIR}/ostinato"
MULTI_ARGS_STR="${EXTRA_ARGS[*]:---bots 4 --host 127.0.0.1 --port 25565 --seconds 600 --stagger-ms 150}"
exec ./gradlew --no-daemon runHeadlessMulti \
  "-PmultiXmx=${HEAP_MAX}" \
  "-PmultiArgs=${MULTI_ARGS_STR}" \
  "-Dostinato.runMode=headless" \
  "-Dostinato.headless.chunkRadius=6"
