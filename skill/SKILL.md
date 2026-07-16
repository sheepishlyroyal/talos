---
name: talos
description: Use when writing or debugging Talos automation for Minecraft — Python scripts (`import talos`), `/talos` client commands, event rules (`/talos on`), pathfinding, humanized aim, raytrace/local coordinates, and the getter/trigger catalog. Talos is a client-side Fabric mod for MC 1.21.11 / 26.1 / 26.2 with an embedded GraalPy runtime.
---

# Talos automation

Talos is a **client-side** Minecraft Fabric automation mod (MC **1.21.11 / 26.1 / 26.2**). It exposes a
physics-simulated pathfinder, a ~206-family event-rule engine, humanized aim/input, and an embedded
**GraalPy** Python runtime — all as **client commands** (`/talos …`), so no server permission level and
no server-side mod are needed. Write automations two ways: **`/talos` commands / event rules**, or
**Python scripts** (`import talos`). This skill is the authoring contract for both.

> Automation may violate a server's rules. Humanization is best-effort obfuscation, **not** a guarantee
> against anti-cheat. Never claim undetectability. Talos models only motor-level imperfection, never
> semantic mistakes.

## Golden rules (read first)

1. **`import talos`** is always in scope inside a script. `pip`, native packages, host classes, the
   filesystem, `os`/`env`, and sockets are **unavailable** — it's a hardened capability surface, not full
   CPython. Don't `import requests`, open files, or spawn processes.
2. **Python runs on a worker thread**, never the game tick thread. Blocking calls (`talos.goto(...)`,
   `talos.wait_between(...)`) block only your script — render FPS is fine. A plain `talos.goto(...)`
   **pauses every Python task in the session**; to run things concurrently use `await talos.aio.goto(...)`
   inside an `async def @talos.task`.
3. **Your own chat echoes back** into the `chat` event and `chat`/`mention` triggers. Always guard against
   self-triggering loops (check `sender`, or a sentinel).
4. **ids work bare or namespaced** everywhere (`stone` == `minecraft:stone`), in commands and Python.
5. **`talos.get(name, *args)` and `/talos on <name>` share one catalog** — all 206 trigger names are also
   getters. Spaces normalize to underscores in `talos.get()` only; use underscores in commands.
6. **End a script with `talos.run()`** if it registers tasks/events/`@every`/`@command` (implicit at module
   end, but be explicit). Without it, event/task handlers never fire.
7. Coordinates: `100 64 -30` absolute · `~ ~1 ~` player/eye-relative · `^ ^ 5` **local** frame
   (`^left ^up ^forward`, from the eyes — `^ ^ 5` = 5 blocks forward). In `/talos look|coords|mine
   direction`, `^` instead means a **relative angle** (yaw/pitch), not a local coordinate.

## Command cheat-sheet

