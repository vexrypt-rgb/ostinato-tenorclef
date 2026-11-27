# Single-JVM Multi-Instance Headless Architecture Plan (`ostinato-tenorclef`)

> **Vanilla Compliance Invariants (NON-NEGOTIABLE — READ BEFORE EVERY PHASE)**
> 1. **Vanilla Physics & Timing:** Never alter `LocalPlayer` / `ClientPlayerEntity` / `LivingEntity` / `PlayerSim` movement physics, collision shapes, sprint/sneak state transitions, `MultiPlayerGameMode` / `ClientPlayerInteractionManager` / `HeadlessPlayerController` block-break/interact timing, or the exact 20 TPS (50ms) tick cadence.
> 2. **Vanilla C2S Packet Ordering & Mouse Quantization:** Preserve standard vanilla C2S packet ordering (`ServerboundPlayerInputPacket`, `ServerboundPlayerCommandPacket`, `ServerboundMovePlayerPacket`, `ServerboundPlayerActionPacket`, `ServerboundUseItemOnPacket`, `ServerboundSwingPacket`, `ServerboundClientTickEndPacket` strictly last) and standard vanilla mouse-step rotation quantization (`f = sens * 0.6 + 0.2`, `step = f^3 * 8.0 * 0.15`, max 32 deg/tick, 8 deg/tick on climbables).
> 3. **Graphical Mode Integrity:** Guard all new Mixins cleanly behind `HeadlessPolicy` (`-Dostinato.runMode=headless`) and ensure all `BotContext` lookups fall back cleanly to default single-client behavior when unbound, so normal single-client graphical behavior remains 100% intact.
> 4. **Baritone API Boundary:** `tenorclef` may ONLY import `baritone.api.*` (never `baritone.headless.*`, `baritone.Baritone`, `baritone.altoclef.*`, etc.). `tenorclef/scripts/check-baritone-imports.sh` MUST pass at every gate.
> 5. **Circuit-Breaker Rules (MANDATORY):**
>    - Max 3 build/fix retries per gate on the same error.
>    - If `./gradlew build` fails 3 times in a row on the same error, or if refactoring a `static` singleton requires altering vanilla physics/packet invariants, **STOP IMMEDIATELY**, update this file with the exact error trace, conflicting files, and 2-3 architectural options, and hand back.

---

## Step 0 — Audit & Checkpoint Setup
- [x] Create branch `headless/multi-instance` off `main` (`3c0755cde`, which already includes `swarm/legit-mode` commits `0c129e33a`, `11d22a297`, `7e5ee7349`).
- [x] Inspect 26.3 `ClientChunkCache` and `LevelLightEngine` signatures via `javap`.
- [x] Verify baseline Ostinato (`:compileLaunchJava :compileHeadlessJava :fabric:jar`) and TenorClef (`check-baritone-imports.sh` + `:26.3:compileJava`) builds before edits.
- [x] Create `HEADLESS_PLAN.md`.

---

## Phase A — Per-Instance Heap & Hot-Loop Optimization
*(Preserve vanilla physics, collision/reach radius >= 2, and exact A* path outputs.)*

- [x] **A1.1** `ostinato/src/headless/java/baritone/headless/HeadlessChunkStore.java`
  - Replace `ConcurrentHashMap<Long, Chunk>` with primitive `Long2ObjectOpenHashMap<Chunk>` + `ReentrantReadWriteLock`.
  - Replace `List<PalettedContainer<BlockState>>` with `PalettedContainer<BlockState>[]`.
  - Share immutable `EMPTY_AIR_SECTION` sentinel for all-air sections (detect single-value AIR in `readSection` via `ThreadLocal` scratch; copy-on-write on non-air `setBlockState`).
  - Reuse `ThreadLocal<PalettedContainer<Integer>>` scratch biome container in `readSection`.
  - Add configurable `maxChunkRadius` (`-Dostinato.headless.chunkRadius`, default `6`, clamped `>= 2`) enforced in `retainAround`. Add `getChunkDirect(int cx, int cz)`.
- [x] **A1.2** `ostinato/src/headless/java/baritone/headless/HeadlessBot.java`
  - Enforce `maxChunkRadius` pruning in `ClientboundLoginPacket` / `ClientboundSetChunkCacheRadiusPacket` / `ClientboundSetChunkCacheCenterPacket` and `vanillaClientInformation` without ever pruning within radius 2 of the bot.
- [x] **A1.3** `ostinato/src/api/java/baritone/api/Settings.java`, `ostinato/src/main/java/baritone/cache/CachedWorld.java`, `ostinato/src/main/java/baritone/cache/CachedRegion.java`
  - Add `headlessChunkRadiusCap` (default `6`), `headlessMaxCachedRegions` (default `4`), `headlessPruneDistance` (default `256`).
  - Headless pruning in `CachedWorld.prune()` using `headlessPruneDistance` and furthest eviction down to `headlessMaxCachedRegions`.
  - Lazy row allocation (`new CachedChunk[32][]`) in `CachedRegion` and nulling empty rows in `removeExpired()`.
