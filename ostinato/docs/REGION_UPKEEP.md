# Region upkeep (`#baseline`)

First playable slice of "repair the damage to this saved wall, but keep the doorway I added". Static masonry,
pre-stocked materials, a baseline the player approves and that survives restarts. Minecraft Java 26.3 Fabric client.

## Player flow

```
#baseline save wall 4,-60,6:13,-57,6      snapshot the loaded region (or select it with #sel first)
#baseline accept wall cells=7,-60,6;7,-59,6   "this doorway is intended" (box=x1,y1,z1:x2,y2,z2 also works)
#baseline exclude wall cells=...          unmanaged: never compared, restored or reported
#baseline check wall                      matched / needs review / would restore / unknown
#baseline repair wall box=11,-58,6:12,-57,6   restore only what this selection authorizes
#baseline policy wall box=...             standing zone: damage inside it is restored without a selection
#baseline stop | list
```

`#baseline` is owner-only; a baseline is what the player approved, so no roster member can change or apply one.

## What a baseline is

`RegionBaseline` (pure Java, `baritone.swarm.rts`) stored as text in `<swarmCenterDir>/baselines/<name>.baseline`:
world id (save folder, or server address) and dimension, the region, the managed cells with structural state, accepted
edits (a cell approved as air for a doorway, or as another block), explicit exclusions and standing zones. It is bounded
to 64 blocks an edge and 20 000 managed cells, and `check` / `repair` refuse a baseline saved in another world or
dimension.

Only structural state is recorded and compared: log axis, slab bottom/top, straight-stair facing and half (the same set
`BlockStateSpec` can place). Waterlogging, stair shape and other neighbour-derived or transient state is ignored.
Anything with a block entity (chests, barrels, furnaces, signs) is recorded as an exclusion at save time, and is never
overwritten, refilled or cleared later; fluids, plants and other non-masonry in the box are not managed.

## Diff rules

Using only client-visible state, every managed cell is exactly one of:

| verdict | meaning |
| --- | --- |
| match | in its approved state |
| unknown | its chunk is not loaded on the client; stays unknown until revisited, never "missing" |
| restore | differs, and an explicit selection or a standing zone authorizes it |
| review | differs and nothing authorizes it (a client cannot tell a player edit from an explosion), or it is an accepted opening that was filled, or a container is involved |

`repair` hands only the `restore` cells to `BlueprintBehavior.repairCells`, which reuses the Phase 2 settle-verified
`BlueprintRepairSession` (bounded retries, cancellation via `#baseline stop`) and adds:

* a guard (`AltoClefSettings.ostinatoGuard`) that keeps the builder and pathfinder from breaking or placing into any other
  cell of the saved region for as long as the repair runs, so approved masonry, the doorway and the unclassified opening
  are untouched;
* a no-progress limit (`DEFAULT_STALL_TICKS`, 60 s) that fails the remaining cells when the builder never finishes (no
  route, no materials). Previously a builder that searched forever was only stopped by the player.

## Chunk checks

26.3 `ClientLevel.hasChunk` is always true. `BlueprintStateHelper.observe` (Phase 2) used it, so unloaded cells read as
air and never as `UNLOADED`. It now asks `getChunkSource().hasChunk`, which also corrects `#blueprint verify` and
`#blueprint repair`.

## Live fixture

`RegionUpkeepLiveTest`, two client launches (a real restart between them):

```
-Dostinato.testHarness=true -Dostinato.upkeeptest=setup  -Dostinato.upkeeptest.world=NAME -Dostinato.upkeeptest.exit=true
-Dostinato.testHarness=true -Dostinato.upkeeptest=verify -Dostinato.upkeeptest.world=NAME -Dostinato.upkeeptest.exit=true
```

Run through `:fabric:runClient`. `setup` builds a 10x4 stone-brick wall with a chest, saves a baseline, registers a doorway,
removes an approved patch and opens a second, unregistered gap. `verify` reopens the world, teleports 600 blocks away
(everything unknown), returns (review list), repairs the patch by explicit selection, then a second gap via a standing
zone, and checks the integrated server's world independently. Results append to `upkeeptest-result.txt`.

## Not in this slice

Autonomous repair of ambiguous changes, automatic material replenishment, and cleanup of the scaffolding the builder
places outside the saved region while it works.