```
# Pathing / movement (goto tunnels, bridges, pillars, parkours automatically — no "modes")
/talos goto xyz <x> <y> <z>          # ~-relative ok:  /talos goto xyz ~ ~ ~50
/talos goto xyz <x> <z>  |  /talos xz <x> <z>
/talos goto near <x> <y> <z> <range>
/talos goto block <id> [radius]      # nearest match; blacklists+retries if unreachable
/talos follow <target> [distance]    # any entity; live-tracks each tick.  @e[type=cow,distance=..20]
/talos find block <predicate> [radius]
/talos glow <x> <y> <z> [seconds]
/talos stop                          # stop pathing/follow/aim/tasks

# Raytrace & local coords (^ = vanilla local frame here)
/talos raytrace where [maxDist]                 # first block OR entity hit: point(3dp), id, distance
/talos raytrace [get] <x> <y> <z>               # resolve a coord triple to a world point + block id
/talos raytrace simple|advanced get <x> <y> <z> # simple = floored block cell (ints); advanced = 3dp point
/talos raytrace if block <id> [maxDist]         # 1/0
/talos raytrace if entity <selector> [maxDist]  # 1/0
# Friendly caret: once ANY axis is ^, plain numbers on other axes are local too. `^ ^ 5` == `^ ^ ^5`.
# Mixing ^ with ~ is an error.

# World actions (humanized, tool-aware, server-verified)
/talos mine <x> <y> <z>   |   /talos mine direction <yaw> <pitch>   |   /talos mine block <predicate> [index]
/talos place <x> <y> <z>
/talos kill nearest [radius]

# Aim (^ = relative ANGLE here)
/talos look <yaw> <pitch> | look block <id> [i] | look coords <x y z> | look direction <yaw> <pitch> | look <selector> [i]
/talos track [<selector>|block <id>] | /talos track stop

# Input automation
/talos walk <w|a|s|d> tap | hold <secs> | blocks <n> [center|touch]
/talos key <name> tap|hold <secs>   |   /talos key list
/talos macro record <name> [channels] | macro stop | macro play <name> [times] [channels] | macro list|delete
#   channels: move,jump,sneak,sprint,clicks,look,hotbar + keys,input,all  (e.g. clicks+look leaves movement free)

# Inventory
/talos inv list | move <from> <to> | hotbar <from> <1-9> | deposit all|<item> | withdraw all|<item> | armor <piece> <from>

# Observables — same evaluator as rules
/talos get <name> [args]     # /talos get health | server_tps | block_count diamond_ore 16 ~ ~-4 ~ | list

# Event rules (persist to ~/.talos/rules.json); `run chat <msg>` sends chat, else dispatches a command
/talos on <trigger> ... run <command>
/talos every <secs> run <command>     # persists
/talos after <secs> run <command>     # session only
/talos rules list | remove <id> | clear    |    /talos on list   # dump every trigger's grammar

# Scripting
/talos script run <name> [args…]   |   script stop | profile | editor
/talos py <code>                    # one-liner; trailing expr echoes repr
/talos example [name]               # list / write example_<name>.py
/talos human [on|off]               # session-arc fatigue + eased cube-aim
/talos bridge allow | status        # VS Code WebSocket bridge
```

### Event-rule grammar by trigger *kind* (`/talos on list` dumps it live)

| Kind | Grammar |
|---|---|
| `NONE` | `on <trigger> run <cmd>` |
| `NUMBER` | `on <trigger> <value> run <cmd>` |
| `TEXT` | `on <trigger> [matching "<text>"] run` · also `count above <n> within <secs> run` |
| `COMPARE` | `on <trigger> [at <x> <y> <z> [radius <r>]] above\|below\|equals <n> [for <secs>] run` · also `changes above\|below\|equals <delta> within <secs> run` |
| `ENTITY_COUNT` | `on <trigger> <selector> radius <r\|-1> [at <x y z>] above\|below\|equals <n> run` |
| `ENTITY_PRESENCE` | `on <trigger> <selector> radius <r\|-1> [at <x y z>] run` |
| `BLOCK_COUNT` | `on <trigger> <block> radius <r> [at <x y z>] above\|below\|equals <n> run` |
| `BLOCK_PRESENCE` | `on <trigger> <block> radius <r> [at <x y z>] run` |
| `ITEM_COUNT` | `on <trigger> <item> above\|below\|equals <n> run` |
| `REGION` | `on <trigger> <x1 y1 z1 x2 y2 z2> run` |

`{value}`, `{health}`, `{x}/{y}/{z}` etc. are substituted from the firing event. `at`/`radius` coords are
resolved when the rule is armed (a `~`/`^` rule does not drift afterward). Selectors: `@e @a @p @s @r @n`
with bracket filters; radius `-1` = whole loaded world.

```
/talos on health below 6 for 2 run chat low HP, retreating
/talos on entity_count @e[type=zombie] radius 16 above 3 run chat too many zombies
/talos on chat matching "diamond" count above 3 within 10 run chat spam detected
/talos on block_near minecraft:lava radius 5 run chat lava nearby
/talos on tick_every 100 run /talos get health
```

## Python API (`import talos`)

Sync *feel* on the worker thread. Runs `.minecraft/talos/scripts/<name>.py`.

**Movement & pathing** — `goto(x, y=None, z=None)` · `goto_near(x,y,z,range)` · `goto_xz(x,z)` ·
`goto_block(block_id, radius=64)` · `follow(target, distance=3.0)` · `move_ahead(distance)` (neg = back
off) · `set_node_count(n)`. All accept numbers, `~`/`^` token strings, one `"~ ~1 ~"` string, or a
`Pos`/`Entity`.