- [x] **A1.4** `ostinato/src/api/java/baritone/api/headless/HeadlessPolicy.java`, `MixinHeadlessChunkCache.java`, `MixinHeadlessLightEngine.java`, `ostinato/src/launch/resources/mixins.baritone.json`
  - Add `HeadlessPolicy.chunkRadiusCap(...)` and `HeadlessPolicy.skipClientLightUpdates(...)` (disabled if `legitMode` is `ON` or `-Dostinato.headless.keepLight=true`).
  - Guard `ClientChunkCache.updateViewRadius(int)` and `LevelLightEngine` (`checkBlock`, `runLightUpdates`, `queueSectionData`, `propagateLightSources`) behind `HeadlessPolicy`.
- [x] **A2.1** `ostinato/src/headless/java/baritone/headless/HeadlessBlockStateInterface.java`
  - Add single-entry chunk cache `(prevChunkX, prevChunkZ, prevLayoutVersion, prevChunk)` for lock-free adjacent `get0(x, y, z)` lookups.
- [x] **A2.2** `ostinato/src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java`, `AStarPathFinder.java`
  - Audited inner loops: Soprano 16x8x16 cell cache (`prevCell` / `piCells`) and `PendingOffer` free-list pooling already active; no avoidable allocations remain.
- [x] **A2.3** `ostinato/src/headless/java/baritone/headless/HeadlessLiveDriver.java`
  - Eliminated per-cell `new BlockPos(x, y, z)` allocations in `mine()` 3D scan using `BlockPos.MutableBlockPos` and primitive `(bestX, bestY, bestZ)`.
- [x] **A3.1** `tenorclef/src/main/java/adris/altoclef/trackers/BlockScanner.java`
  - Use `BlockPos.Mutable` probe inside `scanCloseBlocks()` and allocate `toImmutable()` only when retained in `NearestPerKey`.
  - Amortize 17x16x17 `scanCloseBlocks()`: always scan immediate 5x5x5 shell every tick; run full 17x16x17 sweep on player block move, block-change event, or every 4 ticks.
  - Replace raw `new Thread(...)` in `BlockScanner.tick()` with bounded shared daemon `ExecutorService`.
  - Replace `new LinkedList<>()` in `getKnownLocations` with `new ArrayList<>()`.
- [x] **A3.2** `tenorclef/src/main/java/adris/altoclef/trackers/EntityTracker.java`
  - Reuse backing lists/sets across `updateState()` ticks; eliminate temporary `ItemTarget[]` allocation in `getClosestItemDrop(..., Item...)`.
- [x] **Gate A:** Added unit tests (`HeadlessChunkDecodeTest`, `HeadlessPolicyTest`, `CloseBlockScanCostTest`), passed Ostinato (`:fabric:remapJar :compileHeadlessJava :test --tests 'baritone.headless.*'`, 11 suites / 76 tests, 0 failures), `check-baritone-imports.sh`, and TenorClef (`:26.3:compileJava :26.3:test :26.3:build`, `TC_GATE_A_EXIT=0`).

---

## Phase B — Single-JVM Multi-Instance `BotContext` Isolation & TenorClef Headless Decoupling
*(Re-read `HEADLESS_PLAN.md` before starting.)*

- [x] **B1** `ostinato/src/api/java/baritone/api/context/BotContext.java`, `BotContextScope.java` (`ScopedValue<BotContext>`), `ostinato/src/api/java/baritone/api/headless/IHeadlessHost.java`, `IHeadlessAgent.java`, `IHeadlessAgentFactory.java`
- [x] **B2** Isolate Ostinato static singletons with unbound fallback:
  - `BaritoneAPI`, `Baritone` (`getExecutor()`), `AltoClefSettings`, `WorldProvider`, `LookBehavior` (`lastTurnDeg`), `TickEvent`, `RayTraceUtils`.
  - Create `HeadlessRuntime.java` (shared registries, `BlockState` flyweights, Netty `EventLoopGroupHolder`).
  - Update `HeadlessBot` (`IHeadlessHost`), `HeadlessLiveDriver`, `HeadlessBaritone`, `HeadlessPlayerContext`, `HeadlessLevel`, `HeadlessEntityTracker` to bind `BotContextScope` and expose common `Entity` instances for TenorClef.
- [x] **B3** Isolate TenorClef static singletons & decouple `MinecraftClient.getInstance()`:
  - Create `TenorClefBotContext.java`, `BotClientBridge.java`, `TenorClefHeadlessFactory.java` (+ `META-INF/services/baritone.api.headless.IHeadlessAgentFactory`).
  - Isolate `AltoClef`, `EventBus`, `ConfigHelper`, `BlockScanner.protectedCells`, `ContainerSubTracker`, `FoodChain`, `MobDefenseChain`, `ButlerGuard`, `WhisperChecker`, and task-level statics.
  - Replace `MinecraftClient.getInstance()` call sites across `tenorclef/src/main/java` with `BotClientBridge` (keeping Mixins and strictly-GUI classes cleanly guarded; using `//#if MC >= 260000` for 26.3-only `BotContext`/`IHeadless*` hooks).
  - Add `HeadlessMultiLauncher.java` + `runHeadlessMulti` Gradle task.
