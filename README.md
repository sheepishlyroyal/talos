    # Talos

Talos is a **client-side** Minecraft **1.21.11** Fabric automation mod. It gives you a
physics-simulated pathfinder that mines/bridges/pillars/parkours through obstacles, a ~206-family
event-rule engine that reacts to almost anything the client can observe, humanized aim and input
macros, and an embedded **GraalPy** Python runtime (plus a VS Code bridge) for scripting on top of
all of it. Everything runs as **client commands** — no server permission level, no server-side mod
required.

- **Pathfinding** — a from-scratch A\* planner over a deterministic mirror of vanilla player
  physics (`talos-mod/.../pathing/sim/`), not a waypoint graph. It walks, jumps, sprints,
  parkours (including momentum-chained hops — the 5-block jump off snow layers), mines, bridges,
  pillars, shafts and swims as needed. Deep searches run **full-speed on background threads** over
  an immutable world snapshot with wall-clock budgets that scale to your machine, while movement
  never stalls. Replans live off real player state.
- **Event rules** — `/talos on <trigger> ... run <command>`, ~206 unique trigger families (vitals,
  entities, blocks, items, world, network packets, text, sound/particles), each with comparisons,
  sustained/windowed temporal modes and selector filtering.
- **Raytrace & local coordinates** — `/talos raytrace` resolves vanilla caret (`^left ^up ^forward`)
  coordinates and casts look-rays that hit **blocks and entities** with sub-block precision.
- **Follow mode** — `/talos follow` trails **any entity** (players, mobs, items — full selector
  support) with a moving-goal navigator.
- **Humanized aim** — an off-grid "yellow cube" target model with per-session fast/slow rotation
  profiles, quadratic speed modulation and a live red preview path — not an instant snap.
- **Macros** — channel-selective recording/replay of real per-tick input (movement, jump, sneak,
  sprint, clicks, look, hotbar).
- **Scripting** — embedded GraalPy (`import talos`), script libraries (`talos.require`), CLI args
  (`talos.args`), an on-screen HUD, an in-game editor screen, and a VS Code extension that pushes and
  runs scripts over a local, token-gated WebSocket. The extension runs in **VS Code and every major
  fork** (Cursor, Windsurf, VSCodium, Trae, Antigravity, Theia, code-server).

---

## Requirements & install

| | |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.141.4+1.21.11 |
| Java | 21 (build + run) |
| Yarn mappings | 1.21.11+build.6 |
| GraalPy | 24.2.2 (bundled into the jar) |

Optional: the separately distributed `talos-pathing-baritone` adapter is picked up automatically if
installed and takes priority over the built-in pathfinder — but Talos' own sim-based pathfinder
(`TalosPathingEngine`) is **always available** with no dependency, so `/talos goto` works out of the
box.

