# RTS mode

Written 25 Nov 2026 (America/Phoenix). Status table at the end.

RTS mode lets you give bots standing jobs (roles) and steer them from the command center's war-room map the way
you would in a real-time strategy game. It sits on what is already there: the command-center window (map,
selection, control groups, area orders, tile details), the swarm link (local channel first, whispers only as a
fallback), Phase 2/3 leases, reservations and shared observations, and `#swarm build`.

Hard rules carry over unchanged. A role only ever starts existing Baritone processes (farm, mine, build, path) and
the chest step below. Every action is a key, a mouse-look step or a click on an open container screen. Nothing
here builds a packet or edits state between the input and vanilla's send. Headless bots run the same pure loop
and emit only what a vanilla `LocalPlayer` would.

## 1. Roles

A role is a recurring job loop with inputs and outputs. It is assigned by one registry command, so chat, the
window and an AI tool all reach it the same way:

```
#role farmer [at=x,y,z] [r=16] [chest=x,y,z]
#role miner ore=iron_ore,coal_ore [chest=x,y,z] [keep=32]
#role storage chest=x,y,z [chests=x,y,z;x,y,z...]
#role armorer [chest=x,y,z] [set=iron|copper|leather]
#role idle            (stop the loop, keep the bot where it is)
#role status
```

From the window or another bot it goes as `#swarm send <bot> role ...` (local channel, signed). The role is part
of the bot's feed, so the map and the wall show it.

| Role | Work step | Done when | Output | Deposit |
|---|---|---|---|---|
| farmer | `FarmProcess` around `at` within `r` (harvest mature crops, replant, pick up drops) | crop items >= `batch` or free slots <= 2 or no ripe crop left for 30 s | wheat, carrots, potatoes, beetroot, melon, pumpkin, extra seeds | everything except one stack of each seed |
| miner | `MineProcess` for `ore` (inside a region lease when the window assigned an area) | free slots <= 2 or `batch` ore mined | ores, raw metals, coal, cobble/deepslate/dirt | everything except tools, food, torches and `keep` blocks |
| storage | walk the listed chests, open each, quick-move items into the chest that already holds that item | every chest visited once | sorted chests | n/a |
| armorer | gather inputs from storage, craft at a table, equip or deposit | a full set made | armour pieces | equip, else deposit |

The loop is pure (`baritone.swarm.rts.RoleLoop`, no game types): given the role, the inventory summary and the
step's outcome it returns the next phase (`WORK`, `DEPOSIT`, `RESTOCK`, `WAIT`, `IDLE`). The game side
(`baritone.behavior.RoleBehavior`) turns a phase into one process call. A failed step backs off (5 s, 15 s, 45 s)
instead of spinning, and three failures in a row put the bot in `WAIT` with the reason on its tile.

Deposit is the one new action. Path to the chest (`GoalGetToBlock`), look at it with the normal look step, press
use, wait for the vanilla chest screen, then one `QUICK_MOVE` click per tick with movement released (the same
container-click exception the controls-only audit allows), then close the screen. When leases are on, the chest
is taken with a chest lease (`c:` key) first so two bots never stand in the same chest at once; with leases off
the step still works for one bot per chest.

Copper tools are preferred where the loop chooses (armorer `set=copper` default when iron is short; miners keep
whatever tools they hold).

## 2. Scheduler

There is no new coordinator. Jobs reuse what Phase 2/3 already has:

- A miner given an area gets a region lease (`r:<dim>:box`) through the existing `#swarm lease <bot> ... mine`
  path, one sub-box per bot (the same split as "Mine in area").
- A deposit or a storage visit takes a chest lease, so two bots queue for one chest instead of fighting over it.
- Shared observations name the chests and veins the map draws; a role may only use a chest it was given or one the
  observations confirm.
- Build jobs are `#swarm build` regions, so the lead splits work and members report `BSTAT` as today.

## 3. RTS view (war-room map, RTS panel)

The map layout grows an **RTS** toggle. With it on:

- **Selection**: click, shift-click, right-drag box select and Ctrl+1..9 control groups (already there).
- **Right-click order**: a right click on open ground with bots selected sends each one a `goto` to that block
  (one `#swarm send` per bot, the local bot runs it directly).
