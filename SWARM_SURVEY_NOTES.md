# Swarm survey (first playable slice)

"Have two scouts survey this marked area and show what remains if one disconnects."

Target runtime: Minecraft Java 26.3, interactive Fabric client swarm. **Nothing here was run on a live client** (see
"What was and was not run").

## Entry point and what was missing

`@swarm acquire` -> `SwarmCoordinator.submit` -> `Scheduler.choose` -> `offer` -> `SwarmWorker` -> `AltoClefExecutor`.
That path already reassigns a whole assignment when its agent disconnects (`SwarmCoordinatorTest.deadAgentWorkGoesToAnotherAgent`),
but `Objective` only knew `ACQUIRE`: nothing in it understood an area, a chunk, partial coverage, claimed/observed/unknown
state or unreachable ground. `ExploreProcess` is an unbounded spiral around one origin over that bot's own cache, so two bots
started apart overlap and never stop at the marked area. No distributed bidding was added; the existing leader-centric path
was enough once it could carry a survey.

## What changed

* `@swarm survey [x1 z1 x2 z2]` (default: the Baritone selection), `@swarm survey status [id]`, `@swarm survey stop [id]`.
  Scouts are ordinary `@swarm join` agents with `CAN_SCOUT`. The area is stored in the player's current dimension.
* `swarm/survey/` (no Minecraft types): `SurveyArea`, `SurveyGrid` (regions at most 8x8 chunks, at most 256 per survey, side
  grows to fit, bigger areas are refused), `SurveyBoard` (the four disjoint states below), `SurveyMetrics`, `SurveyScout`,
  `SurveyCommandSpec`.
* `Objective.SURVEY`: one bounded region = one `Assignment`, through the same scheduler, offer/accept, heartbeat, timeout,
  attempt limit and reassignment code as `acquire`. Idle scouts and open regions are matched one best pair at a time (nearest
  unobserved ground, minus a small penalty for sitting beside another scout's region).
* Protocol: `offer kind=survey ... todo=<mask>` carries only the chunks still unobserved when it is sent; scout -> leader `cov`
  (observed chunks per region, throttled to the heartbeat rate, forced before a result); leader -> scout `upd` (chunks other
  scouts have since seen). `reg` now carries the scout's position so the first offer is placed by distance.
* Coverage = chunks the scout's own client had loaded (`SimpleChunkTracker`) in the survey's dimension. An observation tagged
  with another dimension is refused whole and logged. The leader accepts observations from any live scout, including chunks outside
  the region it holds. Nothing is read from the server or from other clients' worlds.
* Verification: a scout's `SUCCEEDED` is accepted only when the merged board has settled its region; otherwise it is
  `TASK_UNVERIFIED` and the region is retried.

## States a player can read

Per chunk, disjoint: **observed** (seen by a client, never un-seen) / **claimed** (unobserved, inside a region a scout holds) /
**unknown** (unobserved, nobody holds it) / **unreachable** (given up, with a reason). Per region: OBSERVED, CLAIMED, UNKNOWN,
UNREACHABLE. `@swarm survey status` prints the counts, one line per unfinished region (holder, chunks left, reason, tries) and a
map (`#` observed, `c` claimed, `.` unknown, `x` unreachable) when the area is at most 64x32 chunks. `@swarm jobs` shows one line
per survey.

## Failure handling (all bounded)

* Scout disconnects (no heartbeat for `heartbeatTimeoutMs`) or dies: claim released, what it already reported stays observed, only
  the remainder is offered again; the scout is not blamed.
* Scout alive but no new chunk and no movement for `surveyStallMs` (120 s): claim expires, the scout is told to stop and is excluded
  from that region (`SURVEY_CLAIM_EXPIRED`).
* A scout gives up on a target chunk after 2 failed walks (`NO_PATH` or `CHUNK_NOT_LOADED`) and reports `UNREACHABLE:...` with the
  chunks it did see. A region is retried by each other capable scout at most once, never beyond `maxAttempts` (3); then what is
  still unknown is marked unreachable with the whole attempt trail as the reason. Seeing a chunk later settles it as observed.
* `@swarm survey stop` cancels every unfinished region (scouts stop walking); observations are kept.
* Cost follows active regions: at most 4 surveys running, 256 regions each; with no idle scout or no open region the scheduler does
  nothing, and a finished survey costs zero messages per tick (tested).

## Measurements

**Simulated chunk world, not a live fixture.** `SurveySim` is a deterministic chunk grid (8-connected cost-weighted paths, a chunk
is seen when within the view radius, 6) with a ground-truth oracle the swarm code never reads. Arm A runs the real
`SwarmCoordinator` + `SwarmWorker` + `SurveyScout`; arm B is a port of `ExploreProcess.closestUncachedChunks` run independently on
each bot from its own start (defaults: `exploreChunkSetMinimumSize=10`, `worldExploringChunkOffset=0`). Same two bots, same starts at
opposite ends of the marked area, same view radius and movement physics. Duplicate travel: each leg of walking since the previous
chunk-boundary crossing is split by what the newly exposed ring held (in-area chunks seen for the first time vs already seen by
anyone); a leg exposing no in-area chunk is reported separately as off-area travel and is **not** counted as duplicate.

| scenario | arm | t95 (s) | duplicate travel @95% (blocks) | off-area @95% | total @95% |
|---|---|---:|---:|---:|---:|
| open-flat 24x24 | coordinated | 75 | 176 | 0 | 773 |
| | existing explore x2 | 248 | 1189 | 826 | 2778 |
| | (ablation: bounded, uncoordinated) | 143 | 1093 | 0 | 1602 |
| strip 40x10 | coordinated | 36 | 0 | 0 | 370 |
| | existing explore x2 | 281 | 1329 | 1386 | 3144 |
| | (ablation) | 33 | 0 | 0 | 370 |
| river-with-ford 32x24 | coordinated | 68 | 110 | 0 | 711 |
| | existing explore x2 | 178 | 966 | 375 | 1982 |
| | (ablation) | 91 | 467 | 0 | 961 |
| lake-and-hills 28x28 | coordinated | 102 | 114 | 9 | 983 |
| | existing explore x2 | 403 | 1906 | 1122 | 4077 |
| | (ablation) | 227 | 644 | 12 | 1407 |
| forest-half 20x32 | coordinated | 79 | 96 | 0 | 645 |
| | existing explore x2 | 296 | 1191 | 801 | 2571 |
| | (ablation) | 119 | 452 | 0 | 910 |

Medians: duplicate travel 110 vs 1191 blocks (coordinated vs existing), time to 95% 75 s vs 281 s. Target met in the simulation:
lower median duplicate travel, no increase in median time to 95%. Read it with these caveats:

* Much of the gap is that the existing explore has no area bound (it spirals through the surrounding world, see off-area) and
  never knows it is finished. The ablation (bounded, nearest-first, but no leader) isolates coordination: against it the
  coordinated survey has lower duplicate travel in four of five scenarios (median 110 vs 467) and is faster in four of five. On the strip there is
  nothing to coordinate: both have 0 duplicate travel and the ablation is slightly faster (33 s vs 36 s). A single bot (open-flat, 1 bot) also reaches 95% in 164 s vs 502 s for existing explore, for the
  same reason.
* A 12 s heartbeat (the live chat link rate) gives identical results: scouts only interact with the leader at region boundaries,
  where observations are flushed before the result. That is a property of this slice, not proof the link is fast enough live.
* The baseline is my port of the explore algorithm, driven by the same simulated physics; it is not Baritone's pathfinder.

## Failover (simulated, open-flat 24x24, scouts A and B)

Cut A with no goodbye when 42% was observed (t=21 s, A held region 13, leader had merged 216 chunks). The claim stayed until
the heartbeat window ran out: A written off at t=37 s (16 s after the cut, window 15 s). Board then showed observed 288 / claimed 4 /
unknown 284 of 576 with the map and per-region lines. A's unfinished region was offered to B (the unit test `disconnectedScoutsClaimExpiresAndOnlyTheRemainderIsReassigned` checks the
offer holds only what the leader had not yet merged), B finished the survey alone at t=174 s (826 blocks walked after the cut), and the result was
checked against the simulator's ground truth: every chunk the board calls observed was really loaded by a client, the observed set
equals the full area and equals the independently computed feasible set; A's bot did not move after the cut. Lake scenario: 36
chunks no scout can see are marked unreachable with reason `NO_PATH` after at most 2 tries per region, exactly the independently
computed unobservable set; the survey ends and stays quiet.