- [x] **Gate B:** Add `BotContextIsolationTest` & `TenorClefContextIsolationTest`, run Ostinato + TenorClef builds & tests + `check-baritone-imports.sh`, mark Phase B `[x]`.

---

## Phase C — Vanilla Compliance Audit, Multi-Instance Scheduler & JVM Flags
*(Re-read `HEADLESS_PLAN.md` before starting.)*

- [x] **C1** `ostinato/src/headless/java/baritone/headless/HeadlessTickScheduler.java`: jitter-free 20 TPS (50ms) monotonic scheduler across worker threads, wired into `HeadlessMultiLauncher`.
- [x] **C2** Vanilla compliance enforcement:
  - `VanillaRotationMath.java` & `HeadlessLookBehavior.java`: mouse-sensitivity GCD step quantization + smooth angular caps (`32 deg/tick`, `8 deg/tick` on climbables) + settle check for block interact/place.
  - Audit `HeadlessMovement`, `HeadlessBot`, `HeadlessLiveDriver`, `HeadlessPlayerController`: look-direction movement alignment, C2S packet ordering (`ServerboundClientTickEndPacket` strictly last), vanilla break/interact timing.
  - `HeadlessSeenView.java` + `HeadlessLiveDriver`: `legitMine` line-of-sight raycast enforcement.
- [x] **C3** `scripts/start-headless-multi.sh` & `scripts/start-headless-multi.bat` with G1GC/ZGC, `-XX:+UseStringDeduplication`, Netty bounds, `-Djava.awt.headless=true`.
- [x] **Gate C:** Add `HeadlessVanillaComplianceTest`, run full clean builds & verified test suites across `ostinato` and `tenorclef`, mark Phase C `[x]`.

---

## Checkpoint Log
- **Step 0:** Passed. Branch `headless/multi-instance` created from `main`; `ostinato` baseline (`:compileLaunchJava :compileHeadlessJava :fabric:remapJar`) + `check-baritone-imports.sh` green.
- **Gate A:** Passed (`OST_GATE_A_EXIT=0`, 11 headless suites / 76 tests; `check-baritone-imports.sh` ok; `TC_GATE_A_EXIT=0` `:26.3:compileJava :26.3:test :26.3:build`). Changed 14 files (+596/-144 lines) + added `MixinHeadlessChunkCache.java`, `MixinHeadlessLightEngine.java`.
- **Gate B:** Passed. `ostinato` `BotContextIsolationTest` + `tenorclef` `TenorClefContextIsolationTest` + `check-baritone-imports.sh` (`ok`) + TenorClef `58 suites / 367 tests` (0 failures, 0 errors, 0 skipped) green across `:26.3`, `:1.21.11`, `:1.21.4`.
- **Gate C:** Passed. `ostinato` `:fabric:remapJar :compileHeadlessJava :test -x nativeHost --tests 'baritone.headless.*' --tests 'baritone.gui.SettingCatalogTest' --tests 'baritone.cache.*' --tests 'baritone.behavior.*' --tests 'baritone.api.*'` (`21 suites / 122 tests`, 0 failures, 0 errors, 0 skipped; includes `HeadlessVanillaComplianceTest` & `BotContextIsolationTest`), `check-baritone-imports.sh` (`ok`), and `tenorclef` `:26.3:compileJava :26.3:test :26.3:build` (`58 suites / 367 tests`, 0 failures, 0 errors, 0 skipped) all exited 0.

---

## Live Benchmark & Verification Results

**Date:** 2026-11-27 | **Branch:** `headless/multi-instance` | **JDK:** OpenJDK 25.0.4.1 (Temurin, Windows x86_64)  
**Target Server:** Local vanilla Minecraft `26.3` dedicated server (`127.0.0.1:25570`, `online-mode=false`, `view-distance=6`, `simulation-distance=5`, flat world)  
**JVM Flags (`scripts/start-headless-multi.bat` + NMT):** `-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+UseStringDeduplication -XX:MaxDirectMemorySize=128m -Dio.netty.allocator.numHeapArenas=2 -Dio.netty.allocator.numDirectArenas=2 -Dio.netty.allocator.maxOrder=9 -Djava.awt.headless=true -Dostinato.runMode=headless -Dostinato.headless.chunkRadius=6 -XX:NativeMemoryTracking=summary --enable-native-access=ALL-UNNAMED`

### 1. Memory Benchmark (`--bots 1` vs `--bots 5`, 32s Steady-State, 213 Chunks/Bot)

| Metric | 1 Bot (`BenchBot1`) | 5 Bots (`BenchBot1..5`) | Marginal / Bot `(5 - 1) / 4` |
| --- | ---: | ---: | ---: |
| OS Working Set (RSS) | **343.72 MB** | **343.76 MB** | **+0.01 MB** |
| OS Private Bytes | **379.83 MB** | **382.13 MB** | **+0.58 MB** |
| NMT Total Committed | **352.53 MB** (`360,992 KB`) | **349.36 MB** (`357,742 KB`) | **-0.79 MB** |
| G1 Heap Committed (`GC.heap_info`) | **160.00 MB** (`163,840 KB`) | **152.00 MB** (`155,648 KB`) | **-2.00 MB** |
| G1 Heap Used (pre-GC steady-state) | **124.73 MB** (`127,726 KB`) | **123.73 MB** (`126,702 KB`) | **-0.25 MB** |
| JVM Threads / Stack Committed | 29 threads (`1,300 KB`) | 37 threads (`2,068 KB`) | +2 threads (`+192 KB`) |
| Loaded Chunks Total | 213 | 1,065 (213/bot) | +213 chunks/bot |
| Scheduler Ticks / Errors (40s run) | 792 ticks / 0 errors | 792 ticks / 1 overrun / 0 errors | — |