- **Role panel**: a role picker plus its fields (chest, ore, radius). *Assign* sends `role ...` to every selected
  bot; *Idle* clears it. The chest field can be filled by clicking a chest observation on the map.
- **Economy HUD**: one line summed over every bot's inventory feed (food, ores, crops, free slots) plus each
  role's count and how many bots are depositing or waiting.
- **Live bot positions**: the markers, facing and paths already drawn from the feeds, now labelled with the role
  letter (F, M, S, A, B).

Rendering stays the existing top-down raster from the feeds' terrain overview. Isometric is a later view mode on
the same data (the camera is already separate from the painter).

## 4. Live build

The blueprint is the same `ost1` text schematic the area bar already loads (`OstSchematic`, block names, no game
types), so the later 3D Design/Live editor can open and save the same file.

1. **Place**: the RTS panel's *Blueprint* tool arms painting. Left click/drag paints the chosen block at the
   chosen layer, right click erases. The cells are drawn as a ghost; cells the bots have placed turn solid.
2. **Write**: every edit rewrites `<swarmCenterDir>/blueprints/<name>.ost` (atomic temp + rename, debounced to
   one write per 500 ms). Same-PC bots read that folder; nothing new travels over the network.
3. **Build**: the first *Build live* press sends `blueprint live <name> <x> <y> <z> [group]` to the host. The
   bot loads the file, diffs it against its own world and starts the builder on what is missing. Without a group
   it builds alone; with a group it is a `#swarm build` with the blueprint path, split by the lead.
4. **Stream**: the bot rechecks the file once a second. A new version restarts the build from the new file: cells
   already right in the world are skipped by the builder's own diff, new cells are added, erased cells are no
   longer wanted. The bot's feed reports `placed/total` and up to 64 missing cells for the ghost.

## 5. Not in this slice

- The full 3D schematic editor (Design and Live modes). It will read and write the same `ost1` files and reuse
  `blueprint live` for its Live mode.
- Isometric rendering, a minimap of chest contents, and per-role priorities.
- Remote-PC bots: feeds and blueprints are same-PC files, as for the rest of the window.

## Status

| Piece | State | Evidence |
|---|---|---|
| (a) roles: farmer, miner, chest deposit | live | `gate/rts/rts2` (Paper 26.3 + Grim): farmer stored 28 wheat in 25 s and replanted; miner stored 6 coal in 16 s; 0 flags |
| (b) RTS panel on the map: role assign, idle, right-click move, economy line, role letters | live | `gate/rts/rts3` window-farmer: Assign from the window, chest held 25 wheat in 20 s; `gate/rts/rts4` window-move: right-click order, bot at -50,31 in 13 s; 0 flags. `RtsViewTest` + `build/screens/rts-dark.png` |
| (c) live blueprint: paint on the map → `blueprints/<name>.ost` → `#blueprint live` → builder; edits restart it; status back to the map | live | `gate/rts/rts4`: 9/9 cobblestone in 10 s, then a painted extra row → v12, 12/12 in 3 s more, status file `12 12 12 done`; 0 flags |
| blueprint split across several bots (one file, cell ranges per bot) + mid-build erase | live | `swarm-gate-logs/rtsl-r6.out` and `rtsl-r7.out`: 23/23 cells, erased cell air, 0 Grim flags |
| (d) storage organizer | live | r6/r7: 3 chests sorted by type via open-screen clicks (coal+wheat / cobblestone / oak_log+bone); r7 RESULT SORTED; 0 flags |
| (e) armorer (copper) | live | r7 (`rtsl-r7.out`): Azalea gathered 24 copper_ingot, crafted and equipped helmet/chestplate/leggings/boots in 8 s; 0 flags |
| multi-layer blueprint painting (h / Y-step) | unit | `RtsViewTest.layersPaintWallsAndYStepsTheLayer`; live proof next (`rtsl.py <tag> layers`) |

Live gate: `python scripts/rts-gate.py grim <tag> [steps]` (farmer, miner, window-*). Multi-bot leases, storage, armorer
and layers: `python swarm-gate-logs/rtsl/rtsl.py <tag> mine,build,storage,armor,layers Copper,Azalea` after
`rtsl-setup.ps1`. Clients are muted (every `soundCategory_*` 0.0); the window runs at 2600,40 without taking focus
and is closed at the end.