**Named-process control (non-blocking watchdog)** — `killprocess(name)` (alias `kill_process`) ·
`process_time(name)` → secs or `-1` · `time_exceeds(name, secs)` → bool. Names: `goto`, `goto_block`,
`follow`, `path`, `pathing`, or a task name.

**Raytrace & local coords** — `local(left,up,forward)` → `Pos` (from eyes) · `ahead(distance)` → `Pos` ·
`raytrace(max_distance=64.0)` → `Hit|None` · `raytrace_if(block=None, entity=None, max_distance=64.0)` →
bool.

**Finding** — `find_block(name, radius=64)` → `Pos|None` · `find_entity(type, radius=64.0)` → `Entity|None`
· `find_item(item, radius=64.0)` → `Entity|None` · `players(radius=128.0)` → `list[Player]` ·
`nearest_player(radius=128.0)` → `Player|None` · `entities(type=None, radius=64.0)` → `list[Entity]`.

**World actions** — `place_block(x=None,y=None,z=None,block_id=None)` (no coords = at crosshair) ·
`place_look()` · `break_block(x,y=None,z=None)` · `mine(...)` (alias) · `mine_looking_at()` ·
`left_click()` · `right_click()` · `kill_nearest(radius=6.0)`.

**Aim & look** — `look(yaw,pitch)` · `look_at(x,y=None,z=None)` · `look_angle()` → `(yaw,pitch)` ·
`looking_at()` → `Pos|None` · `angle_to(x,y=None,z=None)` → `(yaw,pitch)`.

**Position & sensing** — `player_pos()` (eye) · `player_feet()` · `block_at(x,y=None,z=None)` → id ·
`on_edge(margin=0.3)` → bool · `get(name, *args)` → shared catalog (int/float/bool/str).

**Input** — `key(name, pressed=True)` · `tap(name)` · `release_keys(*names)` · `select_slot(0-8)`.

**Inventory** — `inventory()` → `list[{slot,id,count}]` · `hotbar()` · `selected_slot()` · `count(id)` ·
`has(id)` · `find_slot(id)` · `container_items()` · `container_slot_count()` · `click_slot(slot,right=False)`
· `move_stack(from,to)` · `take_stack(container_slot,player_slot)` · `deposit(id,amount)` →moved ·
`withdraw(id,amount)` →moved · `craft(id,count=1)` · `armor_item(slot)` · `equip_armor(from,armor_slot)` ·
`screen()` · `close_screen()`.

**HUD** — `hud(text, id="hud")` pins a top-left line (same id updates in place; §colour ok; max 20 lines /
256 chars) · `hud_remove(id="hud")` · `hud_clear()`.

**Humanization & timing** — `wait(a, b=None)` / `wait_between(a, b)` (right-skewed pause) ·
`set_profile("raw"|"natural"|"paranoid")` · `set_seed(seed)` · `human(enabled=None)` (toggle/query eased
aim + session fatigue) · `fatigue()` → 0–1 · `on_break()` → bool · `sleep(secs)` · `ticks(n)` ·
`next_tick()` · `tick_count()`.

**Metadata & libs** — `talos.args` → `list[str]` from `/talos script run <name> args…` · `talos.require("lib")`
imports another `talos/scripts/` file as a module · `talos.log(msg)` (logger, not chat) · `talos.state`
(persistent per-script dict).

**Events** — `@talos.on("<event>")` registers on the worker thread:

| Event | Handler | Note |
|---|---|---|
| `tick` | `fn()` | every game tick |
| `chat` | `fn(message, sender)` | `sender` = player name or `None` (system). **Own messages echo — guard.** |
| `entity_hurt` | `fn(type_id, entity_id, x, y, z)` | |
| `health` | `fn(health)` | |
| `death` / `disconnect` | `fn()` | |
| `item_pickup` | `fn(item_id, amount)` | |
| `goto_start` | `fn(x, y, z)` | · `goto_done` `fn(success, detail)` · `goto_stuck` `fn(detail)` |

