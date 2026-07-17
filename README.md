# Talos — control your own Minecraft client with Python

Talos is an in-client automation framework: script movement, mining, inventory, combat, building
and world reactions **in your existing Minecraft session** — real Python, running inside the game.

```python
import talos

ore = talos.find_block("iron_ore", radius=64)
talos.goto(ore)          # physics-simulated pathfinding: walks, jumps, bridges, mines through
talos.mine(ore)
talos.chat("got one")
```

Type it in the in-game editor, push it from VS Code, or run it from your terminal
(`talos mine_iron.py`) with logs streaming back. Take over manually at any moment — it's your
client, your player.

**Docs:** https://sheepishlyroyal.github.io/talos/ · **Download:** [releases](https://github.com/sheepishlyroyal/talos/releases) · **License:** [MIT](LICENSE)

> If Talos is useful or interesting to you, consider starring the repository — it helps other
> Minecraft automation developers find it.

## Why Talos (and not …)

| Capability | Talos | Baritone | Mineflayer |
|---|---|---|---|
| Controls your **existing player/client** | Yes | Yes | No (headless bot account) |
| **Python** scripting | Yes | No | No (JavaScript) |
| In-client event rules (~206 trigger families) | Yes | Limited | Via API events |
| Manual takeover mid-script | Yes | Limited | Not applicable |
| Humanized aim & timing (tunable) | Yes | No | No |
| Inventory/container automation | Yes | Limited | Yes |
| Terminal + VS Code control with live logs | Yes | No | N/A (is a library) |
| Runs without a mod on the server | Yes | Yes | Yes |

Baritone is a superb pathfinder; Mineflayer is a superb bot library. Talos is the piece neither
covers: a scriptable automation layer for the client you actually play on.

Under the hood: a **client-side Fabric mod** (MC **1.21.11 / 26.1 / 26.2**) with a
physics-simulated A\* pathfinder, a ~206-family event-rule engine, humanized aim and input macros,
and an embedded **GraalPy** runtime. Everything runs as client commands — no server permission
level, no server-side mod.

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

## Download

