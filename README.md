# Talos

Talos (Java packages still `dev.talos`, formerly branded "Talos") is a **client-side**
Minecraft 1.21.11 Fabric automation mod. It gives you a physics-simulated pathfinder that
mines/bridges/pillars through obstacles, a 206-family event-rule engine that can react to
almost anything the client observes, humanized aim and input macros, and an embedded GraalPy
Python runtime (plus a VS Code bridge) for scripting on top of all of it. Everything runs as
client commands — no server permission level, no server-side mod required.

- **Pathfinding** — a from-scratch A* planner over a deterministic mirror of vanilla player
  physics (`talos-mod/src/main/java/dev/talos/client/pathing/sim/`), not a waypoint graph. It
  mines, bridges, pillars, shafts, and swims as needed, tick-sliced so it never freezes the
  client, replanning live off real player state.
- **Event rules** — `/talos on <trigger> ... run <command>`, ~206 unique trigger families
  (vitals, entities, blocks, items, world, network packets, text, sound/particles), each with
  comparisons, sustained/windowed temporal modes, and selector filtering.
- **Humanized aim** — an off-grid "yellow cube" target model with per-session fast/slow
  rotation profiles, quadratic speed modulation, and a live red preview path — not an instant
  snap.
- **Macros** — channel-selective recording/replay of real per-tick input (movement, jump,
  sneak, sprint, clicks, look, hotbar).
- **Scripting** — embedded GraalPy (`import talos`), an in-game block editor, and a VS Code
  extension that pushes/runs scripts over a local, token-gated WebSocket.

## Requirements & install

| | |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.141.4+1.21.11 |
| Java | 21 (build + run) |
| Yarn mappings | 1.21.11+build.6 |

Optional: the separately distributed `talos-pathing-baritone` adapter is picked up
automatically if installed and takes priority over the built-in pathfinder — but Talos'
own sim-based pathfinder (`TalosPathingEngine`) is **always available** with no dependency,
so `/talos goto` works out of the box.