- **Result:** **PASS** — marginal RSS is **< 1 MB/bot** (well within the `<= 70 MB/bot` target). Because `HeadlessRuntime` shares Bootstrap registries, `BlockState` flyweights, and a single Netty `EventLoopGroupHolder`, and `HeadlessChunkStore` shares the immutable `EMPTY_AIR_SECTION` sentinel across air sections, all 5 concurrent bots (1,065 total loaded chunks) fit inside the initial ~152–160 MB G1 heap without heap expansion.
- **Top `GC.class_histogram` retainers (5-bot JVM):** One-time shared Minecraft registry/bootstrap structures (`[B` 14.08 MB, `[Ljava.lang.Object;` 5.29 MB, `HashMap$Node` 4.84 MB, `String` 3.99 MB, `BlockState` 3.72 MB, `SubShape` 2.70 MB).

### 2. Per-Bot `BotContext` / `ScopedValue` Isolation (`PASS`)

- Launched `IsoBot1` and `IsoBot2` in a single JVM against `127.0.0.1:25570` with distinct `--config-dir` directories and distinct overrides (`legitMine`, `allowParkour`, `allowSprint`, and `AltoClefSettings.avoidBlockBreak((10, -60, 10))` all `true` on `IsoBot1` vs all `false` on `IsoBot2`).
- Dispatched `goto 32 -60 1` to `IsoBot1` only and inspected both bots inside their respective `BotContextScope`:
  - **`IsoBot1`:** `legitMine=true`, `allowParkour=true`, `allowSprint=true`, `avoidBreak10=true`, `configDir=.../IsoBot1`; planned 13-node A* path, moved **12.06 blocks** `(20.67, -60.00, 1.50) -> (32.73, -60.00, 1.50)`, `status=pathing=arrived`.
  - **`IsoBot2`:** `legitMine=false`, `allowParkour=false`, `allowSprint=false`, `avoidBreak10=false`, `configDir=.../IsoBot2`; moved **0.00 blocks** `(6.50, -60.00, -5.50)`, `status=pathing=idle`.

### 3. Vanilla Protocol & Legit-Mode Compliance Under 5-Bot Load (`PASS`)

Captured live C2S packets over real TCP sockets across 5 concurrent bots (`ProtoBot1..5`, `legitMine=true`) for **221 ticks/bot** (1,105 total bot-ticks; 239 scheduler ticks):
- **Scheduler:** `overruns=0`, `avgTickWorkMs=0.891 ms`, `maxDriftMs=0.753 ms`, `errors=0`.

| Bot | Ticks | Mean Interval | p95 / Max Interval | Multi-Move Ticks | Rot Checks | Max Yaw / Pitch Delta | GCD / Slew Violations | `legitMine` LOS (Exposed / Buried Rejected) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
| `ProtoBot1` | 221 | 49.98 ms | 50.39 / 57.97 ms | 0 | 15 | 31.95° / 0.60° | 0 / 0 | `true` / `true` |
| `ProtoBot2` | 221 | 49.98 ms | 50.41 / 56.11 ms | 0 | 16 | 31.95° / 0.60° | 0 / 0 | `true` / `true` |
| `ProtoBot3` | 221 | 49.98 ms | 50.34 / 60.23 ms | 0 | 13 | 0.15° / 0.60° | 0 / 0 | `true` / `true` |
| `ProtoBot4` | 221 | 49.98 ms | 50.42 / 63.28 ms | 0 | 15 | 0.60° / 6.30° | 0 / 0 | `true` / `true` |
| `ProtoBot5` | 221 | 49.97 ms | 50.62 / 53.50 ms | 0 | 58 | 15.90° / 0.75° | 0 / 0 | `true` / `true` |

- Every active movement tick emitted at most one `ServerboundMovePlayerPacket` ending strictly with `ServerboundClientTickEndPacket`.
- All 117 live rotation deltas conformed to vanilla mouse-sensitivity GCD steps (`sens=0.5`) and stayed within `32.0°/tick`.

### 4. Findings & Fixes Applied (Post-Fix Re-Verification `PASS`)

1. **[Resolved] Shared-static race in `PathViz` (`ostinato/src/main/java/baritone/pathing/viz/PathViz.java`):**
   - *Cause:* Under 5-bot concurrent pathing across `headless-tick-worker-1..4`, `KinematicController.drive()` raced on `PathViz.INSTANCE.rolloutCount++` against `rolloutPool[32]`, throwing `ArrayIndexOutOfBoundsException: Index 32 out of bounds for length 32` in `chooseRollout()` and cancelling pathing on 4 of 5 bots.
   - *Fix:* Guarded all `PathViz` toggles (`corridorOn`, `rolloutsOn`, `jumpsOn`, `speedOn`, `inputsOn`, `searchOn`) behind `isHeadlessSuppressed()` (`HeadlessPolicy.skipGpuWorkNow()` or bound headless `BotContext`) and short-circuited `beginRollouts()`, `startRollout()`, and `chooseRollout()` before any buffer writes.