Prebuilt jars for all three Minecraft versions ship on the
[**Releases**](https://github.com/sheepishlyroyal/talos/releases) page:

| Minecraft | Jar | Status |
|---|---|---|
| 1.21.11 | `talos-mod-1.1.0-mc1.21.11.jar` | **Stable** — primary development target, playtested |
| 26.1 | `talos-mod-1.1.0-mc26.1.jar` | Experimental — full port, compile- and content-verified; less playtime |
| 26.2 | `talos-mod-1.1.0-mc26.2.jar` | Experimental — full port, compile- and content-verified; less playtime |

Drop the matching jar into your Fabric `mods/` folder next to a matching Fabric API build, then launch.
Or build from source (below).

## Use Talos with an LLM

Talos ships an **agent skill** ([`skill/SKILL.md`](skill/SKILL.md)) — the full authoring contract for
Talos Python scripts, `/talos` commands and event rules, condensed for a model.

> **Best setup: Talos + an LLM + the terminal CLI.** The LLM writes scripts from the skill, runs them
> itself with `talos run script.py`, and reads the streamed logs to iterate — a closed loop where you
> describe the goal and the model writes, runs, watches and fixes.

- **Claude Code / Claude:** drop `skill/SKILL.md` into `~/.claude/skills/talos/SKILL.md` (it auto-loads
  by name), or copy the repo folder into your project's `.claude/skills/`.
- **Any other LLM (ChatGPT, Gemini, Cursor, local models):** paste the contents of `skill/SKILL.md` into
  the system prompt / context. The model can then write correct Talos scripts and commands directly.

See the [**wiki**](https://github.com/sheepishlyroyal/talos/wiki) for the full guide, including a
dedicated **LLM usage** page.

## Requirements & install
    
| | |
|---|---|
| Minecraft | 1.21.11 - 26.1 - 26.2 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.141.4+1.21.11 - 26.1 - 26.2 |
| Java | 21 (build + run) |

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

With **Human mode on** (`/talos human on` or `talos.human(True)`), command and Python aim—including
absolute angles, coordinates, blocks and entities—runs through the
humanized cube-aim controller (`AimController`, no direct snap): a 1×1m yellow guide cube is rendered
**off-grid**, centered exactly on the intended point. The
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
/talos get light_level ~ ~1 ~                   # sample one block above the player's feet
/talos get nearest_hostile_distance ^ ^ ^8      # nearest hostile measured from 8 blocks ahead
/talos get block_count stone 8 ~ ~-4 ~          # center the radius-8 cube four blocks below
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
| `COMPARE` | none; spatial metrics also accept `<x> <y> <z>` and entity-distance/count metrics optionally take `[radius]` | Current numeric metric through the rule engine's exact calculator. This includes `server_tps`. Spatial forms are listed below. |
| threshold `NUMBER` | none | Current underlying value (`health_below`/`health_above` → health, `hunger_below` → hunger, `air_below` → air, `xp_level_above` → XP level); `tick_every` returns the current Talos tick. |
| `ENTITY_COUNT` / `ENTITY_PRESENCE` | `<selector> [radius=-1] [x y z]` | Exact matching loaded-entity count around the player or supplied point. `entity_near` and `entity_gone` deliberately return the count, not a lossy boolean. All selector identities and filters work. |
| `BLOCK_COUNT` / `BLOCK_PRESENCE` | `<block> [radius=16] [x y z]` | Exact matching block count in the same cube scan used by rules, centered on the player or supplied point. |
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
by entity-trigger payloads and returns `type#id[/name] @ x.xxx y.yyy z.zzz`, or numeric `-1` if that
entity is not currently loaded. Runtime IDs are session-scoped and can be reused after entities unload.

Spatial arguments accept absolute coordinates, `~` offsets from the player's feet, and
`^left ^up ^forward` offsets from the eyes/look direction. A single Python string works too:
`talos.get("light_level", "~ ~1 ~")`. Location-capable live getters are `light_level`,
`entity_total`, all four `nearest_*_distance` metrics, `dropped_items_near`, `xp_orbs_near`,
`arrows_near`, `spawn_distance`, `world_border_distance`, `biome`, `standing_on`,
`block_at_feet`, and `block_above_head`, plus the entity/block families and both region corners.
Other getters reject extra coordinates instead of silently ignoring them.

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
| `COMPARE` | `on <trigger> [at <x> <y> <z> [radius <r>]] above\|below\|equals <n> [for <seconds>] run`, plus `changes above\|below\|equals <delta> within <seconds> run` |
| `ENTITY_COUNT` | `on <trigger> <selector> radius <r\|-1> [at <x> <y> <z>] above\|below\|equals <n> run` |
| `ENTITY_PRESENCE` | `on <trigger> <selector> radius <r\|-1> [at <x> <y> <z>] run` |
| `BLOCK_COUNT` | `on <trigger> <block> radius <r> [at <x> <y> <z>] above\|below\|equals <n> run` |
| `BLOCK_PRESENCE` | `on <trigger> <block> radius <r> [at <x> <y> <z>] run` |
| `ITEM_COUNT` | `on <trigger> <item> above\|below\|equals <n> run` |
| `REGION` | `on <trigger> <x1> <y1> <z1> <x2> <y2> <z2> run` |

The optional `at` branch exists only for spatial metrics listed in the getter section and for the
entity/block condition families. Non-spatial metrics reject it. Rule coordinates are resolved when
the rule is armed and saved as exact world coordinates, so a persistent `~`/`^` rule does not drift
with the player after creation.

```
/talos on health below 6 for 2 run chat low health, retreating
/talos on entity_count @e[type=zombie] radius 16 above 3 run chat too many zombies
/talos on chat matching "diamond" count above 3 within 10 run chat spam detected
/talos on health changes below -4 within 2 run chat taking burst damage
/talos on block_near minecraft:lava radius 5 run chat lava nearby
/talos on light_level at ~ ~1 ~ below 8 run chat it is dark above me
/talos on entity_near @e[type=zombie] radius 12 at ^ ^ ^20 run chat zombie near the point ahead
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
| `/talos human intensity <0..3>` | Global more/less-humanisation dial: 0 = near-robotic, 1 = profile default, 3 = exaggerated. Persisted. |
| `/talos human set <knob> <value>` | Override one humanisation knob (tab-completes knob names; values clamp into safe ranges). Persisted. |
| `/talos human show` | Show profile, intensity, active overrides and the effective aim/timing numbers. |
| `/talos human reset` | Clear intensity + all knob overrides back to the pure profile. |
| `/talos debug [on\|off\|status]` | Master switch for detailed logging (see Detailed logging below). Bare form / `status` reports the state and the session log-file path. |
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
| `armor_durability` | Lowest remaining durability percentage among worn damageable armor; `-1` if none is damageable. A genuinely full piece remains `100`. |
| `held_durability` / `held_count` | Main-hand remaining durability percentage (`-1` if not damageable) / stack count. |
| `fps` / `ping` | Client FPS / local tab-list latency in milliseconds (`-1` if unavailable). |
| `chunks_loaded` | Number of chunks currently held by the client chunk source. |
| `light_level` | Maximum local raw brightness at the player's block, or at an optional supplied coordinate. |
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
| `nearest_player_distance` | Distance to the nearest other loaded player, or `-1` if none. |
| `nearest_hostile_distance` | Distance to the nearest loaded `Monster`, or `-1` if none. |
| `nearest_animal_distance` | Distance to the nearest loaded `Animal`, or `-1` if none. |
| `nearest_item_distance` | Distance to the nearest dropped item entity, or `-1` if none. |
| `dropped_items_near` / `xp_orbs_near` / `arrows_near` | Count of that entity category within 16 blocks. |
| `crosshair_distance` | Eye-to-hit distance for the current block/entity hit, or `-1` on a miss. |
| `spawn_distance` | Distance from the player's block to the client world's respawn position. |
| `fire_ticks` / `frozen_ticks` / `hurt_time` | Remaining fire ticks / accumulated frozen ticks / current hurt animation ticks. |
| `stuck_arrows` | Arrows visibly stuck in the local player. |
| `vehicle_speed` | Mounted vehicle horizontal blocks per second, or `-1` when unmounted. |
| `effect_count` | Number of active status effects. |
| `world_border_distance` | Shortest distance from the player to the world border. |
| `server_tps` | Client estimate of server TPS from world-time advance over a rolling five-client-second window, capped at `20`. |
| `yaw` / `pitch` | Wrapped yaw in degrees / pitch in degrees. |
| `bossbar_percent` | Current/most recently updated boss-bar progress as `0`–`100`; `-1` when no bar is present. |

### Parameterized live values (9)

| Name | Getter call | Getter return | Trigger `{value}` |
|---|---|---|---|
| `entity_count` | `get("entity_count", selector, radius=-1, [x,y,z])` | Exact matching loaded-entity count around player/point. | The count which satisfied the comparison. |
| `entity_near` | `get("entity_near", selector, radius=-1, [x,y,z])` | Exact count, deliberately not a lossy boolean. | Exact count plus every matching entity identity and 3dp position when it changes from zero to nonzero. |
| `entity_gone` | `get("entity_gone", selector, radius=-1, [x,y,z])` | Exact count. | `count=0` plus the selector when the count changes to zero. |
| `block_count` | `get("block_count", block, radius=16, [x,y,z])` | Exact count in the same centered cube used by rules; radius is capped at 16. | The count which satisfied the comparison. |
| `block_near` | `get("block_near", block, radius=16, [x,y,z])` | Exact count in that cube. | Count, block ID, and configured center when it changes from zero to nonzero. |
| `item_count` | `get("item_count", item)` | Total matching item count in the player inventory. | The count which satisfied the comparison. |
| `hotbar_item_count` | `get("hotbar_item_count", item)` | Total matching item count in hotbar slots 1–9. | The count which satisfied the comparison. |
| `held_enchant` | `get("held_enchant", enchantment)` | Main-hand enchantment level, or `0`. | The level which satisfied the comparison. |
| `entered_region` / `left_region` | `get(name, x1, y1, z1, x2, y2, z2)` | Whether currently inside / outside the inclusive cuboid. Corners accept `~`/`^`. | `entered_region` or `left_region` plus the exact 3dp crossing position. |

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
| `damage_taken` / `healed` | Local health decreases / increases. | Exact `amount`, resulting `health`, and player position at 3dp. |
| `death` / `respawn` | Local player dies / becomes alive. | Resulting health and player position at 3dp. |
| `on_fire`, `falling`, `sneaking`, `sprinting`, `swimming`, `gliding`, `underwater`, `sleeping` | Local player enters that state. | State-specific detail and player position at 3dp. Same-name getters return the current boolean where available (`falling` remains latest-event). |
| `woke_up` | Sleeping changes to awake. | `woke_up` and player position at 3dp. |
| `mounted` / `dismounted` | Local vehicle changes. | Vehicle registry ID and player position at 3dp. |
| `moving` / `stopped` | Horizontal motion starts / stops. | Speed/state and player position at 3dp. `moving` getter returns the current boolean. |
| `climbing`, `blocking`, `using_item`, `collided`, `hurt`, `freezing` | Local player enters that state. | Relevant item/tick/state detail and player position at 3dp. Same-name getters return current booleans for `climbing`, `blocking`, `using_item`, and `hurt`; the others retain their latest occurrence. |
| `jumped` | Leaves ground with upward velocity above `0.2`. | Vertical velocity and player position at 3dp. |
| `landed` | Returns to the ground. | Fall distance and landing position at 3dp. |
| `projectile_incoming` | A moving arrow within 12 blocks is travelling toward the player's eyes. | Projectile identity, exact position, and XYZ velocity at 3dp. |
| `window_focused` / `window_unfocused` | Game window gains / loses focus. | `focused` / `unfocused`. `window_focused` getter returns current focus. |
| `screen_opened` / `screen_closed` | Screen class changes. | Opened / closed screen class simple name. |
| `offhand_changed` | Local offhand stack type changes. | New item registry ID. |
| `looking_at_entity` | Crosshair entity type changes to a non-empty hit. | Entity type registry ID. |
| `mention` | Visible text contains the local player's name, case-insensitive. | Full message text. |
| `hotbar_empty` / `armor_missing` | Hotbar becomes empty / any armor slot becomes empty. | State and player position at 3dp. |
| `container_full` / `container_empty` | All / none of the external container slots are occupied. | State and player position at 3dp. |
| `held_changed` / `tool_broken` | Main-hand type changes / a damageable held item disappears as air. | New held item ID / broken item ID. |
| `inventory_full` | Player inventory has no free slot. | `empty_slots=0` and player position at 3dp; getter returns current boolean. |
| `slot_changed` | Selected hotbar slot changes. | New one-based slot number (`1`–`9`) and player position at 3dp. |
| `container_opened` / `container_closed` | An external container opens / closes. | Screen and player position at 3dp. |
| `effect_added` / `effect_removed` | Local active-effect ID appears / disappears. | Effect registry ID. |
| `item_gained` / `item_lost` | Player inventory count changes. | `item_id xamount` delta. |
| `looking_at_block` | Crosshair block ID changes to a non-miss. | Block registry ID. |
| `standing_on` / `block_at_feet` / `block_above_head` | The corresponding sampled block changes. | New block registry ID. `standing_on` and `block_at_feet` getters return the current ID. |
| `dimension_changed` | Dimension registry key changes. | New dimension ID. |
| `world_loaded` / `world_unloaded` | Client world appears / disappears. | Loaded/unloaded dimension ID. A getter requires an active world, so `world_unloaded` cannot be queried after disconnection. |
| `time_day` / `time_night` | Overworld clock crosses into day / night. | Exact `time_ticks`. |
| `weather_rain` / `weather_clear` | Rain begins / ends. | `rain` / `clear`. |
| `player_joined` / `player_left` | Tab-list name appears / disappears. | Player name. |
| `biome_changed` | Player block's biome changes. | Biome registry ID. |
| `chunk_changed` | Player crosses a chunk boundary. | `chunkX chunkZ`. |
| `chat` / `actionbar` | Chat/game message / overlay message is received. | Full rendered plain text. |
| `title` / `subtitle` | HUD title / subtitle is set. | Full rendered plain text. |
| `sound` | Client sound engine plays a sound. | Sound registry ID. |
| `attack_block` / `use_block` | Local client attacks / uses a block. | Block position as `x, y, z`. |
| `attack_entity` / `use_entity` | Local client attacks / uses an entity. | Entity label; getter also carries entity metadata. |
| `use_item` | Local client uses an item. | Item registry ID. |
| `held_enchanted` / `held_has_name` | Held item becomes enchanted / gains a custom name. | Item and player position / custom name. |
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
| `item_picked_up` | Vanilla pickup packet identifies a collector. | `item_id xamount @ x.xxx y.yyy z.zzz by entity label`; the packet amount stays authoritative even when inventory stacks combine, and getter metadata identifies the collector. |
| `projectile_launched` | Any tracked projectile first appears. | `projectile label [by owner label]`; selector attribution is the owner when known. |
| `pearl_thrown`, `snowball_thrown`, `egg_thrown` | Named projectile first appears. | Same launch payload. |
| `projectile_hit` | Projectile vanishes in a still-loaded chunk. | `projectile label @ x.xxx y.yyy z.zzz [by owner label]`. |
| `pearl_landed` / `potion_splashed` / `snowball_hit` / `egg_hit` | Corresponding projectile vanishes in a loaded chunk. | `x.xxx y.yyy z.zzz [by owner label]`. |
| `projectile_stopped` | Tracked projectile speed falls from above `0.2` to below `0.05`. | `projectile label @ x.xxx y.yyy z.zzz`. |
| `potion_drank` | Tracked entity completes use while its previous hand item contains `potion`. | `entity label: potion_item_id`. |
| `totem_popped` | Entity status byte `35` is received. | Entity label. |
| `entity_status` | Any entity status byte is received. | `entity label: signed_status_byte`. |
| `teleported` | Same-dimension position jump exceeds 12 blocks in one client tick. | Rounded jump distance. |
| `particle_seen` | Particle packet is observed. | `particle_id @ x.xxx y.yyy z.zzz`. |
| `item_frame_changed` | Non-empty tracked item-frame content changes. | `item_frame label: item_id`. |

### Container, boss-bar, scoreboard, and packet triggers

| Trigger name | Fires when | Raw `{value}` |
|---|---|---|
| `container_title` | External container opens. | Current screen title. |
| `container_item_gained` / `container_item_lost` | Aggregate open-container count increases / decreases. | `item_id xamount` delta. |
| `packet_received` | Any S2C packet reaches the client. | Packet type ID. |
| `explosion` | Explosion packet is decoded. | Exact center `x.xxx y.yyy z.zzz`. |
| `bossbar_shown` | Boss bar is added. | Boss-bar display name. |
| `bossbar_updated` | Boss-bar progress or name changes. | Rounded percentage such as `75%`, or the new name. |
| `bossbar_removed` | Boss bar is removed. | `removed`; `bossbar_percent` becomes `-1`. |
| `sidebar_appeared` / `sidebar_title_changed` | Sidebar appears / title changes. | Sidebar title. |
| `sidebar_removed` | Sidebar disappears. | `removed: previous title`. |
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
| `entity_location` (alias `mob_location`) | `get(name, runtime_id)` → `entity label @ x.xxx y.yyy z.zzz`, or `-1` if the entity is not currently loaded. |

---

## Python scripting API (`import talos`)

`/talos script run <name>` runs `.minecraft/talos/scripts/<name>.py` through an embedded GraalPy
runtime with a curated `talos` module already in scope. Python runs on a dedicated worker thread; the
game tick thread never enters Python. Blocking calls block only the script, so render FPS is
unaffected. `pip`, native packages, host classes, filesystem and environment access are
unavailable — the API is a hardened capability surface, not full CPython.

> **No `pip install talos`.** `talos` is provided by the mod's embedded GraalPy runtime — it is not a
> PyPI package and cannot be pip-installed. You install nothing: drop a `.py` in
> `.minecraft/talos/scripts/`, `import talos`, and run it with `/talos script run <name>`.

### Script metadata & libraries

| Symbol | Description |
|---|---|
| `talos.args` | `list[str]` of the args passed to `/talos script run <name> args…`. Always fresh per run. |
| `talos.require("mylib")` | Import another script in `talos/scripts/` as a module (CPython import semantics: caching, cycle handling; each library gets its own first-run trust summary). |
| `talos.log(msg, level="info")` | Leveled log line — written to the session log file, the mod logger, and the script console. `level` is `"debug"`/`"info"`/`"warn"`/`"error"`; `talos.log("x")` behaves exactly as before. |

### Python standard library

The pure-Python standard library works out of the box — no setup, no flags:

```python
import random
import talos
talos.log(random.randint(1, 10))
```

Works: `random`, `math`, `json`, `collections`, `heapq`, `itertools`, `re`, `dataclasses`,
`functools`, `enum`, `time`, and every other pure-Python stdlib module (the GraalPy runtime bundles
the full stdlib; it is covered by a unit test that imports them under the production sandbox flags).
**Not available** (blocked by the sandbox, by design): pip packages, native extensions (`numpy`,
`PIL`, …), host file/socket IO (`open`, `socket`, `urllib`), `threading`, `subprocess`,
`os.environ`. Try it: `/talos example stdlib` writes a runnable demo, or from the terminal:
`talos run 'import random;import talos;talos.log(random.randint(1,10))'`.
| `talos.debug(msg)` / `talos.info(msg)` / `talos.warn(msg)` / `talos.error(msg)` | Shorthands for `talos.log(msg, level=…)`. `debug` lines only reach the console/chat while `/talos debug` is on (they're also skipped in the file when off). |
| `talos.debug_mode(enabled=None)` | Query (no arg) or toggle the same master switch as `/talos debug`. |
| `talos.state` | A persistent dict (`state["key"] = …`) saved per-script across runs. |

#### Libraries — reusable modules with `talos.require`

Any `.py` file in `.minecraft/talos/scripts/` can be used as a library by other scripts — there is
no pip and no `import` of anything except `talos`, so `talos.require` **is** the module system:

```python
# talos/scripts/mininglib.py — a library is just a script that defines things
import talos

def vein(block_id, radius=32):
    """Mine every reachable block of this type nearby; returns count mined."""
    mined = 0
    while (pos := talos.find_block(block_id, radius)) is not None:
        talos.goto_near(pos.x, pos.y, pos.z, 4)
        talos.break_block(pos)
        mined += 1
        talos.wait_between(0.2, 0.6)
    return mined
```

```python
# talos/scripts/diamonds.py — the consumer
import talos
lib = talos.require("mininglib")          # ".py" optional
talos.log(f"mined {lib.vein('diamond_ore')} diamonds")
```

Rules:

- `require("name")` loads `talos/scripts/name.py` **only** — no paths, no traversal, no packages.
- CPython import semantics: cached after the first load (repeat `require`s return the same module
  object; cycles get the partially-initialized module), and the cache resets on every script
  (re)run, so editing a library takes effect on the next run — no restart.
- Libraries run in the same sandbox and get the same first-run trust summary as scripts.
- Module-level code in a library executes once at `require` time — keep libraries to `def`s and
  constants; put behaviour in functions the consumer calls.
- Test a library standalone from a terminal:
  `talos py -c 'lib = talos.require("mininglib"); talos.log(lib.vein("stone", 8))'`.

### Movement & pathing

`goto(x, y=None, z=None)` · `goto_near(x, y, z, range)` · `goto_xz(x, z)` ·
`goto_block(block_id, radius=64)` · `follow(target, distance=3.0)` · `move_ahead(distance)` (walk
forward on your horizontal heading) · `set_node_count(n)`. All accept coordinate numbers, `~`/`^`
token strings, a single `"~ ~1 ~"` string, or a `Pos`/`Entity` snapshot.

Named-process control does not stop the Python session: `killprocess(name)` (alias
`kill_process`) requests cancellation, `process_time(name)` returns elapsed seconds or `-1` when
not running, and `time_exceeds(name, seconds)` is a non-blocking watchdog predicate. Recognized
path names are `goto`, `goto_block`, `follow`, `path`, and `pathing`; other names are matched
against Talos task names. Use the awaitable action so the watchdog can continue ticking:

```python
import talos

@talos.task
async def travel():
    await talos.aio.goto("~ ~10 ~")

@talos.on_tick
def stop_stalled_travel():
    if talos.time_exceeds("goto", 10):
        talos.killprocess("goto")
```

A plain synchronous `talos.goto(...)` intentionally pauses every Python task until it returns, so
it cannot be watched from another task in the same session; use `talos.aio.goto(...)` as above.

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

### Chat & commands

| Symbol | Description |
|---|---|
| `talos.chat(msg)` | Send a chat message to the server. A leading `/` runs it as a command instead. Returns the text sent. |
| `talos.run_command(cmd)` | Run a command, leading `/` optional. `/talos …` client commands dispatch locally (so scripts can drive Talos itself); anything unhandled is sent to the server as a normal `/command`. |

```python
talos.chat("selling dirt, 1 diamond per stack")
talos.chat("/home base")                    # same as run_command("home base")
talos.run_command("talos human on")         # drive Talos features from Python
```

Three gotchas, all by design:

- **Your own messages echo back** into the `chat` event — a `chat()` call inside a `chat` handler
  loops forever unless you guard against your own sender name.
- While a script is blocked in `talos.input()`, a plain `chat()` message is **consumed as that
  input answer** (it stays local and never reaches the server).
- Automated chat/commands are indistinguishable from typed ones to the server. Rate-limit yourself
  (`talos.wait_between(...)`) — servers kick or mute chat spam, and `talos.run_command` is *not*
  routed through the humanizer.

Not to be confused with `@talos.command("name")`, which **registers** a new `/talos name`
subcommand handled by your script.

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
talos.get("server tps")                         # same name; spaces normalize to underscores (*MOSTLY only for talos.get())
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

### Drawing overlays

Draw in the world the way Talos' own highlights do (wireframes rendered in-client, visible only to
you):

- `draw_box(a, b=None, color="green", seconds=10, id=None)` — outline a box; `draw_box(pos)` alone
  outlines that single block cell. Positions are `Pos` objects or `(x, y, z)` tuples.
- `draw_line(a, b, color="green", seconds=10, id=None)` — a world-space line segment (paths, links,
  debug rays).
- `draw_clear(id=None)` — remove one overlay, or all of this script's overlays.

`color` is `"#RRGGBB"`, an int, or a name (`green red yellow blue white orange purple aqua pink
black`). Re-drawing with the same `id` replaces the shape in place — that's how you animate (e.g.
a line from your feet to a moving target every few ticks). Limits: max 512 live overlays per
script, lifetime capped at 1 hour, and everything is cleared automatically when the script stops.

```python
ore = talos.find_block("diamond_ore", radius=64)
talos.draw_box(ore, color="aqua", seconds=30)
talos.draw_line(talos.player_feet(), ore, color="yellow", seconds=30, id="path")
```

### Humanization & timing

`wait(a, b=None)` / `wait_between(a, b)` (right-skewed random pause) · `set_profile(name)` (aim/timing
profile: raw/natural/paranoid categories) · `set_seed(seed)` (reproducible runs) ·
`human(enabled=None)` (toggle/query eased aim + session-arc fatigue) · `fatigue()` (0–1) · `on_break()` ·
`intensity(value=None)` (global more/less-humanisation dial) · `tune(**knobs, families=[...])`
(override individual knobs) · `human_knobs()` (inspect tuning + effective values) · `reset_tuning()` ·
`sleep(seconds)` · `ticks(n)` · `next_tick()` · `tick_count()`.

#### Tuning humanisation — more/less, per-knob

The three profiles (raw/natural/paranoid) are starting points, not the ceiling. Two layers of user
tuning sit on top, from Python (`talos.intensity`, `talos.tune`) or chat (`/talos human intensity`,
`/talos human set`), both persisted in the mod config across sessions:

- **Intensity** — one dial for "more or less humanisation". `talos.intensity(1.5)` scales the
  humanness knobs together: reaction delays, overshoot probability/magnitude, timing jitter and path
  wobble scale up with intensity, while rotation speed scales down. `0` is near-robotic, `1` is the
  profile as authored, `3` is the exaggerated maximum.
- **Per-knob overrides** — `talos.tune(overshoot_prob=0.3, rotation_speed_max=12)`. Every knob is
  clamped into a safe range, so bad values can tune aim but never break it:

| Knob | Meaning | Safe range |
|---|---|---|
| `reaction_median_ms` | median reaction delay before an action | 1–5000 |
| `reaction_sigma` | log-normal spread of reaction delays | 0–2 |
| `rotation_speed_min` / `rotation_speed_max` | aim speed range, degrees per tick | 0.5–360 |
| `max_accel` | max angular acceleration, deg/tick² | 0.5–360 |
| `overshoot_prob` | chance an aim overshoots then corrects | 0–1 |
| `overshoot_min` / `overshoot_max` | overshoot magnitude range, degrees | 0–30 |
| `jitter_phi` | AR(1) correlation of timing jitter | 0–0.95 |
| `path_deviation` | lateral walk/aim wobble stdev | 0–2 |
| `visibility_check` | 1 = only aim at visible targets | 0/1 |

- **Trajectory families** — restrict the aim-path shapes: `talos.tune(families=["bezier",
  "min_jerk"])` (options: `bezier`, `min_jerk`, `linear`).
- Inspect everything with `talos.human_knobs()` (returns `profile`, `intensity`, `overrides`,
  `families`, and `effective` — the final numbers actually used) or `/talos human show`; clear with
  `talos.reset_tuning()` or `/talos human reset`.

> Design note: knobs and intensity are the supported way to change *how* humanisation behaves.
> Python callbacks cannot supply aim curves directly — aim plans are computed on the game thread,
> and the game thread never enters Python (a core stability invariant).

#### Human mode (session-arc)

`/talos human [on|off]` (or `talos.human(True/False)`) is the single Human-mode toggle. **On** bundles
the eased, non-instant cube-aim path with session-arc humanization; **off** uses direct snap aiming and
disables the session drift. On top of the stationary raw/natural/paranoid profile, a wall-clock
**fatigue** model drifts your behaviour over the session — reactions slow and spread, aim loosens and
overshoots more, the walk wobbles wider — and injects **idle micro-breaks** that pause pathing briefly
(more often, and longer, as fatigue rises).
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
- `@talos.command("name", suggest=None)` — register a `/talos <name>` (or `/talos cmd <name>`)
  handler in Python; the handler receives the arguments as a `list[str]`, and an `async def` handler
  runs as a task. Overrides built-ins like `goto`/`mine`/`kill`/`follow` when the name matches (the
  built-in checks `scriptOverride` first). `suggest` adds chat tab-completion for the arguments: a
  list of strings suggests those tokens for the first argument, a list of lists suggests
  per-position — `@talos.command("farm", suggest=[["wheat", "carrot"], ["16", "64"]])`. Tokens must
  not contain whitespace; suggestions are captured at registration and served host-side (the game
  never calls into Python to compute them).
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

## Simulations & custom pathfinding — build your own

Talos is deliberately open at both ends: you can replace the *pathfinding engine* or run *any
tick-driven simulation of your own* (animal AI, farming brains, market bots, experiments — anything)
with safety limits that make it impossible to crash or stall the game from a script.

### `talos.sim` — the simulation framework

```python
import talos
from talos import sim

sheep = sim.Simulation("sheep", hz=4, budget_ms=5)

@sheep.tick
def step(dt):                    # dt = seconds since the previous step
    ...                          # one simulation step

sheep.start()
talos.run()
```

- `sim.Simulation(name, hz=20, budget_ms=5)` — a named loop stepping up to `hz` times per second
  (20 = every tick, the fastest allowed). `@sim.tick` registers the step function (takes `dt` or
  nothing); `@sim.on_start` / `@sim.on_stop` are lifecycle hooks.
- `sim.state` — a dict for your simulation's own data (positions, mode machines, counters).
- `sim.rng` — a per-simulation seeded RNG (seeded from the sim's name; re-seed with `sim.seed(n)`),
  so "random" behaviour is reproducible: the same seed replays the identical run. Randomness is an
  *input* to the model, not a replacement for it — an animal sim is a state machine (graze → wander
  → graze) where the RNG only picks parameters like durations and targets, exactly how Minecraft's
  own mob AI works.
- `sim.start() / stop() / pause() / resume()`, `sim.running`, `sim.paused`; module-level
  `sim.sims()` and `sim.stop_all()`.

**The "can't crash the game" contract** — simulations run on the script worker thread (the game
thread never executes Python), and the framework enforces:

| Limit | Value | What happens |
|---|---|---|
| Max simulations | 16 per script session | creating more raises `SimulationError` |
| Step rate | ≤ 20 Hz (1 step/tick) | faster rates are rejected |
| Step budget | `budget_ms` (default 5 ms) | 5 consecutive over-budget steps auto-throttle the sim to half rate, with a warning |
| Circuit breaker | 5 consecutive exceptions | the sim auto-pauses with an error log; `sim.resume()` after fixing |
| Action spam | bounded 256-slot queues | worker→game actions fail fast instead of piling up |
| Hard stop | `/talos stop`, script stop | every sim ends immediately |

What a client-side sim can and can't do: it **can** drive the player like an animal (see
`/talos example sim`), keep purely virtual creatures in `sim.state` and visualise them via
HUD/glow, and read + react to real mobs (`talos.entities()`, `talos.get(...)`). It **cannot**
puppet server-controlled mobs — no client mod can.

### Custom pathfinding, two tiers

1. **Pure Python (no Java needed)** — build movement from raw primitives: `player_feet()`,
   `look_angle()`, `look(yaw, pitch)`, `key("forward")`, `raytrace()`, `block_at()`. The shipped
   reference `/talos example pygoto` is a complete from-scratch goto (eased steering + stall-jump
   watchdog) plus a `@talos.command("goto")` override that intercepts `/talos goto` while the script
   runs — the built-in stays reachable as `talos.goto` / `talos.aio.goto`, so you can pre-process
   (speedbridge, scaffold, log) and delegate.
2. **A Java engine replacement** — Talos discovers pathfinding engines through the Fabric
   entrypoint `talos:pathing_engine`. Any mod jar can ship one: implement
   `dev.talos.client.pathing.PathingEngine` (`isAvailable`, `goTo(Goal, PathingOptions)`, `cancel`,
   `isPathing`) and a `PathingEngineProvider` (`create()`, `priority()`), declare the entrypoint in
   your `fabric.mod.json`, and the registry picks the highest-priority available engine at startup —
   this is exactly how the optional Baritone adapter (`talos-pathing-baritone`) plugs in, and
   scripts notice nothing: `talos.goto()` just uses the winning engine. With no engine available a
   `NoOpPathingEngine` fails calls with a typed error instead of crashing.

Shipped examples (also in the repo's `examples/` folder): `/talos example sim` (wandering sheep,
Simulation API + a `suggest=`-completed control command), `/talos example pygoto` (custom goto +
override), `/talos example stdlib` (`import random` & friends).

---

## Detailed logging

One master switch controls how loud Talos is: `/talos debug on|off|status` (or
`talos.debug_mode(True)` from a script). Everything shares a single rotating sink at
`~/.talos/logs/session-<timestamp>.log` — a new file per game launch, newest 10 kept.

**Always on** (regardless of the switch):

- `talos.log(...)` / `talos.info/warn/error(...)` lines go to the session log file, the standard
  mod log, and the script console (chat, or the VS Code output channel when run from the bridge).
- `warn` is yellow and `error` is red in chat.

**Only while `/talos debug` is on:**

- `talos.debug(...)` lines surface (dark-gray in chat) and are written to the file.
- **Engine trace** streams to chat + file: pathing plan starts, search attempts, replans, stalls
  and outcomes; follow segment starts/retargets/route swaps; event-rule fires; break/place/kill
  state transitions (`prepare → acquire → execute → verify`); and script session start/stop.

That makes `/talos debug on` the first thing to reach for when a goto stalls, a rule doesn't fire,
or a script misbehaves — you can watch exactly what the engine is deciding, live, and the file
keeps the full transcript for later.

```python
import talos

talos.debug_mode(True)                 # same switch as /talos debug on
talos.debug(f"starting at {talos.player_feet()}")   # visible only in debug mode
talos.warn("low durability")           # always visible, yellow in chat
```

---

## VS Code extension

`vscode-extension/` pushes and runs Python scripts against the mod over a local WebSocket, with live
log streaming into a "Talos" output channel and Pylance autocomplete via the bundled `talos.pyi`
stubs.

**Features:** *Run Script in Minecraft* (Cmd/Ctrl+Alt+Enter), *Stop Script*, *Reconnect*, run-on-save
live reload, status-bar connection indicator, clickable Python tracebacks (jump to
`your/script.py:line`, including `require`'d libs), and auto-installs the [`talos` terminal
CLI](#terminal-control--the-talos-cli) to `~/.talos/bin` on activation.

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

## Terminal control — the `talos` CLI

`cli/talos` is a dependency-free Python 3 command that drives the running game from any terminal
over the same loopback WebSocket bridge the VS Code extension uses. Script output (`print`,
`talos.log`, tracebacks) streams live back into the terminal.

```bash
talos harvest.py wheat 64        # push a local file into the game and run it
talos run harvest.py wheat 64    # same, explicit
talos py -c 'talos.log("hi")'    # one-liner; a trailing expression echoes its repr
talos -c 'talos.player_feet()'   # same (py/python/python3 are accepted aliases)
talos run 'import talos;talos.log("hi")'   # inline code: anything that isn't a filename
talos python3 -c 'talos.chat("hello from the shell")'
talos stop                       # hard-stop the running script
talos status                     # bridge reachability + run state
talos logs -f                    # follow ~/.talos/logs/session-<newest>.log
```

### Install

- **Automatic (recommended):** the VS Code extension bundles the CLI and installs it to
  `~/.talos/bin/talos` every time it activates (command palette: *Talos: Install Terminal CLI* to
  re-run it loudly). Add it to your PATH once:
  `export PATH="$HOME/.talos/bin:$PATH"` in `~/.zshrc`/`~/.bashrc`
  (Windows: the extension also writes `talos.cmd`; add `%USERPROFILE%\.talos\bin` to PATH).
- **Manual (no VS Code):** copy [`cli/talos`](cli/talos) from this repo anywhere on your PATH and
  `chmod +x` it. Python 3.8+ is the only requirement — no pip packages.

### How arguments must be written

- Everything **after the script filename** is an argument: `talos farm.py wheat 64 --fast` →
  `talos.args == ["wheat", "64", "--fast"]`. Nothing after the filename is interpreted by the CLI —
  `--fast` there belongs to your script, not to `talos`.
- CLI options (`--port N`, `--token FILE`, `--no-color`) must come **before** the script filename.
- Args always arrive as **strings** — convert yourself: `count = int(talos.args[1])`.
- An argument containing spaces needs shell quoting: `talos greet.py "hello world"` arrives as one
  arg. (In-game `/talos script run greet hello world` is whitespace-split only — spaces inside an
  arg are impossible there; the CLI is the way to pass them.)
- Script filenames may only use letters, digits, `_`, `.`, `-`, and must end in `.py` — the file is
  pushed under its basename into `.minecraft/talos/scripts/`, overwriting any script of that name.

### Behaviour & exit codes

- The first terminal run needs a one-time `/talos bridge allow` in-game (persisted afterwards); the
  CLI prints the prompt and waits, then runs automatically once you allow it.
- `Ctrl-C` sends a hard-stop to the game before exiting — a runaway loop dies with the CLI.
- Exit codes: `0` script succeeded · `1` script raised/failed · `2` usage error · `3` bridge
  unreachable or auth failed. That makes shell scripting and CI-style checks possible:
  `talos selftest.py && echo PASS`.
- `talos logs [-f]` reads the newest `~/.talos/logs/session-*.log` directly (works even with the
  game closed); everything a run prints also lands there, so the terminal, chat, VS Code, and the
  log file all see the same stream.

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
| `~/.talos/token` | Per-session bridge auth token (VS Code + `talos` CLI; regenerated every launch). |
| `~/.talos/bin/` | The `talos` terminal CLI (auto-installed by the VS Code extension). |
| `~/.talos/logs/` | Per-session detailed log files (`session-<timestamp>.log`, newest 10 kept). |
| `.minecraft/talos/scripts/` | Python scripts run by `/talos script run` (and `require`'d libs). |

---

## Limitations

- **Client-side protocol boundaries, not bugs:** villager *inventories* are never synced to the
  client (equipment/profession/level are, and are covered by triggers); chest contents are only
  knowable while the chest screen is open; beacon effects only via the beacon screen. Honest ceilings
  on what a client mod can observe.
- **Not a guaranteed anti-detection system.** Humanization varies trajectory *families* — best-effort
  obfuscation, not a guarantee against a determined observer or anti-cheat.
- **Build-verified, not all battle-tested in-game.** The codebase compiles and behavior matches the
  source, but several recent waves (background planning, momentum parkour, follow/selectors, raytrace,
  the chat/entity_hurt events, `require`/`args`, clickable tracebacks) haven't all been exercised
  against a live server yet. Expect rough edges in newly landed features before well-worn ones like
  pathing/mining.

---

## Architecture — and how to test each part

Talos is a set of engines behind thin entry points. Every box below names its package under
`talos-mod/src/main/java/dev/talos/` and the seam you can hook to test it **without touching the
others**.

```
 you                          the mod (client only)                        Minecraft
 ───                          ─────────────────────                        ─────────
 /talos …  ──────────► client/command/TalosCommands ──┐
 VS Code / talos CLI ─► client/bridge/ (WebSocket) ───┤
                          BridgeProtocol JSON v1      ├─► client/script/ScriptEngine
                                                      │     └ ≤8 Sessions: 1 worker thread
 .py files ───────────────────────────────────────────┘       + 1 GraalPy Context each
                                                              │
                              Python `import talos` (resources/talos_pyapi/)
                                                              │
                          client/script/TalosNativeBridge  (default-deny exports)
                                                              │  every call marshals via
                          client/script/GameThreadExecutor ◄──┘  submit() → client tick
                                                              │
        ┌──────────────┬───────────────┬──────────────┬───────┴──────┬─────────────┐
  client/pathing   client/action   client/rules   client/humanize  client/scan  client/hud
  (sim planner,    (break/place/   (206 triggers, (profiles, aim   (block       (overlay)
   follower)        kill state      /talos on)     arcs, timing)    search)
                    machines)
        └──────────────┴───────────────┴── client/log/TalosLog ── ~/.talos/logs/session-*.log
```

**Key invariants** (these are what your tests should assert):

- The client tick thread **never enters Python**; scripts run on session worker threads and reach
  the game only through `GameThreadExecutor.submit(...)` (bounded queue, drained each tick).
- Every script run/eval takes an injectable **`LogSink`** (`ScriptEngine.LogSink` —
  `void log(String level, String text)`), so all output is capturable: chat is just the default
  sink, the bridge substitutes a WebSocket sink, the CLI sees the same stream in a terminal.
- `/talos script stop` (or bridge `stop`, or CLI Ctrl-C) must unblock **any** stuck call — sessions
  invalidate their native bridge, cancel in-flight game-thread futures, and hard-close the GraalPy
  context.
- The bridge speaks versioned JSON (`vscode-extension/PROTOCOL.md`); loopback-only, token-gated,
  nothing before `auth_ok`.

### Testing seams — how to check that a part works

| Layer | Seam | How to test it |
|---|---|---|
| Wire protocol | Plain JSON over a WebSocket | Run a mock server/client — no Minecraft needed. The CLI was validated exactly this way: a ~80-line stdlib mock bridge asserting `push_script`/`run`/`eval` shapes and replaying `log`/`script_done`. |
| Script engine + API | `talos` CLI exit codes | `talos selftest.py && echo PASS` — `0` success, `1` script raised, `3` bridge down. Scriptable from CI or a shell loop. |
| Python API surface | An in-game self-test script | See below — a check-runner that exercises each subsystem and fails the run (exit `1`) if any check fails. |
| Log pipeline | `~/.talos/logs/session-*.log` | Every level-tagged line lands in the file; `talos logs` reads it with the game closed. Assert on file contents. |
| Engine internals | `/talos debug on` trace | Pathing/movement/rules/actions/script categories narrate decisions to chat + file — grep the session log for `[pathing]` etc. |
| Humanizer | `talos.set_seed(n)` | Seeded runs are deterministic — replay a seed and compare traces. |
| Rules engine | `/talos get <trigger>` | Every rule trigger is also a getter — read the value a rule would see, instantly, without firing it. |

### Bug-hunting workflow — when something misbehaves

1. **Turn the narration on:** `/talos debug on` (or `talos.debug_mode(True)`, or from a shell
   `talos py -c 'talos.debug_mode(True)'`). The engine now explains its decisions live —
   pathing plans/replans/stalls, rule fires with resolved values, action state transitions,
   script lifecycle — to chat *and* the session log.
2. **Watch from a terminal while you play:** `talos logs -f` follows
   `~/.talos/logs/session-<newest>.log`. Every line is `HH:mm:ss.SSS [LEVEL] [category] message`,
   so `talos logs | grep '\[pathing\]'` isolates one subsystem after the fact.
3. **Probe state interactively:** `talos py -c '<expr>'` echoes the repr of any getter without
   writing a script — e.g. `talos py -c 'talos.get("server_tps")'`,
   `talos py -c 'talos.raytrace(16)'`. In-game, `/talos get <name>` reads the exact value a rule
   trigger would see.
4. **Instrument your script:** sprinkle `talos.debug(...)` freely — the lines are invisible (chat
   *and* file) until debug mode is on, so they can stay in production scripts.
5. **Reduce, then bisect:** shrink the repro into a snippet you can rerun cheaply from the shell
   (`talos repro.py`; exit code `1` = still broken) — the run-on-save loop in VS Code or a
   `while ! talos repro.py; do ...` shell loop makes iteration fast.
6. **Check dispatch cost:** `/talos script profile` toggles per-event dispatch profiling when a
   handler feels slow.
7. **When reporting a bug**, attach the session log file — it contains the full timestamped
   transcript of both your script's output and the engine trace.

### A self-test script you can extend

Drop this in `.minecraft/talos/scripts/selftest.py` (or run `talos selftest.py` from a terminal —
non-zero exit means a check failed). Add a `@check(...)` per feature you care about:

```python
import talos

CHECKS = []
def check(name):
    def wrap(fn):
        CHECKS.append((name, fn))
        return fn
    return wrap

@check("player position readable")
def _(): assert talos.player_feet() is not None

@check("world block lookup")
def _():
    feet = talos.player_feet()
    assert ":" in talos.block_at(feet.x, feet.y - 1, feet.z)

@check("observable catalog")
def _(): assert talos.get("health") > 0

@check("raytrace does not raise")
def _(): talos.raytrace(8.0)          # None (no hit) is fine

@check("inventory snapshot")
def _(): assert isinstance(list(talos.inventory()), list)

@check("logging pipeline")
def _(): assert talos.log("selftest ping") == "selftest ping"

failed = 0
for name, fn in CHECKS:
    try:
        fn()
        talos.info("PASS " + name)
    except Exception as error:
        failed += 1
        talos.error("FAIL " + name + ": " + repr(error))
talos.log(f"{len(CHECKS) - failed}/{len(CHECKS)} checks passed")
if failed:
    raise RuntimeError(f"{failed} check(s) failed")   # → exit code 1 in the CLI
```

---

## Roadmap

- **Modrinth listing** — one-click install distribution (docs site already carries the button).
- **Deeper 26.x parity** — promote the 26.1/26.2 ports from experimental to stable with playtime.
- **More showcase scripts** — schematic-style building, farm loops, recording-to-script polish.
- **Pathfinding engines** — the `talos:pathing_engine` entrypoint is open; a Baritone adapter
  exists, more engines welcome.
- **Script sharing** — exploring a curated place to publish and discover Talos scripts.

Suggestions and PRs welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

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