## What was and was not run

* **Unit tests passed** (55 of 55): the existing `SwarmCoordinatorTest` (19) plus `SurveyBoardTest`, `SurveyCommandSpecTest`,
  `SwarmSurveyTest`, `SurveyFailoverTest`, `SurveyAbTrialTest` (36 new). They were compiled with `javac` against JUnit 1.10.2 console
  because Gradle cannot run in the authoring environment (`maven.fabricmc.net` refused, so no Loom/Minecraft).
* **Live fixture unavailable.** No Fabric client, no Minecraft 26.3, no display, no roster/link in the authoring environment. The
  live pieces (`AltoClefSurveyPort`, the survey branch in `AltoClefExecutor`, the `survey` case in `SwarmCommand`, `cov` routing in
  `SwarmRuntime`) were never run. Every external signature they use was checked by hand against the repo sources (AltoClef,
  SimpleChunkTracker, WorldHelper, Debug, and the baritone.api goal/pathing/selection interfaces), and `AltoClefSurveyPort` was
  type-checked with `javac` against stubs that mirror those signatures. `AltoClefExecutor` and `SwarmCommand` were only
  syntax-checked, because they pull in too much of the mod to stub. `SwarmContextIsolationTest`, `SwarmRuntimeLinkTest` and
  `Phase1AtAdapterTest` need the full TenorClef build and were not run.
* Not claimed: headless mode, older versions, real Baritone pathing, chat-link rate limits, real chunk streaming.
* Not done: a center-app map overlay (center-app is unchanged, still free of Minecraft classes), distributed bidding,
  construction-integrated scouting (out of scope for this slice).

Live run, when a fixture exists: two clients `@swarm join`, the leader `@swarm lead`, mark an area with `#sel`, `@swarm survey`,
watch `@swarm survey status`, kill one client, and compare against `@swarm survey status` after the survivor finishes.