2. **[Resolved] Per-bot `allowParkour` reset in `HeadlessLiveDriver` (`ostinato/src/headless/java/baritone/headless/HeadlessLiveDriver.java`):**
   - *Cause:* `applyClientBotSettings()` unconditionally reset `s.allowParkour.value = false` during `ensureHost()`, clobbering pre-loaded per-bot `BotContext` overrides.
   - *Fix:* Preserved per-bot `Settings.allowParkour` when `BotContextScope.isBound()` (fresh `Settings` instances already default to `false`; `-Dostinato.allowParkour=true` still opts in).
3. **[Resolved] Startup login-burst stagger (`HeadlessMultiLauncher.java`, `scripts/start-headless-multi.bat`, `scripts/start-headless-multi.sh`):**
   - *Fix:* Added `--stagger-ms <ms>` (defaulting to `150 ms` when `--bots > 1`, `0 ms` for 1 bot) and exposed `--stagger-ms 150` in both launcher scripts.

**Post-Fix Live Re-Verification (MC `26.3` at `127.0.0.1:25570`):**
- **Build & Unit Tests:** `:fabric:remapJar :compileHeadlessJava :test -x nativeHost --tests "baritone.headless.*"` — `BUILD SUCCESSFUL` (0 failures).
- **Isolation (`IsoBot1` vs `IsoBot2`):** `ISOLATION_RESULT=PASS` — pre-spawn `allowParkour=true` on `IsoBot1` preserved across `ensureHost()` (`dist=11.78`, `pathing=arrived`), while `IsoBot2` remained `allowParkour=false`, `dist=0.00`, `pathing=idle`.
- **5-Bot Concurrent Pathing & Protocol Compliance (`ProtoBot1..5`, 221 ticks/bot):** `PROTOCOL_COMPLIANCE_RESULT=PASS` — **0** pathing exceptions, **5/5 bots arrived** (`dist=16.26–16.83` blocks each), `overruns=0`, `avgTickWorkMs=0.973 ms`, `maxDriftMs=4.008 ms`, mean interval `49.96–49.97 ms`, **255** live rotation steps checked with **0 GCD / 0 slew violations**, **0** multi-move ticks, `legitMine` LOS `true/true` on all 5 bots.
- **Staggered 5-Bot Launcher (`--bots 5 --stagger-ms 150`):** `ticks=234`, `maxDriftMs=3.78 ms` (down from `155.85 ms` un-staggered), `tickErrors=0`.

### 5. Live End-to-End TenorClef (`AltoClef`) 3-Bot Verification (`PASS`)

Verified against the local MC `26.3` dedicated server (`127.0.0.1:25570`, `online-mode=false`, `view-distance=6`, `spawn-protection=0`) with `Bot1`, `Bot2`, and `Bot3` running concurrently in one JVM with separate `--config-dir` folders and `ServiceLoader`-loaded `adris.altoclef.headless.TenorClefHeadlessFactory`:

- **Scheduler summary (`400` elapsed ticks, `405` total ticks):** `errors=0` (0 `BotClientBridge` NPEs), `avgTickWorkMs=4.785 ms`, `maxDriftMs=43.190 ms`, `overruns=3` (`steadyOverruns=2` on cold JIT warmup).
- **`Bot1` (`@get oak_log 4`):** `connected=true`, `dist=2.14` (`3.99` in cold tree-chop run), `pos=(-5.05, -60.00, -3.41)`, `health=20.0`, `startDestroy=6`, `stopDestroy=4`, `rotChecks=20`, `maxYaw=31.95°`, `maxPitch=31.95°`, `gcdViol=0`, `slewViol=0`, `multiMove=0`, `orderViol=0`, collected `inv={minecraft:oak_log=4}` and returned to `idle`.
- **`Bot2` (`@get dirt 4`):** `connected=true`, `pos=(-8.50, -63.00, -5.30)`, `health=20.0`, `startDestroy=13`, `stopDestroy=4`, `rotChecks=16`, `maxYaw=31.95°`, `maxPitch=31.95°`, `gcdViol=0`, `slewViol=0`, `multiMove=0`, `orderViol=0`, collected `inv={minecraft:dirt=4}` and returned to `idle`.
- **`Bot3` (idle):** remained stationary at `(6.50, -60.00, 9.50)` (`dist=0.00`, `startDestroy=0`, `stopDestroy=0`, `rotChecks=0`, `inv={}`), confirming zero cross-bot interference.

### 6. Dynamic Fleet Control & Multi-Account Session Support