Build the mod jar with a Java 21 `JAVA_HOME`:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS example
./gradlew :talos-mod:remapJar
```

The output jar lands in `talos-mod/build/libs/`. Drop it into your Fabric `mods/` folder alongside a
matching Fabric API build.

---

## Quick start

```
/talos goto xyz ~ ~ ~50        # walk 50 blocks north, tunnelling/bridging as needed
/talos raytrace where          # what am I looking at? (block or entity, to 3dp)
/talos raytrace ^ ^ 5          # world coords of the point 5 blocks ahead
/talos follow @e[type=cow]     # trail the nearest cow
/talos on health below 6 run chat low HP!    # arm a persistent rule
/talos script run farm wheat 64              # run a Python script with args
```

Every command lives under `/talos`; `/talos` is a full alias (both prefixes work everywhere).

---

## Command reference

### Pathfinding & movement

| Command | Description |
|---|---|
| `/talos goto xyz <x> <y> <z>` | Path to an exact block. Coordinates accept `~`-relative syntax. |
| `/talos goto xyz <x> <z>` / `/talos xz <x> <z>` | Path to an X/Z column at your current Y. |
| `/talos goto near <x> <y> <z> <range>` | Path to within `range` blocks of a position. |
| `/talos goto block <id> [radius]` | Path to the **nearest** matching block; if that one is unreachable it blacklists it and retries the next-nearest (up to 5 candidates). |
| `/talos follow <target> [distance]` | Follow **any entity** until following ends. The goal live-tracks the target every tick it moves — no waiting for it to stray. `target` is a player name, an entity type, or a selector. |
| `/talos find block <blockPredicate> [radius]` | Report the nearest matching block in loaded chunks. |
| `/talos glow <x> <y> <z> [seconds]` | Wireframe box around a block, to confirm a lookup. |

```
/talos goto xyz ~ ~10 ~
/talos goto near 100 64 200 3
/talos goto block diamond_ore 32        # ids work bare or namespaced everywhere
/talos follow Steve 4
/talos follow @e[type=cow,distance=..20]
```

Every block/item id argument accepts **both** the bare and the namespaced form (`stone` and
`minecraft:stone`), and tab-completion suggests both — the same rule the Python API applies.

`/talos goto` runs with mining **and** placing enabled by default — it tunnels through walls, bridges
gaps, digs vertical shafts, nerdpoles up and parkours across, all through the same simulated-physics
planner, never as fixed "modes" you choose. `follow` ends via `/talos stop`, when another goto takes
over, or after the target stays gone ~15s.

`/talos goto` may be edited with /talos example goto and saves in your scripts.
Then, `/talos goto` reads off the saved/running script, instead of the default /talos goto.
### Raytrace & local coordinates

`/talos raytrace` is the look-relative coordinate + raycast primitive.

| Command | Description |
|---|---|
| `/talos raytrace get <x> <y> <z>` (or bare `/talos raytrace <x> <y> <z>`) | Resolve a coordinate triple to a world point, reported to **3 decimals**, plus the block id there. |
| `/talos raytrace simple\|advanced get <x> <y> <z>` | Same resolve, choosing the report: `simple` = the floored **block cell** (integers — stepping the forward axis walks adjacent cells), `advanced` = the exact 3dp point (the default). |
| `/talos raytrace where [maxDist]` | Cast from the eyes along the look; report the first **block or entity** hit — exact point (3dp), id and distance. Default reach 64 (max 256). |
| `/talos raytrace if block <id> [maxDist]` | Succeed (1) / fail (0) if the first hit is that block. |
| `/talos raytrace if entity <selector> [maxDist]` | Succeed / fail if the first hit is a matching entity. |

Each axis of `get` accepts three modes, matching vanilla:

- **absolute** — `12`, `-3.5` — a literal world coordinate.
- **`~` relative** — `~`, `~5`, `~-3` — offset from your **eye** position on that axis.
- **`^` local (caret)** — `^`, `^5`, `^-3` — an axis of the look-relative `^left ^up ^forward`
  frame. Friendlier than vanilla: once **any** axis is a caret, plain numbers on the other axes
  are local offsets too — `^ ^ 5` **is** `^ ^ ^5`, 5 blocks forward; `^ ^ -3` = 3 behind;
  `^2 ^ ^` = 2 to the left. Only mixing `^` with `~` is an error (two different origins).

```
/talos raytrace ^ ^ 5          # point 5 ahead along the gaze
/talos raytrace get ~ ~-1 ~    # the block just under your eyes
/talos raytrace where          # first block/entity your crosshair meets
/talos raytrace if block minecraft:chest 6
/talos raytrace if entity @e[type=zombie]
```

> **Note on `^`:** in `raytrace` it means vanilla **local coordinates**. In `/talos look`,
> `/talos coords direction` and `/talos mine direction`, `^` instead means a **relative angle**
> (yaw/pitch offset). Different subcommands, no collision — but be aware the two exist.

### World actions

| Command | Description |
|---|---|
| `/talos mine <x> <y> <z>` | Break a block via the humanized, tool-aware `BreakBlockAction`. |
| `/talos mine direction <yaw> <pitch>` | Mine the block hit by a raycast (`^`-relative angles). |
| `/talos mine block <blockPredicate> [index]` | Mine the `index`-th closest match (0-based, `-1` = furthest). |
| `/talos place <x> <y> <z>` | Place your held block, verified against server state. |
| `/talos kill nearest [radius]` | Attack the nearest hostile within `radius` (default 6, range 1–64). |

```
/talos mine direction ^ ^-30
/talos mine block minecraft:diamond_ore -1
/talos place ~ ~1 ~
```

### Aiming (`/talos look` / `/talos track`)

```
/talos look 45 10                                # absolute or ^-relative yaw/pitch
/talos look block minecraft:diamond_ore 0        # 0-based index into nearest matches
/talos look coords ~ ~1 ~
/talos look direction ^ ^-20                     # raycast along a direction
/talos look @e[type=zombie] 0
/talos look entity type minecraft:zombie tag boss 0
/talos track                                     # follow the nearest player (@p)
/talos track @e[type=zombie]
/talos track block minecraft:diamond_ore
/talos track stop
```

All aim runs through the humanized cube-aim controller by default (`AimController`, no instant
snap): a 1×1m yellow guide cube is rendered **off-grid**, centered exactly on the intended point. The
actual aim spot — the red X — lands on a visible face chosen with probability proportional to that
face's visible flat area, center-biased. Rotation draws a random fast sensitivity far out and a
random slow sensitivity for the final approach, blending smoothly (never instantly) once the look ray
passes within 0.4m of the cube, with per-tick jitter and a red dotted preview line tracing the exact
curve the crosshair is about to draw. A per-session quadratic speed modulation (peaking mid-flight,
never linear) adds a small random speed swell/sag and a slight bow to the path. `/talos track` keeps
this same aim session alive against a moving target.

Navigation gaze speaks the same language: while `/talos goto` walks, a **full-block yellow cube** is
placed on a random, center-biased point of the look-ahead target's hitbox, the red mark lands on a
visible face (weighted by visible area, random spot on the face), and the eased view — yaw *and*
pitch — converges on that mark. The walking bearing stays honest: the mark may pull the gaze only a
few degrees off the route line, and the physics rollouts still choose the actual movement inputs.
The blue/green/purple route checkpoint boxes are re-drawn from the live follower every second, so
they stay visible for the whole run — every run — and clear the moment it ends.

### Input automation

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
| `/talos macro list` / `/talos macro delete <name>` | Manage saved macros. |

Macro channels: `move`, `jump`, `sneak`, `sprint`, `clicks`, `look`, `hotbar`, plus shorthands
`keys` (all keyboard), `input` (keys+clicks) and `all`. A `clicks+look`-only macro leaves movement
free for `/talos goto` or manual play to run alongside it.

```
/talos walk w blocks 5 touch
/talos key jump hold 1.5
/talos macro record mine_loop clicks+look
/talos macro stop
/talos macro play mine_loop 3
```

### Inventory

| Command | Description |
|---|---|
| `/talos inv list` | List occupied slots in the current screen handler (player inventory, or an open chest). |
| `/talos inv move <from> <to>` | Move a stack between slots. |
| `/talos inv hotbar <from> <slot 1-9>` | Swap a slot into a hotbar position. |
| `/talos inv deposit all\|<item>` | Quick-move matching stacks into an open container. |
| `/talos inv withdraw all\|<item>` | Quick-move matching stacks out of an open container. |
| `/talos inv armor helmet\|chestplate\|leggings\|boots <from>` | Equip an armor piece from a slot. |

### Observables (`/talos get`)

Instant readouts through the exact same evaluation path rules use.

```
/talos get health                              # numeric metric
/talos get server_tps                          # same live value used by server_tps rules
/talos get list                                # every observable + all 206 trigger names
/talos get position                            # string readout
/talos get slot hotbar.1                        # named slot contents
/talos get entity @e[type=zombie] -1            # 0-based/negative index; -1 = furthest match
/talos get blockpos minecraft:diamond_ore 0     # nearest matching block within 32
/talos get sign / lectern / skull / banner / campfire / item_frame   # crosshair block-entity detail
/talos get sounds                              # distinct sound ids in the last 5s
/talos get particles                           # distinct particle ids in the last 3s
/talos get crosshair_particles                 # particles near the look ray in the last 2s
/talos get block ~ ~-1 ~                        # block id at ~-relative coords
/talos get block direction ^ ^                  # block id along a ^-relative raycast
/talos get entity_location 123                  # mob/entity runtime id -> xyz, exactly 3dp
/talos get entity_count @e[type=zombie] 32      # exact loaded count within 32 blocks
/talos get block_count minecraft:diamond_ore 16 # exact cube count, same calculator as rules
/talos get item_count minecraft:diamond         # exact inventory item count
/talos get villager_profession_changed          # latest old -> new change + exact villager id
```

`get entity`/`get blockpos` use Python-style 0-based indexing (`0` = nearest, `-1` = furthest).
`slot` names: `hotbar.1`–`hotbar.9`, `inv.1`–`inv.27`, `head`/`chest`/`legs`/`feet`, `offhand`,
`held`, `cursor`, `container.N`, `saddle`, `horsearmor`.

The getter catalog is a strict superset of the trigger catalog: **every one of the 206 names accepted
by `/talos on` is accepted by `/talos get` and `talos.get()`**. Both surfaces call the same Java
catalog, so they cannot have different names or calculations. Underscores and spaces are equivalent
in Python (`talos.get("server_tps")` = `talos.get("server tps")`); commands use underscores.

| Trigger kind | Getter arguments | Returned value |
|---|---|---|
| `COMPARE` | none | Current numeric metric through the rule engine's exact calculator. This includes `server_tps`. |
| threshold `NUMBER` | none | Current underlying value (`health_below`/`health_above` → health, `hunger_below` → hunger, `air_below` → air, `xp_level_above` → XP level); `tick_every` returns the current Talos tick. |
| `ENTITY_COUNT` / `ENTITY_PRESENCE` | `<selector> [radius=-1]` | Exact matching loaded-entity count. `entity_near` and `entity_gone` deliberately return the count, not a lossy boolean. All selector identities and filters work. |
| `BLOCK_COUNT` / `BLOCK_PRESENCE` | `<block> [radius=16]` | Exact matching block count in the same cube scan used by rules. |
| `ITEM_COUNT` | `<item-or-enchantment>` | Exact inventory/hotbar count, or held enchantment level for `held_enchant`. |
| `REGION` | `<x1> <y1> <z1> <x2> <y2> <z2>` | Whether the player is currently inside (`entered_region`) or outside (`left_region`). |
| state/string/event (`NONE` / `TEXT`) | none | A live hand-written state where one exists (for example `sneaking`); otherwise the latest occurrence, its payload, and age. Entity-subject events also include runtime `entity_id`, UUID, type and `pos=x y z` at 3dp. Before an occurrence the result is `never observed`. |

Examples of event retrieval: `villager_profession_changed` returns the old and new profession and
the precise villager identity/location; `villager_level_changed` returns old → new level and the
same identity fields; `item_picked_up` identifies the collector; projectile/entity lifecycle events
identify their subject. Packet, particle, scoreboard and per-entity observations are retained even
when no rule is armed, specifically so a later getter is truthful. Event getters report the latest
occurrence (not an invented current value); client-inaccessible facts such as closed villager
inventories remain unavailable.

`entity_location` (alias `mob_location` in Python) takes the client runtime/network entity ID shown
by entity-trigger payloads and returns `type#id[/name] @ x.xxx y.yyy z.zzz`. It errors if that entity
is not currently loaded; runtime IDs are session-scoped and can be reused after entities unload.