Build the mod jar with a Java 21 `JAVA_HOME`:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS example
./gradlew :talos-mod:remapJar
```

The output jar lands in `talos-mod/build/libs/`. Drop it into your Fabric `mods/` folder
alongside a matching Fabric API build.

## Commands

Every command lives under `/talos`; `/talos` is kept as a full alias (redirects to the same
command tree — every example below works with either prefix).

### Pathfinding & world actions

| Command | Description |
|---|---|
| `/talos goto <x> <y> <z>` | Path to an exact block. Coordinates accept `~`-relative syntax. |
| `/talos goto near <x> <y> <z> <range>` | Path to within `range` blocks of a position. |
| `/talos goto <x> <z>` / `/talos xz <x> <z>` | Path to an X/Z column at your current Y. |
| `/talos find block <blockPredicate> [radius]` | Nearest matching block in loaded chunks. |
| `/talos glow <x> <y> <z> [seconds]` | Wireframe box around a block, for confirming a lookup. |
| `/talos mine <x> <y> <z>` | Break a block via the humanized, tool-aware `BreakBlockAction`. |
| `/talos mine direction <yaw> <pitch>` | Mine the block hit by a raycast (`^`-relative angles). |
| `/talos mine block <blockPredicate> [index]` | Mine the `index`-th closest match (0-based, `-1` = furthest). |
| `/talos place <x> <y> <z>` | Place your held block, verified against the server state. |
| `/talos kill nearest [radius]` | Attack the nearest hostile within `radius` (default 6, 1–64). |

```
/talos goto ~ ~10 ~
/talos goto near 100 64 200 3
/talos mine direction ^ ^-30
/talos mine block minecraft:diamond_ore -1
/talos place ~ ~1 ~
```

`/talos goto` runs with mining **and** placing enabled by default — it tunnels through walls,
bridges gaps, digs vertical shafts, and nerdpoles up, all through the same simulated-physics
planner, never as fixed "modes" you have to choose.

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

```
/talos walk w blocks 5 touch
/talos key jump hold 1.5
/talos macro record mine_loop clicks+look
/talos macro stop
/talos macro play mine_loop 3
```

Macro channels: `move`, `jump`, `sneak`, `sprint`, `clicks`, `look`, `hotbar`, plus shorthands
`keys` (all keyboard), `input` (keys+clicks), and `all`. A `clicks+look`-only macro leaves
movement free for `/talos goto` or manual play to run alongside it.

### Inventory

| Command | Description |
|---|---|
| `/talos inv list` | List occupied slots in the current screen handler (player inventory, or an open chest). |
| `/talos inv move <from> <to>` | Move a stack between slots. |
| `/talos inv hotbar <from> <slot 1-9>` | Swap a slot into a hotbar position. |
| `/talos inv deposit all\|<item>` | Quick-move matching stacks into an open container. |
| `/talos inv withdraw all\|<item>` | Quick-move matching stacks out of an open container. |
| `/talos inv armor helmet\|chestplate\|leggings\|boots <from>` | Equip an armor piece from a slot. |

```
/talos inv list
/talos inv deposit minecraft:cobblestone
/talos inv armor chestplate 12
```

### Event rules

`/talos on <trigger> ... run <command>` arms a persistent rule (saved to
`~/.talos/rules.json`). `run` may be prefixed with `chat ` to send a chat message instead of
dispatching a command; the command dispatches through `/talos` first, then falls back to the
server. Placeholders like `{value}`, `{health}`, `{x}`/`{y}`/`{z}` are substituted from the
firing event.

Trigger grammar depends on the trigger's *kind*:

| Kind | Grammar |
|---|---|
| `NONE` | `on <trigger> run <command>` |
| `NUMBER` | `on <trigger> <value> run <command>` |
| `TEXT` | `on <trigger> [matching "<text>"] run <command>`, plus `count above <n> within <seconds> run` |
| `COMPARE` | `on <trigger> above\|below\|equals <n> [for <seconds>] run`, plus `changes above\|below\|equals <delta> within <seconds> run` |
| `ENTITY_COUNT` | `on <trigger> <selector> radius <r\|-1> above\|below\|equals <n> run` |
| `ENTITY_PRESENCE` | `on <trigger> <selector> radius <r\|-1> run` |
| `BLOCK_COUNT` | `on <trigger> <block> radius <r> above\|below\|equals <n> run` |
| `BLOCK_PRESENCE` | `on <trigger> <block> radius <r> run` |
| `ITEM_COUNT` | `on <trigger> <item> above\|below\|equals <n> run` |
| `REGION` | `on <trigger> <x1> <y1> <z1> <x2> <y2> <z2> run` |

Selectors follow Minecraft syntax: `@e[type=...,tag=...,name=...,distance=...]`, `@a`, `@p`
(nearest player, excluding yourself), `@s` (self). Triggers whose event carries a subject
entity (pickups, projectile lifecycle, damage, mounts, potions, item frames — ~37 families in
total) additionally accept a trailing selector to filter *which* entity's event fires the
rule, composable with `matching`.

```
/talos on health below 6 for 2 run chat low health, retreating
/talos on entity_count @e[type=zombie] radius 16 above 3 run chat too many zombies nearby
/talos on chat matching "diamond" count above 3 within 10 run chat spam detected
/talos on health changes below -4 within 2 run chat taking burst damage
/talos on block_near minecraft:lava radius 5 run chat lava nearby
/talos on item_picked_up @e[type=player] matching diamond run chat someone grabbed a diamond
/talos on entered_region 100 60 100 120 80 120 run chat entered the build zone
/talos on tick_every 100 run /talos get health
```

Other rule commands: `/talos rules list`, `/talos rules remove <id>`, `/talos rules clear`,
`/talos every <seconds> run <command>` (persists), `/talos after <seconds> run <command>`
(this session only), `/talos on list` (dumps every trigger's grammar).

### Observables (`/talos get`)

Instant readouts through the exact same evaluation path rules use.

```
/talos get health                              # numeric metric
/talos get list                                # enumerate every observable
/talos get position                             # string readout
/talos get slot hotbar.1                        # named slot contents
/talos get entity @e[type=zombie] -1            # 0-based/negative index; -1 = furthest match
/talos get blockpos minecraft:diamond_ore 0      # nearest matching block within 32
/talos get sign                                 # sign under the crosshair
/talos get lectern                              # book + page under the crosshair
/talos get skull / banner / campfire / item_frame
/talos get sounds                               # distinct sound ids played in the last 5s
/talos get particles                            # distinct particle ids in the last 3s
/talos get crosshair_particles                  # particles near the look ray in the last 2s
/talos get block ~ ~-1 ~                        # block id at ~-relative coords
/talos get block direction ^ ^                  # block id along a ^-relative raycast
```

`get entity`/`get blockpos` use the same Python-style 0-based indexing as `look`/`track`/`mine
block`: `0` = nearest, `1` = next, `-1` = furthest, `-2` = second-furthest.

`slot` names: `hotbar.1`–`hotbar.9`, `inv.1`–`inv.27`, `head`/`chest`/`legs`/`feet`,
`offhand`, `held`, `cursor`, `container.N`, `saddle`, `horsearmor`.

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

All aim now runs through the humanized cube-aim controller by default (`AimController`, no
more instant snap): a 1x1m yellow guide cube is rendered **off-grid**, centered exactly on the
intended point. The actual aim spot — the red X — lands on a visible face chosen with
probability proportional to that face's visible flat area, center-biased. Rotation draws a
random fast sensitivity far out and a random slow sensitivity for the final approach, blending
smoothly (never instantly) once the look ray passes within 0.4m of the cube, with per-tick
jitter and a red dotted preview line tracing the exact curve the crosshair is about to draw. A
per-session quadratic speed modulation (peaking mid-flight, never linear) adds a small random
speed swell/sag and a slight bow to the path. `/talos track` keeps this same aim session alive
against a moving target, so the crosshair trails it instead of re-snapping every tick.

## Trigger catalog (206 unique families)

`Trigger` enum: `talos-mod/src/main/java/dev/talos/client/rules/EventRuleEngine.java`. Grouped
as documented there:

- **Vitals** — health/hunger/air thresholds, damage taken, healed, death, respawn, XP level.
- **Player state** — on fire, falling, sneaking, sprinting, swimming, gliding, underwater,
  sleeping/woke up, mounted/dismounted, moving/stopped, climbing, blocking, using item,
  collided, hurt, freezing, jumped, landed, incoming projectile, window focus, screen
  opened/closed, offhand changed, looking at entity, entity spawned/removed, chat mention,
  hotbar empty, armor missing, container full/empty.
- **Items & screens** — held item changed, tool broken, inventory full, slot changed,
  container opened/closed, effect added/removed, item gained/lost, item/hotbar item count.
- **Entities** — `entity_count`/`entity_near`/`entity_gone` with full selector support
  (`@e[type=,tag=,name=]`/`@a`/`@p`/`@s`), radius `-1` = whole loaded world.
- **Blocks** — `block_count`/`block_near` (1 Hz staggered cube scans, radius ≤16), plus
  looking-at/standing-on/at-feet/above-head change events.
- **World** — dimension changed, world loaded/unloaded, day/night, rain/clear, player
  joined/left, biome changed, chunk changed, entered/left a region.
- **Text & sound** — chat, title, subtitle, actionbar (with `matching`/`count within`), every
  sound instance the client plays.
- **Interactions** — attack/use block, attack/use entity, use item.
- **Per-entity tracking** — *every* loaded entity is diffed per tick: held/offhand/armor
  changed, hurt, died, damaged/healed (with amount), started burning, mounted/dismounted,
  sneaking, sprinting, using item, blocking, gliding, swimming, sleeping, baby grown, villager
  profession/level changed, other players' held/offhand/armor/gamemode changed, item
  spawned/picked-up (exact collector + item, via the vanilla pickup packet)/despawned,
  projectile launched.
- **Containers** — open-container item gained/lost (per-slot diff — the chest-indexing
  primitive), container title.
- **Network wave** — every S2C packet by id, explosions, boss bar shown/updated/removed +
  percent metric, scoreboard sidebar appeared/removed/title/score/line add/remove.
- **Held-item detail** — enchant level, enchanted, has custom name, name changed.
- **Unload vs. gone** — entity/item unloaded vs. despawned/removed, disambiguated by a
  chunk-loaded test at last known position (no timers, no guessing).
- **Combat/consumables** — totem popped, pearl thrown/landed, teleported (>12 blocks,
  same-dimension jump), potion splashed/drank.
- **Projectile lifecycle** — generic `projectile_hit`/`projectile_stopped` (covers *any*
  throwable via `matching`) plus named sugar: snowball/egg thrown/hit.
- **Block-entity content** — sign text changed, item frame contents changed.
- **~40 numeric metrics** (`COMPARE` kind — all support instant, `for <seconds>` sustained, and
  `changes ... within <seconds>` windowed-delta modes): health, hunger, air, XP level/progress,
  armor/held durability, FPS, ping, chunks loaded, light level, x/y/z position, speed,
  saturation, absorption, armor points, fall distance, time ticks, entity total, players
  online, idle seconds, velocity Y, moon phase, day count, empty/occupied slots, container
  items, memory used %, nearest player/hostile/animal/item distance, dropped items/XP
  orbs/arrows near, crosshair distance, spawn distance, fire/frozen ticks, hurt time, stuck
  arrows, vehicle speed, effect count, world border distance, server TPS, yaw, pitch, held
  count, max health, world age, boss bar percent.
- **Clock** — `tick_every <n>`.

Four generic catch-alls exist specifically so nothing is ever un-triggerable, even before it
has a named sibling: `packet_received matching <id>` (any S2C packet), `entity_status`
(any status byte of any entity), `particle_seen` (any particle, id + position), `sound`
(any sound instance the client plays).

## Scripting, block editor, VS Code bridge

`/talos script run <name>` runs `.minecraft/talos/scripts/<name>.py` through an embedded
GraalPy runtime (`talos-graalpy-runtime`) exposing a small curated `import talos` API
(`goto`/`goto_near`/`goto_xz`, `find_block`/`find_entity`/`find_item`, `place_block`/
`break_block`/`kill_nearest`/`look_at`, humanization controls, `@talos.on(...)` event hooks).
`/talos script stop` hard-stops it, even mid-loop. `/talos editor` opens the in-game block
editor screen. `/talos bridge allow` / `/talos bridge status` control the local, token-gated
WebSocket server (`~/.talos/token`) that the `vscode-extension/` uses to push and run scripts
from VS Code with live log streaming. Full details: `docs/scripting.md`, `docs/vscode.md`,
`docs/ui.md`.

## Data locations

| Path | Contents |
|---|---|
| `~/.talos/rules.json` | Persisted event rules and schedules. |
| `~/.talos/macros/` | Recorded input macros (JSON, per-tick frames). |
| `~/.talos/token` | Per-session VS Code bridge auth token (regenerated every launch). |
| `.minecraft/talos/scripts/` | Python scripts run by `/talos script run`. |

## Limitations

- **Client-side protocol boundaries, not bugs**: villager *inventories* are never synced to
  the client (equipment/profession/level are, and are covered by rule triggers); chest
  contents are only knowable while the chest screen is open; beacon effects are only readable
  via the beacon's own screen. These are honest ceilings on what a client mod can observe, not
  gaps waiting to be fixed.
- **Not a guaranteed anti-detection system.** Humanization (aim, movement, timing) varies
  trajectory *families* — it is best-effort obfuscation, not a guarantee against a determined
  observer or anti-cheat.
- **Build-verified, not all battle-tested in-game.** The codebase compiles and the described
  behavior matches the source, but several recent feature waves (the network/per-entity
  trigger wave, projectile lifecycle triggers, block-entity getters, Python-style indexing,
  the humanized cube-aim rewrite) have not all been exercised against a live server yet. Expect
  rough edges in newly landed trigger families before well-worn ones like pathing/mining.
- `docs/commands.md` and `docs/vscode.md` in this repo predate several of the changes above
  (older `/talos`-only naming, an outdated "Baritone required" pathing error). Treat
  `TalosCommands.java` and this README as the source of truth over those docs until they're
  refreshed.

## Development

Multi-module Gradle repo (Fabric Loom):

| Module | Purpose |
|---|---|
| `talos-mod/` | The mod itself — commands, pathing, rules, macros, aim, bridge, scripting glue. |
| `talos-pathing-baritone/` | Optional adapter that lets Baritone (if installed) supersede the built-in pathfinder. |
| `talos-graalpy-runtime/` | Embedded GraalPy runtime + the `talos` Python package. |
| `vscode-extension/` | VS Code extension for pushing/running scripts over the bridge. |
| `docs/` | Longer-form docs: architecture, commands, scripting, UI, VS Code bridge. |

Active development is on the `pathing-v2` branch (the physics-simulated pathfinder rewrite);
`talos-integration` and the `talos-p*`/`wt/cmd*` branches are earlier work already merged
forward. Build with `./gradlew :talos-mod:remapJar` under a Java 21 `JAVA_HOME`.
