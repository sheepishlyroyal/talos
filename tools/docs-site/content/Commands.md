# Command reference

Every command lives under `/talos` (`/talos` is a full alias — both prefixes work everywhere). Every
block/item id argument accepts **both** the bare and namespaced form (`stone` and `minecraft:stone`), and
tab-completion suggests both.

## Coordinate syntax

| Prefix | Meaning | Base | Example |
|---|---|---|---|
| *(none)* | absolute world coordinate | — | `100 64 -30` |
| `~` | relative to the player | position/eyes on that axis | `~ ~1 ~` (1 above) |
| `^` (raytrace / scripts) | vanilla **local** frame `^left ^up ^forward` | eyes + look direction | `^ ^ 5` (5 forward) |
| `^` (look / coords / mine `direction`) | relative **angle** offset | current yaw/pitch | `^ ^-30` (aim 30° up) |

> `^` means **local coordinates** in `raytrace`, but a **relative angle** in `/talos look`,
> `/talos coords direction` and `/talos mine direction`. Different subcommands, no collision — just be aware.

## Pathfinding & movement

| Command | Description |
|---|---|
| `/talos goto xyz <x> <y> <z>` | Path to an exact block (`~`-relative ok). |
| `/talos goto xyz <x> <z>` · `/talos xz <x> <z>` | Path to an X/Z column at your current Y. |
| `/talos goto near <x> <y> <z> <range>` | Path to within `range` blocks of a position. |
| `/talos goto block <id> [radius]` | Path to the **nearest** match; blacklists + retries the next-nearest (up to 5) if unreachable. |
| `/talos follow <target> [distance]` | Follow **any entity**; the goal live-tracks each tick it moves. `target` = player name, entity type, or selector. |
| `/talos find block <predicate> [radius]` | Report the nearest matching block in loaded chunks. |
| `/talos glow <x> <y> <z> [seconds]` | Wireframe box around a block to confirm a lookup. |
| `/talos stop` (`stop all`) | Stop pathing, follow, aim and any running task. |

`/talos goto` runs with mining **and** placing enabled by default — it tunnels, bridges, digs shafts,
nerdpoles and parkours through one simulated-physics planner, never as fixed "modes". `follow` ends via
`/talos stop`, when another goto takes over, or after the target stays gone ~15s. The blue/green/purple
route-checkpoint boxes redraw from the live follower every second, so they stay visible the whole run.

```
/talos goto xyz ~ ~10 ~
/talos goto near 100 64 200 3
/talos goto block diamond_ore 32
/talos follow @e[type=cow,distance=..20]
```

## Raytrace & local coordinates

| Command | Description |
|---|---|
| `/talos raytrace [get] <x> <y> <z>` | Resolve a coordinate triple to a world point (3dp) + the block id there. |
| `/talos raytrace simple\|advanced get <x> <y> <z>` | `simple` = floored **block cell** (ints); `advanced` = exact 3dp point (default). |
| `/talos raytrace where [maxDist]` | Cast from the eyes; report the first **block or entity** hit — point (3dp), id, distance. Default 64, max 256. |
| `/talos raytrace if block <id> [maxDist]` | Succeed (1) / fail (0) if the first hit is that block. |
| `/talos raytrace if entity <selector> [maxDist]` | Succeed / fail if the first hit is a matching entity. |

Friendly caret: once **any** axis is a caret, plain numbers on the other axes are local offsets too —
`^ ^ 5` **is** `^ ^ ^5`. Only mixing `^` with `~` is an error.

```
/talos raytrace ^ ^ 5          # point 5 ahead along the gaze
/talos raytrace get ~ ~-1 ~    # the block just under your eyes
/talos raytrace where
/talos raytrace if block minecraft:chest 6
```

## World actions

| Command | Description |
|---|---|
| `/talos mine <x> <y> <z>` | Break a block via the humanized, tool-aware `BreakBlockAction`. |
| `/talos mine direction <yaw> <pitch>` | Mine the block hit by a raycast (`^`-relative angles). |
| `/talos mine block <predicate> [index]` | Mine the `index`-th closest match (0-based, `-1` = furthest). |
| `/talos place <x> <y> <z>` | Place your held block, verified against server state. |
| `/talos kill nearest [radius]` | Attack the nearest hostile within `radius` (default 6, range 1–64). |