### Event rules

`/talos on <trigger> ... run <command>` arms a persistent rule (saved to `~/.talos/rules.json`).
`run` may be prefixed with `chat ` to send a chat message instead of dispatching a command; the
command dispatches through `/talos` first, then falls back to the server. Placeholders like
`{value}`, `{health}`, `{x}`/`{y}`/`{z}` are substituted from the firing event.

Trigger grammar depends on the trigger's *kind*:

| Kind | Grammar |
|---|---|
| `NONE` | `on <trigger> run <command>` |
| `NUMBER` | `on <trigger> <value> run <command>` |
| `TEXT` | `on <trigger> [matching "<text>"] run`, plus `count above <n> within <seconds> run` |
| `COMPARE` | `on <trigger> above\|below\|equals <n> [for <seconds>] run`, plus `changes above\|below\|equals <delta> within <seconds> run` |
| `ENTITY_COUNT` | `on <trigger> <selector> radius <r\|-1> above\|below\|equals <n> run` |
| `ENTITY_PRESENCE` | `on <trigger> <selector> radius <r\|-1> run` |
| `BLOCK_COUNT` | `on <trigger> <block> radius <r> above\|below\|equals <n> run` |
| `BLOCK_PRESENCE` | `on <trigger> <block> radius <r> run` |
| `ITEM_COUNT` | `on <trigger> <item> above\|below\|equals <n> run` |
| `REGION` | `on <trigger> <x1> <y1> <z1> <x2> <y2> <z2> run` |

```
/talos on health below 6 for 2 run chat low health, retreating
/talos on entity_count @e[type=zombie] radius 16 above 3 run chat too many zombies
/talos on chat matching "diamond" count above 3 within 10 run chat spam detected
/talos on health changes below -4 within 2 run chat taking burst damage
/talos on block_near minecraft:lava radius 5 run chat lava nearby
/talos on item_picked_up @e[type=player] matching diamond run chat someone grabbed a diamond
/talos on entered_region 100 60 100 120 80 120 run chat entered the build zone
/talos on tick_every 100 run /talos get health
```