- **Launcher flags (`HeadlessMultiLauncher`, `scripts/start-headless-multi.bat`, `scripts/start-headless-multi.sh`):**
  - Default JVM heap sizing bumped to `HEAP_INIT=256m`, `HEAP_MAX=2g`, and `-XX:MaxDirectMemorySize=512m` (overridable via env vars).
  - `--control-port <port>`: starts `HeadlessControlServer` bound strictly to `127.0.0.1` (`0` picks an ephemeral port).
  - `--accounts-file <path>`: loads offline usernames or online credentials per bot (`--bots <N>` can select a prefix subset; omitted `--bots` launches every entry in the file).
  - `--no-stdin`: disables the interactive console loop for background/CI runs; `--config-dir` acts as an alias for `--config-root`.
- **Interactive stdin commands (`HeadlessFleetController`):**
  - `list` / `ls`, `stats`, `status <bot>`, `cmd <bot|all> <command>` (or `@...` shorthand for `all`), `spawn <user> [host] [port]`, `stop <bot|all>`, `shutdown` / `quit` / `exit`.
- **Local HTTP JSON REST & RFC 6455 WebSocket (`127.0.0.1:<port>`):**
  - `GET /health`, `GET /bots`, `GET /bots/<id>` (includes live health, food, saturation, position/rotation, current task, and inventory slots)
  - `POST /bots/<id|all>/command` (JSON `{"command":"@get oak_log 4"}` or raw text), `POST /bots/spawn`, `POST /bots/<id|all>/stop`, `DELETE /bots/<id|all>`, `POST /shutdown`
  - WebSocket `ws://127.0.0.1:<port>/ws` accepting JSON `{"action":"list|health|status|command|spawn|stop|shutdown", ...}` or console text commands.
- **Supported `--accounts-file` formats (`HeadlessAccount`):**
  - **JSON array or `{"accounts": [...]}`:** elements may be bare username strings (offline) or objects `{"username": "...", "uuid": "...", "accessToken": "..."}` (`uuid` accepts 36-char hyphenated or 32-char Mojang hex and defaults to `OfflinePlayer:<username>`; `accessToken` is optional for offline servers).
  - **CSV / colon-delimited text (`#` / `//` comments ignored):** `username`, `username,uuid`, or `username,uuid,accessToken` (or `:` delimiter; `-` derives offline UUID).
  - **Online-mode servers:** `HeadlessBot` handles `ClientboundHelloPacket` using AES/CFB8 ciphers (`Crypt`, `ServerboundKeyPacket`, `Connection.setEncryptionKey`) and async Mojang `sessionserver/minecraft/join` authentication when `shouldAuthenticate()` is `true` and `accessToken` is supplied.

### 7. 50-Bot High-Density Scale Stress Test (`PASS`)

Run configuration: `--bots 50 --host 127.0.0.1 --port 25570 --prefix StressBot --stagger-ms 150 --seconds 35 --tick-threads 4 --control-port 18080 --no-stdin` with `-Xms256m -Xmx2g -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxDirectMemorySize=512m -Dostinato.headless.chunkRadiusCap=6`.

| Metric | 1-Bot Baseline | 50-Bot Measured (`+28s` Steady State) | Marginal Cost (`49` extra bots) |
| :--- | :---: | :---: | :---: |
| **Connected Bots** | `1 / 1` | **`50 / 50`** (`14.35s` total spawn/login) | — |
| **OS Working Set (RSS)** | `343.72 MB` | **`635.85 MB`** | **`5.96 MB / bot`** (`12.72 MB` avg) |
| **OS Private Bytes** | `379.83 MB` | **`677.64 MB`** | **`6.08 MB / bot`** (`13.55 MB` avg) |
| **JVM NMT Committed** | `352.53 MB` | **`623.53 MB`** (`638,494 KB`) | **`5.53 MB / bot`** |
| **G1 Heap (Committed / Used)** | `160.00 / 124.73 MB` | **`410.00 / 301.71 MB`** | **`5.10 / 3.61 MB / bot`** |
| **Scheduler `avgTickWorkMs`** | — | **`2.081 ms`** (`2.393 ms` at tick 573) | — |
| **Scheduler `maxDriftMs` / Overruns / Errors** | — | **`50.457 ms` / `7` / `0`** (`694` ticks) | — |
| **G1GC Pauses (`gc50.log`)** | — | **`36` pauses, `0` Full GCs** | `max = 26.817 ms`, `avg = 7.499 ms` |

---

## Phase D — Command Center & In-JVM Swarm Coordinator Integration

### Step 0 — Design Doc Audit & Architecture Mapping (`SPEC_swarm_design_doc.md` -> Single-JVM Headless Engine)