**Async / tasks / commands** — `@talos.task` or `talos.start(coro, name=None)` run an `async def`
concurrently · `talos.aio.*` = awaitable actions (`goto, goto_near, goto_xz, goto_block, follow, find_block,
place_block, break_block, mine, mine_looking_at, kill_nearest, wait, wait_between, input`) — use inside
`async def` so other tasks keep running · `@talos.on_start` / `@talos.on_tick` module hooks ·
`@talos.every(seconds=…, minutes=…, ticks=…)` · `@talos.command("name")` registers `/talos name` (async
handler runs as a task; overrides built-ins when the name matches) · `talos.run()` starts the loop ·
`talos.cancel_all()` · `talos.parallel(...)` · `talos.spawn(fn, …)` · `talos.input(prompt)` /
`await talos.aio.input(prompt)` blocks for the next chat message (captured locally, never sent).

**Types & errors** — `Pos(.x/.y/.z)` · `Entity(.uuid/.type/.pos/.distance)` · `Player(.name/…)` ·
`Hit(.type/.id/.pos/.distance)` (`.type` = `"block"`/`"entity"`). Failures raise `TalosError`,
`PathFailedError`, `OutOfReachError`, `NotFoundError`, `TargetLostError`, `ActionCancelledError`,
`WorldClosedError`.

### Canonical patterns

```python
# 1) Simple sequential: find → path → mine
import talos
goal = talos.find_block("diamond_ore", radius=64)
if goal:
    talos.goto(goal)
    talos.mine(goal)
    talos.hud(f"§bdiamonds: {talos.count('minecraft:diamond')}")
talos.run()
```

```python
# 2) Concurrent travel with a watchdog (aio + named-process control)
import talos

@talos.task
async def travel():
    await talos.aio.goto("~ ~10 ~")

@talos.on_tick
def stop_stalled():
    if talos.time_exceeds("goto", 10):
        talos.killprocess("goto")

talos.run()
```

```python
# 3) Reactive rule in Python — GUARD the chat echo
import talos

@talos.on("chat")
def greet(message, sender):
    if sender is None or sender == talos.get("players").split(",")[0]:
        return                      # skip system + (roughly) own lines
    if "help" in message.lower():
        talos.log(f"{sender} asked for help")

@talos.on("entity_hurt")
def flee(type_id, entity_id, x, y, z):
    if type_id == "minecraft:player":
        talos.move_ahead(-3)        # back off 3 blocks

talos.run()
```

```python
# 4) A /talos command written in Python, taking args
import talos

@talos.command("harvest")
async def harvest(*args):
    n = int(args[0]) if args else 8
    for _ in range(n):
        crop = talos.find_block("wheat", radius=32)
        if not crop: break
        await talos.aio.goto(crop); await talos.aio.mine(crop)
        talos.wait_between(0.4, 1.1)
talos.run()
# then in-game:  /talos harvest 16
```

## Human mode (`/talos human on` · `talos.human(True)`)

Bundles eased, non-instant **cube-aim** (a 1×1m yellow guide cube centred on the target; the red X lands on
a visible face weighted by visible area, center-biased; fast-then-slow rotation with jitter + a red preview
line) with a **session-arc fatigue** model: over wall-clock time reactions slow and spread, aim loosens and
overshoots more, the walk wobbles wider, and idle micro-breaks pause pathing (more/longer as fatigue rises).
HUD shows `human » fatigue N% (Mm)`. Off = direct snap aim, no drift. Best-effort long-session obfuscation
only.

## Gotchas checklist (verify before finishing a script)

- [ ] `import talos` only; no stdlib file/network/process/`pip`.
- [ ] `talos.run()` present if any `@task`/`@on`/`@every`/`@command`/`@on_tick` is used.
- [ ] Used `await talos.aio.<action>()` (not the blocking form) when other tasks must keep running.
- [ ] `chat`/`mention` handlers guard against the player's **own** echoed messages.
- [ ] Coordinate `^` means **local frame** in `raytrace`/scripts but a **relative angle** in
      `look`/`coords`/`mine direction` — don't mix them up.
- [ ] ids are valid Minecraft registry ids (bare or `minecraft:`); selectors use `@e/@a/@p/@s/@r/@n`.
- [ ] Long-running loops call `talos.wait_between(...)` / `next_tick()` so they yield and stay cancellable.
- [ ] Client-only ceilings respected: villager inventories, closed-chest contents, beacon effects are **not**
      observable from the client.

## Data locations

`~/.talos/rules.json` (rules/schedules) · `~/.talos/macros/` · `~/.talos/token` (VS Code bridge, per-launch)
· `.minecraft/talos/scripts/` (scripts + `require`d libs).