Other rule commands: `/talos rules list`, `/talos rules remove <id>`, `/talos rules clear`,
`/talos every <seconds> run <command>` (persists), `/talos after <seconds> run <command>` (this
session only), `/talos on list` (dumps every trigger's grammar).

### Scripting commands

| Command | Description |
|---|---|
| `/talos script run <name> [args…]` | Run `talos/scripts/<name>.py`. Trailing args are whitespace-split into `talos.args`. |
| `/talos script stop` | Hard-stop the running script, even mid-loop. |
| `/talos script profile` | Toggle per-event dispatch profiling (prints a report when toggled off). |
| `/talos py <code>` | Run a Python one-liner; a trailing expression echoes its repr. Shares the running session's globals if one is live. |
| `/talos <name> [args]` | Script-registered commands (`@talos.command(...)`) work directly as `/talos <name>` — built-ins always win first. |
| `/talos cmd <name> [args]` | Explicit dispatch for a script command whose name shadows a built-in. |
| `/talos example [name]` | Bare form lists every bundled example; with a name, writes a commented reference script to `talos/scripts/example_<name>.py`. |
| `/talos script editor` | Open the in-game Python editor screen. |
| `/talos bridge allow` / `/talos bridge status` | Allow / inspect the VS Code WebSocket bridge for this session. |
| `/talos human [on\|off]` | Toggle session-arc "Human mode" — wall-clock fatigue drift + idle micro-breaks (see Humanization). |
| `/talos ui` | Open the Talos UI screen. |
| `/talos stop` (alias `/talos stop all`) | Stop pathing, follow, aim and any running task. |

```
/talos script run farm wheat 64
/talos py talos.player_pos()
/talos example                 # list examples
/talos example goto            # write example_goto.py
```

---

## Coordinate syntax (used across commands and scripts)

| Prefix | Meaning | Base | Example |
|---|---|---|---|
| *(none)* | absolute world coordinate | — | `100 64 -30` |
| `~` | relative to the player | position/eyes on that axis | `~ ~1 ~` (1 above) |
| `^` (raytrace/scripts) | vanilla **local** frame `^left ^up ^forward` | eyes + look direction | `^ ^ 5` (5 forward) |
| `^` (look/coords/mine `direction`) | relative **angle** offset | current yaw/pitch | `^ ^-30` (aim 30° up) |

In Python, coordinate-taking calls accept the same tokens as strings — `talos.goto("^ ^ 5")`,
`talos.block_at("~", "~-1", "~")` — plus the direct helpers `talos.local()`, `talos.ahead()` and
`talos.move_ahead()`.

---

## `talos.get()` and trigger return-value catalog

There is no separate `talos.trigger()` Python function: “trigger” below means a name accepted by
`/talos on <trigger> …`. All 206 trigger names are also accepted by `talos.get(name, *args)` and
`/talos get <name> …`. Python accepts either canonical underscores or spaces, but underscores are
recommended: `talos.get("server_tps")`.

Return conventions:

- Numeric and boolean getters become Python `int`/`float`/`bool`; descriptive values are `str`.
- Numeric triggers place the shown value in `{value}`. In `changes … within …` mode, `{value}` is
  the net change over the window instead of the absolute metric.
- Event getters return the latest event payload followed by `[N.NNs ago]`, or `never observed`.
  Entity-subject event getters additionally append runtime `entity_id`, UUID, type, and the event
  position at three decimal places. The trigger's `{value}` is the raw payload shown below.
- `entity label` means `minecraft:type#runtime_id[/custom-or-player-name]`. Runtime IDs last only
  for the current connection and may be reused after an entity unloads.
- A payload marked `empty` intentionally sets `{value}` to `""`; the trigger itself is the fact.

### Live numeric metrics (53)

| Name | `talos.get()` and trigger `{value}` |
|---|---|
| `health` / `max_health` / `absorption` | Current health / maximum health / absorption hearts. |
| `hunger` / `saturation` / `air` | Food points / saturation / remaining air ticks. |
| `xp_level` / `xp_progress` | Integer XP level / progress toward the next level (`0.0`–`1.0`). |
| `armor_points` | Current armor defense points. |
| `armor_durability` | Lowest remaining durability percentage among worn damageable armor; `100` if none is damageable. |
| `held_durability` / `held_count` | Main-hand remaining durability percentage (`100` if not damageable) / stack count. |
| `fps` / `ping` | Client FPS / local tab-list latency in milliseconds (`0` if unavailable). |
| `chunks_loaded` | Number of chunks currently held by the client chunk source. |
| `light_level` | Maximum local raw brightness at the player's block. |
| `x_position` / `y_position` / `z_position` | Exact player coordinate on that axis. |
| `speed` / `velocity_y` | Horizontal blocks per second / vertical blocks per second. |
| `fall_distance` | Current accumulated fall distance in blocks. |
| `time_ticks` / `world_age` | Overworld clock modulo 24,000 / current world game time. |
| `moon_phase` / `day_count` | Moon phase `0`–`7` / elapsed Minecraft day number. |
| `entity_total` / `players_online` | Render-loaded entity count / tab-list player count. |
| `idle_seconds` | Seconds since the player's block position last changed. |
| `empty_slots` / `occupied_slots` | Empty / occupied slots in the player inventory container. |
| `container_items` | Number of non-empty slots belonging to the currently open external container. |
| `memory_used_percent` | JVM used heap as a percentage of maximum heap. |
| `nearest_player_distance` | Distance to the nearest other loaded player, or `999` if none. |
| `nearest_hostile_distance` | Distance to the nearest loaded `Monster`, or `999` if none. |
| `nearest_animal_distance` | Distance to the nearest loaded `Animal`, or `999` if none. |
| `nearest_item_distance` | Distance to the nearest dropped item entity, or `999` if none. |
| `dropped_items_near` / `xp_orbs_near` / `arrows_near` | Count of that entity category within 16 blocks. |
| `crosshair_distance` | Eye-to-hit distance for the current block/entity hit, or `999` on a miss. |
| `spawn_distance` | Distance from the player's block to the client world's respawn position. |
| `fire_ticks` / `frozen_ticks` / `hurt_time` | Remaining fire ticks / accumulated frozen ticks / current hurt animation ticks. |
| `stuck_arrows` | Arrows visibly stuck in the local player. |
| `vehicle_speed` | Mounted vehicle horizontal blocks per second, or `0` when unmounted. |
| `effect_count` | Number of active status effects. |
| `world_border_distance` | Shortest distance from the player to the world border. |
| `server_tps` | Client estimate of server TPS from world-time advance over a rolling five-client-second window, capped at `20`. |
| `yaw` / `pitch` | Wrapped yaw in degrees / pitch in degrees. |
| `bossbar_percent` | Most recently updated boss-bar progress as `0`–`100`; `0` after removal. |

### Parameterized live values (9)

| Name | Getter call | Getter return | Trigger `{value}` |
|---|---|---|---|
| `entity_count` | `get("entity_count", selector, radius=-1)` | Exact matching loaded-entity count. | The count which satisfied the comparison. |
| `entity_near` | `get("entity_near", selector, radius=-1)` | Exact count, deliberately not a lossy boolean. | Count when it changes from zero to nonzero. |
| `entity_gone` | `get("entity_gone", selector, radius=-1)` | Exact count. | `0` when the count changes to zero. |
| `block_count` | `get("block_count", block, radius=16)` | Exact count in the same centered cube used by rules; radius is capped at 16. | The count which satisfied the comparison. |
| `block_near` | `get("block_near", block, radius=16)` | Exact count in that cube. | Count when it changes from zero to nonzero. |
| `item_count` | `get("item_count", item)` | Total matching item count in the player inventory. | The count which satisfied the comparison. |
| `hotbar_item_count` | `get("hotbar_item_count", item)` | Total matching item count in hotbar slots 1–9. | The count which satisfied the comparison. |
| `held_enchant` | `get("held_enchant", enchantment)` | Main-hand enchantment level, or `0`. | The level which satisfied the comparison. |
| `entered_region` / `left_region` | `get(name, x1, y1, z1, x2, y2, z2)` | Whether currently inside / outside the inclusive cuboid. | `0` on the corresponding boundary crossing. |

Selectors support `@e`, `@a`, `@p`, `@s`, `@r`, and `@n`, including bracket filters. Radius `-1`
means the whole loaded client world.

### Threshold and clock triggers (6)

| Name | `talos.get()` return | Trigger `{value}` |
|---|---|---|
| `health_below` / `health_above` | Current health. | Health at the threshold crossing. |
| `hunger_below` | Current food points. | Food points at the threshold crossing. |
| `air_below` | Current remaining air ticks. | Air ticks at the threshold crossing. |
| `xp_level_above` | Current XP level. | XP level at the threshold crossing. |
| `tick_every` | Current Talos client tick counter. | Tick counter on each requested interval. |

### Player, inventory, and world transition triggers

Unless a live getter override is explicitly stated, `talos.get()` returns the latest raw payload
shown here plus its age.

| Trigger name | Fires when | Raw `{value}` / getter payload |
|---|---|---|
| `damage_taken` / `healed` | Local health decreases / increases. | Positive amount lost / gained, one decimal place. |
| `death` / `respawn` | Local player dies / becomes alive. | Empty. |
| `on_fire`, `falling`, `sneaking`, `sprinting`, `swimming`, `gliding`, `underwater`, `sleeping` | Local player enters that state. | Empty. Same-name getters return the current boolean where available (`falling` remains latest-event). |
| `woke_up` | Sleeping changes to awake. | Empty. |
| `mounted` / `dismounted` | Local vehicle changes. | Vehicle registry ID entered / left. |
| `moving` / `stopped` | Horizontal motion starts / stops. | Empty. `moving` getter returns the current boolean. |
| `climbing`, `blocking`, `using_item`, `collided`, `hurt`, `freezing` | Local player enters that state. | Empty. Same-name getters return current booleans for `climbing`, `blocking`, `using_item`, and `hurt`; the others retain their latest occurrence. |
| `jumped` | Leaves ground with upward velocity above `0.2`. | Empty. |
| `landed` | Returns to the ground. | Fall distance immediately before landing, one decimal place. |
| `projectile_incoming` | A moving arrow within 12 blocks is travelling toward the player's eyes. | Empty. |
| `window_focused` / `window_unfocused` | Game window gains / loses focus. | Empty. `window_focused` getter returns current focus. |
| `screen_opened` / `screen_closed` | Screen class changes. | Opened / closed screen class simple name. |
| `offhand_changed` | Local offhand stack type changes. | New item registry ID. |
| `looking_at_entity` | Crosshair entity type changes to a non-empty hit. | Entity type registry ID. |
| `mention` | Visible text contains the local player's name, case-insensitive. | Full message text. |
| `hotbar_empty` / `armor_missing` | Hotbar becomes empty / any armor slot becomes empty. | Empty. |
| `container_full` / `container_empty` | All / none of the external container slots are occupied. | Empty. |
| `held_changed` / `tool_broken` | Main-hand type changes / a damageable held item disappears as air. | New held item ID / broken item ID. |
| `inventory_full` | Player inventory has no free slot. | Empty; getter returns current boolean. |
| `slot_changed` | Selected hotbar slot changes. | New one-based slot number (`1`–`9`). |
| `container_opened` / `container_closed` | An external container opens / closes. | Empty. |
| `effect_added` / `effect_removed` | Local active-effect ID appears / disappears. | Effect registry ID. |
| `item_gained` / `item_lost` | Player inventory count changes. | `item_id xamount` delta. |
| `looking_at_block` | Crosshair block ID changes to a non-miss. | Block registry ID. |
| `standing_on` / `block_at_feet` / `block_above_head` | The corresponding sampled block changes. | New block registry ID. `standing_on` and `block_at_feet` getters return the current ID. |
| `dimension_changed` | Dimension registry key changes. | New dimension ID. |
| `world_loaded` / `world_unloaded` | Client world appears / disappears. | Empty. A getter requires an active world, so `world_unloaded` cannot be queried after disconnection. |
| `time_day` / `time_night` | Overworld clock crosses into day / night. | Empty. |
| `weather_rain` / `weather_clear` | Rain begins / ends. | Empty. |
| `player_joined` / `player_left` | Tab-list name appears / disappears. | Player name. |
| `biome_changed` | Player block's biome changes. | Biome registry ID. |
| `chunk_changed` | Player crosses a chunk boundary. | `chunkX chunkZ`. |
| `chat` / `actionbar` | Chat/game message / overlay message is received. | Full rendered plain text. |
| `title` / `subtitle` | HUD title / subtitle is set. | Full rendered plain text. |
| `sound` | Client sound engine plays a sound. | Sound registry ID. |
| `attack_block` / `use_block` | Local client attacks / uses a block. | Block position as `x, y, z`. |
| `attack_entity` / `use_entity` | Local client attacks / uses an entity. | Entity label; getter also carries entity metadata. |
| `use_item` | Local client uses an item. | Item registry ID. |
| `held_enchanted` / `held_has_name` | Held item becomes enchanted / gains a custom name. | Empty / custom name. |
| `held_name_changed` | Non-empty held custom name changes. | New custom name. |
| `sign_seen` | Text of the sign under the crosshair changes to non-empty. | Front text joined with ` / `. |

### Entity, item, and projectile lifecycle triggers

| Trigger name | Fires when | Raw `{value}` |
|---|---|---|
| `entity_spawned` / `entity_removed` / `entity_unloaded` | Entity first appears / disappears in a still-loaded chunk / disappears because its chunk unloaded. | Entity label. |
| `entity_held_changed` / `entity_offhand_changed` | Tracked living entity's hand / offhand item changes. | `entity label: item_id`. |
| `entity_armor_changed` | Tracked living entity's armor summary changes. | `entity label: comma-separated armor IDs`. |
| `entity_hurt` / `entity_died` / `entity_started_burning` | Tracked entity enters hurt state / reaches zero health or disappears dead / begins burning. | Entity label. |
| `entity_damaged` / `entity_healed` | Tracked living health changes. | `entity label: -amount` / `entity label: +amount`, one decimal place. |
| `entity_mounted` / `entity_dismounted` | Tracked entity's vehicle changes. | `entity label -> vehicle_id` / `entity label <- vehicle_id`. |
| `entity_sneaking`, `entity_sprinting`, `entity_blocking`, `entity_gliding`, `entity_swimming`, `entity_sleeping` | Tracked entity enters that state. | Entity label. |
| `entity_using_item` | Tracked entity begins using an item. | `entity label: held_item_id`. |
| `entity_baby_grown` | Tracked baby entity becomes adult. | Entity label. |
| `villager_profession_changed` | Synced villager profession changes. | `villager label: old_profession -> new_profession`. |
| `villager_level_changed` | Synced villager level changes. | `villager label: old_level -> new_level`. |
| `player_held_changed` / `player_offhand_changed` / `player_armor_changed` | Same tracked equipment changes, restricted to players. | Same payload as the corresponding `entity_*` trigger. |
| `player_gamemode_changed` | Tab-list game mode changes. | `player_name: lowercase_gamemode`. |
| `item_spawned` / `item_despawned` / `item_unloaded` | Dropped stack appears / vanishes in a loaded chunk / unloads with its chunk. | `item_id xcount`. |
| `item_picked_up` | Vanilla pickup packet identifies a collector. | `item_id xamount by entity label`; getter metadata identifies the collector. |
| `projectile_launched` | Any tracked projectile first appears. | `projectile label [by owner label]`; selector attribution is the owner when known. |
| `pearl_thrown`, `snowball_thrown`, `egg_thrown` | Named projectile first appears. | Same launch payload. |
| `projectile_hit` | Projectile vanishes in a still-loaded chunk. | `projectile label @ x y z [by owner label]`, block-rounded coordinates. |
| `pearl_landed` / `potion_splashed` / `snowball_hit` / `egg_hit` | Corresponding projectile vanishes in a loaded chunk. | `x y z [by owner label]`, block-rounded coordinates. |
| `projectile_stopped` | Tracked projectile speed falls from above `0.2` to below `0.05`. | `projectile label @ x y z`, block-rounded coordinates. |
| `potion_drank` | Tracked entity completes use while its previous hand item contains `potion`. | `entity label: potion_item_id`. |
| `totem_popped` | Entity status byte `35` is received. | Entity label. |
| `entity_status` | Any entity status byte is received. | `entity label: signed_status_byte`. |
| `teleported` | Same-dimension position jump exceeds 12 blocks in one client tick. | Rounded jump distance. |
| `particle_seen` | Particle packet is observed. | `particle_id @ x y z`, coordinates rounded to whole blocks. |
| `item_frame_changed` | Non-empty tracked item-frame content changes. | `item_frame label: item_id`. |

### Container, boss-bar, scoreboard, and packet triggers

| Trigger name | Fires when | Raw `{value}` |
|---|---|---|
| `container_title` | External container opens. | Current screen title. |
| `container_item_gained` / `container_item_lost` | Aggregate open-container count increases / decreases. | `item_id xamount` delta. |
| `packet_received` | Any S2C packet reaches the client. | Packet type ID. |
| `explosion` | Explosion packet is decoded. | Rounded center `x y z`. |
| `bossbar_shown` | Boss bar is added. | Boss-bar display name. |
| `bossbar_updated` | Boss-bar progress or name changes. | Rounded percentage such as `75%`, or the new name. |
| `bossbar_removed` | Boss bar is removed. | Empty. |
| `sidebar_appeared` / `sidebar_title_changed` | Sidebar appears / title changes. | Sidebar title. |
| `sidebar_removed` | Sidebar disappears. | Empty. |
| `sidebar_score_changed` | Existing sidebar owner score changes. | `owner: score`. |
| `sidebar_line_added` / `sidebar_line_removed` | Sidebar owner appears / disappears. | Owner string. |

The four generic catch-alls are therefore fully inspectable: `packet_received`, `entity_status`,
`particle_seen`, and `sound`.

### Additional current-value getters and live overrides

These are available to both `/talos get` and `talos.get()`. Most are convenience names with no
matching `/talos on` trigger; where a name does match a trigger (for example `sneaking` or
`standing_on`), the getter deliberately returns the current value rather than the latest event.

| Getter | Return |
|---|---|
| `position` / `block_position` | Player exact `x y z` to two decimals / integer block position. |
| `dimension` / `biome` | Current dimension / biome registry ID. |
| `held_item` / `offhand_item` | Main-hand `item_id xcount` / offhand item ID. |
| `armor` | Head, chest, legs, and feet item paths, comma-separated. |
| `hotbar` / `selected_slot` | All nine one-based hotbar entries / selected one-based slot. |
| `vehicle` | Mounted vehicle registry ID, or `none`. |
| `standing_on` / `block_at_feet` | Current block registry ID at that sample point. |
| `looking_at` | Current vanilla hit-result string, or `none`. |
| `screen` | Current screen class simple name, or `none`. |
| `difficulty` / `weather` / `time` | Serialized difficulty / `thunder`, `rain`, or `clear` / `ticks (day|night)`. |
| `effects` | Active effect IDs, levels, and remaining seconds, or `none`. |
| `players` | Comma-separated tab-list player names, or `none`. |
| `spawn_point` | Client world's respawn block position. |
| `sounds` / `particles` / `crosshair_particles` | Distinct sound IDs from 5s / particle IDs from 3s / particles near the look ray from 2s. |
| `sign`, `lectern`, `skull`, `banner`, `campfire`, `item_frame` | Detail for the corresponding block/entity currently under the crosshair. |
| `sneaking`, `sprinting`, `swimming`, `gliding`, `underwater`, `on_fire`, `on_ground`, `climbing`, `blocking`, `using_item`, `sleeping`, `frozen`, `hurt`, `moving`, `window_focused`, `raining`, `day`, `inventory_full`, `container_open` | Current boolean state. |
| `entity_location` (alias `mob_location`) | `get(name, runtime_id)` → `entity label @ x.xxx y.yyy z.zzz`; errors if the entity is not currently loaded. |

---

## Python scripting API (`import talos`)

`/talos script run <name>` runs `.minecraft/talos/scripts/<name>.py` through an embedded GraalPy
runtime with a curated `talos` module already in scope. Python runs on a dedicated worker thread; the
game tick thread never enters Python. Blocking calls block only the script, so render FPS is
unaffected. `pip`, native packages, host classes, filesystem and environment access are
unavailable — the API is a hardened capability surface, not full CPython.

### Script metadata & libraries

| Symbol | Description |
|---|---|
| `talos.args` | `list[str]` of the args passed to `/talos script run <name> args…`. Always fresh per run. |
| `talos.require("mylib")` | Import another script in `talos/scripts/` as a module (CPython import semantics: caching, cycle handling; each library gets its own first-run trust summary). |
| `talos.log(msg)` | Log to the mod logger (not chat). |
| `talos.state` | A persistent dict (`state["key"] = …`) saved per-script across runs. |

### Movement & pathing

`goto(x, y=None, z=None)` · `goto_near(x, y, z, range)` · `goto_xz(x, z)` ·
`goto_block(block_id, radius=64)` · `follow(target, distance=3.0)` · `move_ahead(distance)` (walk
forward on your horizontal heading) · `set_node_count(n)`. All accept coordinate numbers, `~`/`^`
token strings, a single `"~ ~1 ~"` string, or a `Pos`/`Entity` snapshot.

### Raytrace & local coordinates

`local(left, up, forward)` → `Pos` (caret `^left ^up ^forward`, from the eyes; `local(0,0,5)` is 5
ahead) · `ahead(distance)` → `Pos` (`local(0,0,distance)`) · `raytrace(max_distance=64.0)` → `Hit |
None` (first block/entity along the look, sub-block precise, entity-aware) ·
`raytrace_if(block=None, entity=None, max_distance=64.0)` → `bool`.

### Finding

`find_block(name, radius=64)` → `Pos|None` · `find_entity(entity_type, radius=64.0)` → `Entity|None`
· `find_item(item, radius=64.0)` → `Entity|None` · `players(radius=128.0)` → `list[Player]` ·
`nearest_player(radius=128.0)` → `Player|None` · `entities(type=None, radius=64.0)` → `list[Entity]`.

### World actions

`place_block(x=None, y=None, z=None, block_id=None)` (no coords = place at crosshair) ·
`place_look()` · `break_block(x, y=None, z=None)` · `mine(...)` (alias of `break_block`) ·
`mine_looking_at()` · `left_click()` · `right_click()` · `kill_nearest(radius=6.0)`.

### Aim & look

`look(yaw, pitch)` · `look_at(x, y=None, z=None)` · `look_angle()` → `(yaw, pitch)` ·
`looking_at()` → `Pos|None` (crosshair block) · `angle_to(x, y=None, z=None)` → `(yaw, pitch)`.

### Position & sensing

`player_pos()` → eye `Pos` · `player_feet()` → feet `Pos` · `block_at(x, y=None, z=None)` → block id
· `on_edge(margin=0.3)` → `bool` (feet near a cell boundary) · `get(name, *args)` → the shared
trigger/observable catalog described above. Numeric results are returned as `int`/`float`, booleans
as `bool`, and descriptive/latest-event results as `str`.

```python
talos.get("server_tps")
talos.get("server tps")                         # same name; spaces normalize to underscores
talos.get("entity_count", "@e[tag=guard]", 48)
talos.get("block_near", "minecraft:lava", 8)  # exact count, not merely True/False
talos.get("villager_profession_changed")        # old -> new + id/UUID/type/3dp position
talos.get("entity_location", 123)               # type#123 @ x.xxx y.yyy z.zzz
```

### Input

`key(name, pressed=True)` (hold/release a logical key) · `tap(name)` (one-tick press) ·
`release_keys(*names)` (none = all) · `select_slot(n)` (hotbar 0–8).

### Inventory & containers

`inventory()` → `list[{slot,id,count}]` · `hotbar()` · `selected_slot()` · `count(item_id)` ·
`has(item_id)` · `find_slot(item_id)` · `container_items()` · `container_slot_count()` ·
`click_slot(slot, right=False)` · `move_stack(from, to)` · `take_stack(container_slot, player_slot)`
· `deposit(item_id, amount)` → moved · `withdraw(item_id, amount)` → moved · `craft(item_id,
count=1)` · `armor_item(slot)` · `equip_armor(from_slot, armor_slot)` · `screen()` ·
`close_screen()`.

### HUD

`hud(text, id="hud")` pins a line to the on-screen overlay (top-left); repeated calls with the same
id update in place, different ids stack (max 20 lines / 256 chars, `§` colour codes work, cleared
when the script stops). `hud_remove(id="hud")` · `hud_clear()`.

### Humanization & timing

`wait(a, b=None)` / `wait_between(a, b)` (right-skewed random pause) · `set_profile(name)` (aim/timing
profile: raw/natural/paranoid categories) · `set_seed(seed)` (reproducible runs) ·
`human(enabled=None)` (toggle/query session-arc fatigue) · `fatigue()` (0–1) · `on_break()` ·
`sleep(seconds)` · `ticks(n)` · `next_tick()` · `tick_count()`.

#### Human mode (session-arc)

`/talos human [on|off]` (or `talos.human(True)`) enables **session-arc humanization**: on top of the
stationary raw/natural/paranoid profile, a wall-clock **fatigue** model drifts your behaviour over the
session — reactions slow and spread, aim loosens and overshoots more, the walk wobbles wider — and
injects **idle micro-breaks** that pause pathing briefly (more often, and longer, as fatigue rises).
The HUD shows a `human » fatigue N% (Mm)` line while it's on.

The point: the fixed-parameter profiles are themselves a fingerprint a server can find over hours,
because a real human's parameters *drift*. This makes the input stream non-stationary. It is
**best-effort obfuscation of long-session statistical detection, not a guarantee of undetectability**,
and automation may still violate a server's rules. It models only *motor-level* imperfection
(overshoot, hesitation, breaks) — never *semantic* mistakes like attacking the wrong target.

### Events

`@talos.on("<event>")` registers a handler on the worker thread. Events and signatures:

| Event | Handler |
|---|---|
| `tick` | `fn()` — every game tick |
| `chat` | `fn(message, sender)` — any visible line; `sender` is a player name or `None` for system lines. **Your own messages echo back — guard loops.** |
| `entity_hurt` | `fn(type_id, entity_id, x, y, z)` — a tracked entity took damage |
| `health` | `fn(health)` — local health changed |
| `death` | `fn()` — local player died |
| `item_pickup` | `fn(item_id, amount)` — you picked up items |
| `goto_start` | `fn(x, y, z)` — a goto began planning |
| `goto_done` | `fn(success, detail)` — a goto finished |
| `goto_stuck` | `fn(detail)` — a segment failed; engine replanning |
| `disconnect` | `fn()` — left the world/server |

### Async, tasks & commands

Cooperative multitasking so several behaviors run at once:

- `@talos.task` / `talos.start(coro, name=None)` — run an `async def` as a concurrent task.
- `talos.aio.*` — awaitable versions of the blocking actions (`goto`, `goto_near`, `goto_xz`,
  `goto_block`, `follow`, `find_block`, `place_block`, `break_block`, `mine`, `mine_looking_at`,
  `kill_nearest`, `wait`, `wait_between`, `input`). Use these inside `async def` so other tasks keep
  running while one walks or mines.
- `@talos.on_start` / `@talos.on_tick` — module-level lifecycle hooks.
- `@talos.every(seconds=…, minutes=…, ticks=…)` — run a function on a cadence.
- `@talos.command("name")` — register a `/talos <name>` (or `/talos cmd <name>`) handler in Python;
  an `async def` handler runs as a task. Overrides built-ins like `goto`/`mine`/`kill`/`follow` when
  the name matches (the built-in checks `scriptOverride` first).
- `talos.run()` — start the task loop (implicit at module end). `talos.cancel_all()`,
  `TaskHandle.cancel()`, `talos.parallel(...)`, `talos.spawn(fn, …)`.
- `talos.input(prompt)` / `await talos.aio.input(prompt)` — block for the user's next chat message
  (captured locally, never sent).

### Snapshot types & errors

`Pos(.x/.y/.z)` · `Entity(.uuid/.type/.pos/.distance)` · `Player(.name/…)` ·
`Hit(.type/.id/.pos/.distance)` (`.type` is `"block"`/`"entity"`). Failures raise typed errors:
`TalosError`, `PathFailedError`, `OutOfReachError`, `NotFoundError`, `TargetLostError`,
`ActionCancelledError`, `WorldClosedError`.

```python
import talos

goal = talos.find_block("diamond_ore", radius=64)
if goal:
    talos.goto(goal)
    talos.mine(goal)
    talos.hud(f"mined {talos.count('minecraft:diamond')} diamonds")

@talos.on("entity_hurt")
def flee(type_id, entity_id, x, y, z):
    if type_id == "minecraft:player":
        talos.move_ahead(-3)      # back off 3 blocks

talos.run()
```

---

## VS Code extension

`vscode-extension/` pushes and runs Python scripts against the mod over a local WebSocket, with live
log streaming into a "Talos" output channel and Pylance autocomplete via the bundled `talos.pyi`
stubs.

**Features:** *Run Script in Minecraft* (Cmd/Ctrl+Alt+Enter), *Stop Script*, *Reconnect*, run-on-save
live reload, status-bar connection indicator, clickable Python tracebacks (jump to
`your/script.py:line`, including `require`'d libs).

### Compatible editors

The extension uses **only stable VS Code APIs** plus a plain `ws` WebSocket — no proposed or
proprietary APIs — so the same `.vsix` runs unmodified on any VS Code-compatible host at engine
`^1.85.0`:

| Fully supported | |
|---|---|
| **VS Code** | reference target |
| **Cursor** · **Windsurf** · **VSCodium** · **Trae** · **Google Antigravity** | VS Code forks |
| **Eclipse Theia** | via Open VSX |
| **code-server / Gitpod / GitHub Codespaces** | browser-hosted VS Code |

Install on any of them: `<editor> --install-extension talos-<version>.vsix --force` (`code`,
`cursor`, `windsurf`, `codium`, …) or **Extensions → "Install from VSIX…"**. Talos isn't on a
marketplace, so sideload the `.vsix` directly.

**Not supported** (different extension systems — would need a native client against the WebSocket
protocol in `vscode-extension/PROTOCOL.md`): JetBrains IDEs (IntelliJ, PyCharm…), Zed, Neovim/Vim,
Sublime Text, Emacs.

### Configuration & security

| Setting | Default | Description |
|---|---|---|
| `talos.host` | `127.0.0.1` | Must be loopback — the extension refuses anything else. |
| `talos.port` | `43077` | Port the mod's WebSocket server listens on. |
| `talos.tokenPath` | `~/.talos/token` | Per-session auth token file. |
| `talos.runOnSave` | `false` | Re-push and re-run on save (live reload). |

Pushing a script is remote code execution inside your game client, so the bridge is hardened: it
**only binds loopback**, is **token-gated** (a random per-session token in a file only your OS user
can read, sent as the first WebSocket frame — never in the URL), acts on **no frame before auth
succeeds**, and **never auto-replays** a script on reconnect. **Don't run untrusted scripts.**

---

## In-game editor

`/talos script editor` opens an in-game Python editor screen (`PythonEditorScreen`) for writing and
running scripts without leaving the game.

---

## Data locations

| Path | Contents |
|---|---|
| `~/.talos/rules.json` | Persisted event rules and schedules. |
| `~/.talos/macros/` | Recorded input macros (JSON, per-tick frames). |
| `~/.talos/token` | Per-session VS Code bridge auth token (regenerated every launch). |
| `.minecraft/talos/scripts/` | Python scripts run by `/talos script run` (and `require`'d libs). |

---

## Limitations

- **Client-side protocol boundaries, not bugs:** villager *inventories* are never synced to the
  client (equipment/profession/level are, and are covered by triggers); chest contents are only
  knowable while the chest screen is open; beacon effects only via the beacon screen. Honest ceilings
  on what a client mod can observe.
- **A flat 5-block jump is honestly impossible** and the planner reports it — parkour clears 3-gaps
  from near-standing, 4-gaps via edge takeoff, and the 5-block gap only with a snow-layer runway and
  chained momentum hops. It won't fake physics vanilla can't do.
- **Not a guaranteed anti-detection system.** Humanization varies trajectory *families* — best-effort
  obfuscation, not a guarantee against a determined observer or anti-cheat.
- **Build-verified, not all battle-tested in-game.** The codebase compiles and behavior matches the
  source, but several recent waves (background planning, momentum parkour, follow/selectors, raytrace,
  the chat/entity_hurt events, `require`/`args`, clickable tracebacks) haven't all been exercised
  against a live server yet. Expect rough edges in newly landed features before well-worn ones like
  pathing/mining.

---

## Development

Multi-module Gradle repo (Fabric Loom):

| Module | Purpose |
|---|---|
| `talos-mod/` | The mod — commands, pathing, rules, macros, aim, bridge, scripting glue. |
| `talos-pathing-baritone/` | Optional adapter letting Baritone (if installed) supersede the built-in pathfinder. |
| `talos-graalpy-runtime/` | Embedded GraalPy runtime + the `talos` Python package. |
| `vscode-extension/` | The editor extension for pushing/running scripts over the bridge. |
| `docs/` | Longer-form docs: architecture, commands, scripting, UI, VS Code bridge. |

Build and test:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :talos-mod:remapJar :talos-mod:test
```

Active development is on the `pathing-v2` branch (the physics-simulated pathfinder rewrite);
`talos-integration` and earlier `talos-p*`/`wt/cmd*` branches are already merged forward.

> `TalosCommands.java`, the `talos` Python package and this README are the source of truth over any
> older per-topic files in `docs/` (some predate the current command naming).
