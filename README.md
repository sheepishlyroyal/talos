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

### Raytrace & local coordinates

`/talos raytrace` is the look-relative coordinate + raycast primitive.

| Command | Description |
|---|---|
| `/talos raytrace get <x> <y> <z>` (or bare `/talos raytrace <x> <y> <z>`) | Resolve a coordinate triple to a world point, reported to **3 decimals**, plus the block id there. |
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
/talos get list                                # enumerate every observable
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
```

`get entity`/`get blockpos` use Python-style 0-based indexing (`0` = nearest, `-1` = furthest).
`slot` names: `hotbar.1`–`hotbar.9`, `inv.1`–`inv.27`, `head`/`chest`/`legs`/`feet`, `offhand`,
`held`, `cursor`, `container.N`, `saddle`, `horsearmor`.

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

## Trigger catalog (~206 unique families)

`Trigger` enum: `talos-mod/src/main/java/dev/talos/client/rules/EventRuleEngine.java`. Grouped as
documented there:

- **Vitals** — health/hunger/air thresholds, damage taken, healed, death, respawn, XP level.
- **Player state** — on fire, falling, sneaking, sprinting, swimming, gliding, underwater,
  sleeping/woke, mounted/dismounted, moving/stopped, climbing, blocking, using item, collided, hurt,
  freezing, jumped, landed, incoming projectile, window focus, screen opened/closed, offhand
  changed, looking at entity, entity spawned/removed, chat mention, hotbar empty, armor missing,
  container full/empty.
- **Items & screens** — held item changed, tool broken, inventory full, slot changed, container
  opened/closed, effect added/removed, item gained/lost, item/hotbar item count.
- **Entities** — `entity_count`/`entity_near`/`entity_gone` with full selector support
  (`@e[type=,tag=,name=]`/`@a`/`@p`/`@s`), radius `-1` = whole loaded world.
- **Blocks** — `block_count`/`block_near` (1 Hz staggered cube scans, radius ≤16), plus
  looking-at/standing-on/at-feet/above-head change events.
- **World** — dimension changed, world loaded/unloaded, day/night, rain/clear, player joined/left,
  biome changed, chunk changed, entered/left a region.
- **Text & sound** — chat, title, subtitle, actionbar (with `matching`/`count within`), every sound
  instance the client plays.
- **Interactions** — attack/use block, attack/use entity, use item.
- **Per-entity tracking** — every loaded entity is diffed per tick: held/offhand/armor changed,
  hurt, died, damaged/healed (with amount), started burning, mounted/dismounted, sneaking,
  sprinting, using item, blocking, gliding, swimming, sleeping, baby grown, villager
  profession/level changed, other players' held/offhand/armor/gamemode changed, item
  spawned/picked-up (exact collector + item via the vanilla pickup packet)/despawned, projectile
  launched.
- **Containers** — open-container item gained/lost (per-slot diff — the chest-indexing primitive),
  container title.
- **Network wave** — every S2C packet by id, explosions, boss bar shown/updated/removed + percent
  metric, scoreboard sidebar appeared/removed/title/score/line add/remove.
- **Held-item detail** — enchant level, enchanted, has custom name, name changed.
- **Unload vs. gone** — entity/item unloaded vs. despawned/removed, disambiguated by a chunk-loaded
  test at last known position (no timers).
- **Combat/consumables** — totem popped, pearl thrown/landed, teleported (>12 blocks same-dimension
  jump), potion splashed/drank.
- **Projectile lifecycle** — generic `projectile_hit`/`projectile_stopped` (any throwable via
  `matching`) plus named sugar: snowball/egg thrown/hit.
- **Block-entity content** — sign text changed, item frame contents changed.
- **~40 numeric metrics** (`COMPARE` kind — all support instant, `for <seconds>` sustained and
  `changes … within <seconds>` windowed-delta modes): health, hunger, air, XP level/progress,
  armor/held durability, FPS, ping, chunks loaded, light level, x/y/z position, speed, saturation,
  absorption, armor points, fall distance, time ticks, entity total, players online, idle seconds,
  velocity Y, moon phase, day count, empty/occupied slots, container items, memory used %, nearest
  player/hostile/animal/item distance, dropped items/XP orbs/arrows near, crosshair distance, spawn
  distance, fire/frozen ticks, hurt time, stuck arrows, vehicle speed, effect count, world border
  distance, server TPS, yaw, pitch, held count, max health, world age, boss bar percent.
- **Clock** — `tick_every <n>`.

Four generic catch-alls exist so nothing is un-triggerable: `packet_received matching <id>` (any S2C
packet), `entity_status` (any status byte), `particle_seen` (any particle, id + position), `sound`
(any sound instance).

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
· `on_edge(margin=0.3)` → `bool` (feet near a cell boundary).

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
`sleep(seconds)` · `ticks(n)` · `next_tick()` · `tick_count()`.

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
