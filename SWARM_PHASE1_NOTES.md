# Phase 1 notes

Conservative readings of architecture v3 where the spec left a choice. The design doc was not edited.

- Ambiguous `@` completion is never stored as `succeeded`. The record stays `running` with observation `unknown`, including when a later poll reports success. Only inline, non-mutating commands (`set`, `help`, `settings`, `echo`, `status`, `reload`, `ping`, `version`, `eta`) may become `succeeded (verified)` with observation `confirmed`.
- A stop that the executing bot has not confirmed is not `canceled` and is not `failed (verified)`. The cancel record stays `running` with observation `unknown`. That covers: still running, unknown command id, bad cancel target, the link being down, and a stop that loses the race to a verified terminal result. A verified `succeeded` or `failed` is not rewritten.
- `canceled (confirmed)` is stored only after `confirmStopped` returns true while the link is still up.
- A dry-run or preview is not a sixth execution state. The sender returns `swarm: preview NAME on TARGET (not executed)` and does not send. A dry-run envelope that still arrives is stored as `failed (verified)`, `started=false`, reason `preview`, so it cannot be mistaken for work that ran.
- Single-bot `mine` / `build` is independent and allowed. A target of `*`, `all`, or a roster group, combined with `worldMutation` or `leaseNeeding`, is coordinated and rejected (reason `coordinated`) unless it is a dry-run preview. `CommandCatalog.infer` never sets `leaseNeeding`.
- Dedup keeps every non-terminal record. Terminal markers expire by TTL (default 600000 ms) and then the oldest terminal entry is evicted one at a time at the 256 cap. After TTL, the same id may run again. The same id with a different payload hash is a tampered replay and does not replace the recorded outcome.
- Command ids are `UUID.randomUUID()`, not a Crockford ULID. The spec allows a sender-scoped UUID or ULID.
- While any outbound `mine`, `place`, `drop`, `pickup`, `craft`, or `use` has observation `unknown`, a new command of those names is refused even without the previous id. Delivery retries reuse the same id and the same body. The first retry waits `retryMs` (2000 ms on the live link).
- An ack that claims a terminal execution with observation other than `confirmed` is stored on the sender as `running` + `unknown`.
- Owner emergency is `stop`, `pause`, `disconnect`, `cancel`, or `forcecancel` with permission `owner`. It is blocked only by immediate safety. It outranks local survival and swarm commands. The catalog `targets` field may say `bot,group,all`; Phase 1 still rejects multi-bot mutation and lease work.
- `swarmCommandProtocol` is the only Phase 1 flag. It defaults to false. There is no second flag. While it is off, `SWC` / `SWA` / `SWH` increment the ignored count the same way an unknown type does, and `#swarm send|outcome|cancel` keep the original invalid-subcommand error.
- Gameplay hellos advertise navigation, mining, building, combat, and inventory. They do not advertise `liveRendering`. An unknown descriptor version is not stored. Missing peer capabilities fail closed.
- TenorClef does not import the new protocol types. It exports `@` commands as catalog text and installs the hook by reflection, so a stock Baritone jar still compiles.
- Live client crashes, OOM, performance, and closing a command-center window need a Phase 1 live run. Headless tests do not claim those rows.