| Design Doc Concept (`SWARM_DESIGN_DOC_v3.tsx`) | Existing Command Center / Swarm Component | Headless Fleet Target (`HeadlessControlServer` / `HeadlessFleetController`) |
| --- | --- | --- |
| Read-only status & log view + Mission control wall / War room map (`CenterApp`, `WallView`, `MapView`) | `SwarmSource` (`FeedReader`, `FakeSwarm`) reading `ostinato.center` v1 (`BotFeed`, `CoordinatorFeed`, `CatalogFeed`, `HostInfo`) | `HeadlessControlSource` (`SwarmSource`) streaming via `ws://127.0.0.1:<port>/ws` (`subscribe`) + `GET /center/snapshot` fallback, emitting `ostinato.center` v1 feeds; `CompositeSwarmSource` merges graphical + headless fleets |
| Authenticated command dispatch (`CommandPort`, `CenterRequest`/`CenterReply`, `TileRoute`) | `CenterClient` -> `CenterHost` -> `UnifiedRegistry` / `TileActions` | `HeadlessControlCommandPort` (`CommandPort`) -> `POST /center/request` (or WS `center_request`) -> `HeadlessFleetController.handleCenterRequest(CenterRequest)`; `CompositeCommandPort` routes per target bot |
| Execution & observation split states (`accepted`, `running`, `succeeded`, `failed`, `canceled` + `confirmed`, `stale`, `unknown`) | `BotFeed.Execution`, `CommandProtocol`, `Alerts` | Bounded per-bot execution ledger (`MAX_EXECUTIONS = 8`) in `HeadlessFleetController` tracking `dispatchCommand` & `cancel` with explicit `observation` state |
| Local IPC / in-process bus preferred over rate-limited server chat (`InMemorySwarmBus`, `ISwarm`, `SwarmControl`) | `ISwarm` (`SwarmApi` / `SwarmBehavior`), `SwarmLink.Typed`, `SwarmRuntime`, `SwarmCoordinator`, `SwarmWorker`, `FleetProtocol` | `HeadlessSwarmBus` + `HeadlessSwarmLink` (`ISwarm`) owned by `HeadlessFleetController`, wired into `HeadlessBaritone.getSwarm()`, drained inside `HeadlessBot.tickOnce()` under `BotContextScope`, with `RemoteFallback` for non-local peers |
| Cooperative resource claims, leases & observations (`ReservationKey`, `LeaseCoordinator`, `ReservationTable`) | `ReservationKey.conflicts(...)`, TenorClef `SwarmCoordinator`, `ReservationTable`, `BeliefBoard` | `HeadlessSwarmBus` cooperative claims (`ReservationKey.conflicts`), shared observations, and passive `TCS` (`s1 ...`) observer projecting live assignments/claims into `CoordinatorFeed` (`GET /swarm/status`) |
| Per-bot `BotContext` / `ScopedValue` isolation & safety hierarchy | `BotContextScope`, `TenorClefBotContext`, `SwarmRuntime`, `GoalCommand`, `Fleet` | `TenorClefBotContext`-scoped `SwarmRuntime.State`, `GoalCommand`, `Fleet.members()`, and `AltoClefExecutor` using `BotClientBridge.respawn()` |

### Integration Checklist
- [x] **Step 0 — Audit Design Doc, Command Center & Swarm Architecture:** Audited `SPEC_swarm_design_doc.md`, `COMMAND_CENTER_NOTES.md`, `SWARM_PHASE1_NOTES.md`, `tenorclef/docs/guides/swarm-and-fleet.md`, `CenterApp`, `SwarmSource`, `CommandPort`, `CenterFeed` v1, `ISwarm`, `SwarmRuntime`, `SwarmCoordinator`, `SwarmWorker`, `HeadlessControlServer`, and `HeadlessFleetController`.
- [x] **Phase 1 — Command Center <-> `HeadlessControlServer` Integration:**
  - [x] Enrich `HeadlessCommands`, `HeadlessLiveDriver`, `HeadlessPathingBehavior`, `HeadlessBot`, `HeadlessFleetController`, and `HeadlessControlServer` with `ostinato.center` v1 telemetry (`GET /center/snapshot`, `POST /center/request`, WS `subscribe`/`snapshot`/`center_request`), 2-arg `goto <x> <z>`, `mine [count] <block>`, `InventorySummary`, live route nodes, and execution ledger.
  - [x] Add pure-JDK `HeadlessEndpoint`, `HeadlessControlSource`, `CompositeSwarmSource`, `HeadlessControlCommandPort`, `CompositeCommandPort`, and `--control-port` / `--headless` support in `CenterApp` (preserving `SourceLintTest` and `center-app` JDK-21-only boundary).
- [x] **Phase 2 — In-JVM Swarm Coordinator <-> `HeadlessFleetController` Bridge:**
  - [x] Add `HeadlessSwarmBus` and `HeadlessSwarmLink` (`ISwarm`) with tick-thread `BotContextScope` inbox drain, `ReservationKey` claim conflict detection, shared observations, `TCS` passive projection, remote fallback hook, and `GET /swarm/status` + `POST /swarm/command` + `--swarm-leader` / `--swarm-auto`.
  - [x] Isolate TenorClef `SwarmRuntime`, `GoalCommand`, `Fleet`, `FleetProtocol` (`TCF` over `ISwarm` with whisper fallback), `BotClientBridge.runDirectory()`, and `GameFiles.dir()` per `TenorClefBotContext`, replace `MinecraftClient.getInstance()` in `AltoClefExecutor.alive()` with `BotClientBridge.respawn()`, fix per-bot `Settings` isolation in `HeadlessFleetController.spawnBot`, and wire `@swarm lead|join|leave|acquire|jobs|why|local|cancel` (plus multi-token `StringArg` parsing) in `SwarmCommand`.