## Aiming (`/talos look` / `/talos track`)

```
/talos look 45 10                                # absolute or ^-relative yaw/pitch
/talos look block minecraft:diamond_ore 0        # 0-based index into nearest matches
/talos look coords ~ ~1 ~
/talos look direction ^ ^-20                     # raycast along a direction
/talos look @e[type=zombie] 0
/talos track                                     # follow the nearest player (@p)
/talos track @e[type=zombie]
/talos track block minecraft:diamond_ore
/talos track stop
```

With **Human mode on** (`/talos human on`), command and Python aim run through the humanized cube-aim
controller — see [Humanization](Humanization).

## Input automation

| Command | Description |
|---|---|
| `/talos walk <w\|a\|s\|d> tap` | One key press. |
| `/talos walk <w\|a\|s\|d> hold <seconds>` | Hold a movement key. |
| `/talos walk <w\|a\|s\|d> blocks <n> [center\|touch]` | Walk exactly `n` blocks with momentum-aware braking. |
| `/talos key <name> tap\|hold <seconds>` | Press any logical keybinding (movement, jump/sneak/sprint, attack/use/drop/swap/inventory, hotbar 1–9). |
| `/talos key list` | List key names. |
| `/talos macro record <name> [channels]` | Record real per-tick input. |
| `/talos macro stop` | Stop and save the recording. |
| `/talos macro play <name> [times] [channels]` | Replay a macro. |
| `/talos macro list` · `/talos macro delete <name>` | Manage saved macros. |

Macro channels: `move`, `jump`, `sneak`, `sprint`, `clicks`, `look`, `hotbar`, plus `keys`
(all keyboard), `input` (keys+clicks) and `all`. A `clicks+look`-only macro leaves movement free for
`/talos goto` or manual play alongside it.

## Inventory

| Command | Description |
|---|---|
| `/talos inv list` | List occupied slots in the current screen handler. |
| `/talos inv move <from> <to>` | Move a stack between slots. |
| `/talos inv hotbar <from> <1-9>` | Swap a slot into a hotbar position. |
| `/talos inv deposit all\|<item>` | Quick-move matching stacks into an open container. |
| `/talos inv withdraw all\|<item>` | Quick-move matching stacks out of an open container. |
| `/talos inv armor <piece> <from>` | Equip an armor piece from a slot. |

## Observables (`/talos get`)

Instant readouts through the exact same evaluation path rules use. See
[Event rules & getters](Event-Rules-and-Getters) for the full catalog.

```
/talos get health
/talos get server_tps
/talos get list                                # every observable + all 206 trigger names
/talos get block_count minecraft:diamond_ore 16 ~ ~-4 ~
/talos get entity @e[type=zombie] -1           # 0-based/negative index; -1 = furthest
```

## Scripting & session commands

| Command | Description |
|---|---|
| `/talos script run <name> [args…]` | Run `talos/scripts/<name>.py`. Trailing args → `talos.args`. |
| `/talos script stop` | Hard-stop the running script, even mid-loop. |
| `/talos script profile` | Toggle per-event dispatch profiling. |
| `/talos py <code>` | Run a Python one-liner; a trailing expression echoes its repr. |
| `/talos <name> [args]` | Script-registered commands (`@talos.command(...)`); built-ins win first. |
| `/talos cmd <name> [args]` | Explicit dispatch for a script command that shadows a built-in. |
| `/talos example [name]` | List bundled examples, or write `example_<name>.py`. |
| `/talos script editor` | Open the in-game Python editor screen. |
| `/talos human [on\|off]` | Toggle session-arc Human mode. |
| `/talos human intensity <0..3>` | More/less humanisation: 0 near-robotic, 1 profile default, 3 max. Persisted. |
| `/talos human set <knob> <value>` | Override one humanisation knob (tab-completes; clamped safe). Persisted. |
| `/talos human show` / `reset` | Inspect / clear humanisation tuning. |
| `/talos debug [on\|off\|status]` | Master switch for [detailed logging](Detailed-Logging) — engine trace + `talos.debug()` lines to chat and `~/.talos/logs/`. |
| `/talos ui` | Open the Talos UI screen. |
| `/talos bridge allow` · `/talos bridge status` | Allow / inspect the VS Code WebSocket bridge. |