- [x] **Phase 3 — Verification, Builds & Live MC 26.3 Multi-Bot Proof:**
  - [x] `center-app` tests (`SourceLintTest` + `HeadlessControlAdapterTest`: `155 tests, 0 failed`), Ostinato headless/swarm/center tests (`HeadlessCenterAndSwarmIntegrationTest`: `380 tests, 0 failed`), `check-baritone-imports.sh` (`ok`), TenorClef `:26.3:compileJava :26.3:test :26.3:build :1.21.11:compileJava :1.21.4:compileJava` (`SwarmContextIsolationTest`, `368 tests` on `:26.3`, `0` failures).
  - [x] Live multi-bot MC `26.3` verification against `127.0.0.1:25570` with Command Center live telemetry/dispatch + in-JVM Swarm work division and `0` protocol/GCD/slew/steady-overrun violations.

### Phase D — Changed & Added Files
- **Ostinato Headless Engine (`ostinato/src/headless/java/baritone/headless/`):** `HeadlessSwarmBus.java` (new), `HeadlessSwarmLink.java` (new), `HeadlessBaritone.java`, `HeadlessBot.java`, `HeadlessCommands.java`, `HeadlessControlServer.java`, `HeadlessFleetController.java`, `HeadlessLiveDriver.java`, `HeadlessMultiLauncher.java`, `HeadlessPathingBehavior.java`, `HeadlessTickScheduler.java`.
- **Ostinato Command Center (`ostinato/src/main/java/baritone/swarm/center/`):** `channel/HeadlessEndpoint.java` (new), `channel/HeadlessControlCommandPort.java` (new), `channel/CompositeCommandPort.java` (new), `feed/HeadlessControlSource.java` (new), `feed/CompositeSwarmSource.java` (new), `app/CenterApp.java`.
- **TenorClef (`tenorclef/src/main/java/adris/altoclef/`):** `commands/GoalCommand.java`, `commands/SwarmCommand.java`, `headless/BotClientBridge.java`, `headless/TenorClefBotContext.java`, `swarm/AltoClefExecutor.java`, `swarm/SwarmLink.java`, `swarm/SwarmRuntime.java`, `tasks/speedrun/testrun2/fleet/Fleet.java`, `tasks/speedrun/testrun2/fleet/FleetProtocol.java`, `tasks/speedrun/testrun2/util/GameFiles.java`.
- **Tests:** `ostinato/center-app/src/test/java/baritone/swarm/center/feed/HeadlessControlAdapterTest.java`, `ostinato/src/test/java/baritone/headless/HeadlessCenterAndSwarmIntegrationTest.java`, `tenorclef/src/test/java/adris/altoclef/swarm/SwarmContextIsolationTest.java`.

### Phase D — Verification & Live MC 26.3 Results (`PASS`)
- **Gate 1 (`ostinato/scripts/center-app.ps1 test`):** `tests: 155, failed: 0` (`SourceLintTest` + `HeadlessControlAdapterTest`).
- **Gate 2 (`ostinato` `:fabric:remapJar :compileHeadlessJava :test -x nativeHost --tests "baritone.headless.*" --tests "baritone.swarm.center.*" --tests "baritone.swarm.transport.*" --tests "baritone.swarm.lease.*"`):** `380 tests, 0 failed` (`BUILD SUCCESSFUL`).
- **Gate 3 (`tenorclef/scripts/check-baritone-imports.sh`):** `baritone import gate: ok (1 allowlisted files still use internals)`.
- **Gate 4 (`tenorclef` `-PostinatoJar.26.3=.../baritone-fabric-1.28.0.jar :26.3:compileJava :26.3:test :26.3:build :1.21.11:compileJava :1.21.4:compileJava`):** `BUILD SUCCESSFUL` (`368` tests on `:26.3`, plus `:1.21.11` & `:1.21.4` compilation).
- **Live MC `26.3` 3-Bot Verification (`127.0.0.1:25570`, `CleanLead` / `CleanA` / `CleanB` with `TenorClefHeadlessFactory` + `legitMine=true`):**
  - Command Center (`HeadlessControlSource` + `HeadlessControlCommandPort` + `CenterShell`) discovered all 3 bots, rendered wall tiles with live position/dimension/health/inventory/process telemetry, executed `PRESS goto` (`GoalXZ`) + `stop`, and verified dynamic `spawn`/`despawn` (`TempBot`).
  - In-JVM Swarm (`@swarm lead` on `CleanLead`, `@swarm join` on `CleanA`/`CleanB`) divided `@swarm acquire dirt 2` (`a1 -> CleanA`) and `@swarm acquire oak_log 2` (`a2 -> CleanB`) concurrently over `HeadlessSwarmBus` (`58/58` local messages delivered, `SWARM_CHAT_PACKETS=0`, both `released` / `progress=100` / `reason=succeeded`).
  - Compliance & timing: `ROT_CHECKS=192`, `GCD_VIOLATIONS=0`, `SLEW_VIOLATIONS=0`, `ORDER_VIOLATIONS=0`, `SWARM_STEADY_TICKS=896`, `SWARM_STEADY_OVERRUNS=0`, `TPS_X10=199`.

