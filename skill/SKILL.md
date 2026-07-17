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

1. **`import talos`** is always in scope inside a script. **There is no `pip install talos`** — `talos`
   is provided by the mod's embedded GraalPy runtime, not a PyPI package; you install nothing, just put a
   `.py` in `.minecraft/talos/scripts/` and `import talos`. `pip`, native packages, host classes, the
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
/talos human intensity <0..3>       # more/less humanisation (0 robotic, 1 default, 3 max); persisted
/talos human set <knob> <value>     # override one knob (tab-completes names; values clamped safe)
/talos human show | reset           # inspect / clear tuning
/talos debug [on|off|status]        # detailed logging: engine trace + talos.debug() lines → chat + ~/.talos/logs/
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

**Chat & commands** — `chat(msg)` sends a chat message to the server (leading `/` runs it as a command) ·
`run_command(cmd)` runs a command, `/` optional: `/talos …` client commands dispatch locally (scripts can
drive Talos itself), anything else goes to the server. Both return the text. ⚠️ own messages echo back
into the `chat` event (guard against loops); during `talos.input()` a plain `chat()` is consumed as that
input answer; not humanized — rate-limit with `wait_between`. Distinct from `@talos.command(name)`, which
*registers* a `/talos` subcommand.

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

**Drawing overlays** — `draw_box(a, b=None, color="green", seconds=10, id=None)` (one arg = that
block cell; a/b are Pos or (x,y,z)) · `draw_line(a, b, ...)` · `draw_clear(id=None)`. Client-only
wireframes like the built-in highlights. color = "#RRGGBB" | int | name (green/red/yellow/blue/
white/orange/purple/aqua/pink/black). Same id redraws in place (animate that way). Max 512 live
per script, ≤1h lifetime, auto-cleared on script stop.

**Humanization & timing** — `wait(a, b=None)` / `wait_between(a, b)` (right-skewed pause) ·
`set_profile("raw"|"natural"|"paranoid")` · `set_seed(seed)` · `human(enabled=None)` (toggle/query eased
aim + session fatigue) · `fatigue()` → 0–1 · `on_break()` → bool · `sleep(secs)` · `ticks(n)` ·
`next_tick()` · `tick_count()`.

**Humanisation tuning** — `intensity(v=None)` global dial (0 near-robotic · 1 profile default · 3 max;
scales delays/overshoot/jitter/wobble up, aim speed down) · `tune(**knobs, families=[...])` per-knob
overrides, always clamped safe: `reaction_median_ms` (1–5000), `reaction_sigma` (0–2),
`rotation_speed_min/max` (0.5–360 deg/t), `max_accel` (0.5–360), `overshoot_prob` (0–1),
`overshoot_min/max` (0–30°), `jitter_phi` (0–0.95), `path_deviation` (0–2), `visibility_check` (0/1);
`families` ⊆ `["bezier","min_jerk","linear"]` · `human_knobs()` → dict(profile, intensity, overrides,
families, effective) · `reset_tuning()`. All persisted in the mod config. Python aim-curve callbacks are
NOT supported (game thread never enters Python) — knobs/families/intensity are the supported surface.

**Metadata & libs** — `talos.args` → `list[str]` from `/talos script run <name> args…` (CLI: everything
after the filename; always strings) · `talos.require("lib")` imports another `talos/scripts/` file as a
module · `talos.state` (persistent per-script dict).

**Python stdlib** — pure-Python stdlib imports work out of the box: `random`, `math`, `json`,
`collections`, `heapq`, `itertools`, `re`, `dataclasses`, `time`, … (`import random;
talos.log(random.randint(1,10))` is fine). Blocked by the sandbox: pip, native extensions (numpy),
file/socket IO, `threading`, `subprocess`, `os.environ`. Demo: `/talos example stdlib`.

**Libraries (`talos.require`)** — with no pip, `require` IS the module system for your own code: a
library is just another `.py` in `talos/scripts/` defining functions (`lib = talos.require("mininglib");
lib.vein("diamond_ore")`). CPython semantics — cached per run, cycles return the partial module — and the
cache resets on every (re)run, so edits apply next run. Same sandbox + trust summary as scripts. Keep
libraries to `def`s/constants (module-level code executes at require time). Only files directly in
`talos/scripts/` — no paths/packages. Test one standalone:
`talos py -c 'lib = talos.require("mininglib"); talos.log(lib.vein("stone", 8))'`.

**Logging** — `talos.log(msg, level="info")` writes to the session log file (`~/.talos/logs/`), the mod
log, and the script console (chat / VS Code) · shorthands `talos.debug(msg)` / `info(msg)` / `warn(msg)` /
`error(msg)` (`warn` = yellow, `error` = red in chat) · `talos.debug_mode(enabled=None)` queries/toggles the
master debug switch — same as `/talos debug on|off`. `debug`-level lines only surface (console AND file)
while that switch is on; it also streams the engine's own trace (pathing plans/replans/stalls, rule fires,
action state transitions, script lifecycle) to chat + file, which makes it the first diagnostic step when a
goto stalls or a rule doesn't fire.

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
`@talos.every(seconds=…, minutes=…, ticks=…)` · `@talos.command("name", suggest=None)` registers
`/talos name` (handler gets `list[str]` args; async handler runs as a task; overrides built-ins when the
name matches; `suggest=` adds chat tab-completion — list of tokens for arg 1 or list-of-lists
per-position, e.g. `suggest=[["wheat","carrot"],["16","64"]]`; tokens must be whitespace-free, served
host-side) · `talos.run()` starts the loop ·
`talos.cancel_all()` · `talos.parallel(...)` · `talos.spawn(fn, …)` · `talos.input(prompt)` /
`await talos.aio.input(prompt)` blocks for the next chat message (captured locally, never sent).

**Types & errors** — `Pos(.x/.y/.z)` · `Entity(.uuid/.type/.pos/.distance)` · `Player(.name/…)` ·
`Hit(.type/.id/.pos/.distance)` (`.type` = `"block"`/`"entity"`). Failures raise `TalosError`,
`PathFailedError`, `OutOfReachError`, `NotFoundError`, `TargetLostError`, `ActionCancelledError`,
`WorldClosedError`.

## Terminal CLI (`talos` command)

Drives the running game from any shell over the same loopback bridge as VS Code; output streams back
live and also lands in `~/.talos/logs/`. **Install:** auto-installed to `~/.talos/bin/talos` by the VS
Code extension on activation (*Talos: Install Terminal CLI* re-runs it) — put `~/.talos/bin` on PATH
(`export PATH="$HOME/.talos/bin:$PATH"`); or manually copy `cli/talos` from the repo (Python 3.8+,
stdlib only, no pip).

```bash
talos harvest.py wheat 64          # push local file + run; args → talos.args (as strings)
talos py -c 'talos.log("hi")'      # one-liner (trailing expression echoes repr)
talos run 'import talos;talos.log("hi")'  # inline code: any arg that isn't a filename
talos stop | status | logs -f      # hard-stop · bridge state · follow the session log
```

Args: everything after the filename is a script arg (CLI options like `--port` go *before* it);
always strings (`int(talos.args[0])` yourself); quote spaces in the shell (in-game
`/talos script run` is whitespace-split and cannot pass spaces). Filenames `[A-Za-z0-9_.-]+\.py`,
pushed under their basename into `.minecraft/talos/scripts/`. First terminal run needs a one-time
`/talos bridge allow` in-game (the CLI waits). Exit codes: 0 ok · 1 script failed · 2 usage ·
3 unreachable/auth — so `talos selftest.py && echo PASS` works as a test harness; Ctrl-C hard-stops
the in-game script.

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
    if sender is None:              # system line (not a player) — skip
        return
    # If your handler ever SENDS chat, also skip your own echoed lines here
    # (compare `sender` to your username, or track a sentinel you sent).
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
async def harvest(args):                 # args = list[str] from `/talos harvest 16`
    n = int(args[0]) if args else 8
    for _ in range(n):
        crop = talos.find_block("wheat", radius=32)
        if not crop: break
        await talos.aio.goto(crop); await talos.aio.mine(crop)
        await talos.aio.wait_between(0.4, 1.1)   # aio form yields to other tasks
talos.run()
# then in-game:  /talos harvest 16
```

## Simulations & custom pathfinding (`talos.sim`)

Run ANY tick-driven loop (animal AI, custom pathfinding, experiments) safely — sims run on the script
worker (game thread never enters Python) with hard limits: max 16 sims, ≤20 Hz, per-step `budget_ms`
(5 consecutive over-budget steps → auto-throttle to half rate), circuit breaker (5 consecutive
exceptions → auto-pause; `sim.resume()` after fixing), bounded action queues, `/talos stop` kills all.

```python
from talos import sim
s = sim.Simulation("sheep", hz=4, budget_ms=5)   # name, steps/sec, soft budget
@s.tick
def step(dt): ...                                 # dt = secs since last step; use s.state, s.rng
s.start()                                         # also: stop/pause/resume, @s.on_start/@s.on_stop
```

`s.state` = your dict · `s.rng` = per-sim seeded RNG (reproducible; `s.seed(n)`); randomness only picks
parameters inside a state machine (graze→wander), like vanilla mob AI. A client sim drives the PLAYER,
virtual creatures in `s.state`, or reactions to real mobs — it cannot puppet server mobs. Reference:
`/talos example sim`. Custom pathfinding: pure Python from raw primitives (`/talos example pygoto` —
from-scratch goto + `@talos.command("goto")` override), or a Java engine jar via the
`talos:pathing_engine` Fabric entrypoint (implement `PathingEngine` + `PathingEngineProvider`; registry
picks highest-priority available; that's how the Baritone adapter plugs in).

## Human mode (`/talos human on` · `talos.human(True)`)

Bundles eased, non-instant **cube-aim** (a 1×1m yellow guide cube centred on the target; the red X lands on
a visible face weighted by visible area, center-biased; fast-then-slow rotation with jitter + a red preview
line) with a **session-arc fatigue** model: over wall-clock time reactions slow and spread, aim loosens and
overshoots more, the walk wobbles wider, and idle micro-breaks pause pathing (more/longer as fatigue rises).
HUD shows `human » fatigue N% (Mm)`. Off = direct snap aim, no drift. Best-effort long-session obfuscation
only. Tune how strong all of this is with `/talos human intensity <0..3>` and per-knob
`/talos human set <knob> <v>` (see Humanisation tuning above); both persist across sessions.

## Gotchas checklist (verify before finishing a script)

- [ ] `import talos` + pure-Python stdlib only (`random`/`math`/`json`/… fine); no file/network/process/`pip`/native modules.
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

`~/.talos/rules.json` (rules/schedules) · `~/.talos/macros/` · `~/.talos/token` (bridge auth for VS Code +
CLI, per-launch) · `~/.talos/bin/` (the `talos` terminal CLI) · `~/.talos/logs/` (session logs, newest 10)
· `.minecraft/talos/scripts/` (scripts + `require`d libs).

## Debugging workflow (bug hunting)

1. `/talos debug on` (or `talos py -c 'talos.debug_mode(True)'`) — engine narrates pathing
   plans/replans/stalls, rule fires, action state transitions, script lifecycle to chat + file.
2. `talos logs -f` in a terminal follows `~/.talos/logs/session-<newest>.log`
   (`HH:mm:ss.SSS [LEVEL] [category] msg`; grep `[pathing]`, `[rules]`, `[actions]`, `[script]`).
3. Probe state without a script: `talos py -c 'talos.get("server_tps")'` · in-game `/talos get <name>`
   reads exactly what a rule trigger would see.
4. Sprinkle `talos.debug(...)` in scripts — invisible until debug mode is on, safe to leave in.
5. Reduce to a repro script and bisect from the shell: `while ! talos repro.py; do …; done` (exit 1 =
   still broken); VS Code run-on-save for fast iteration.
6. `/talos script profile` toggles per-event dispatch profiling; attach the session log when reporting.

## Architecture (for testing parts in isolation)

`/talos` commands, the VS Code extension and the `talos` CLI (both via the loopback WebSocket,
JSON protocol v1 in `vscode-extension/PROTOCOL.md`) all feed **ScriptEngine** (≤8 sessions; one
worker thread + one GraalPy context each — the client tick thread never enters Python). Python
reaches the game only through **TalosNativeBridge** exports marshalled onto the client tick via
**GameThreadExecutor**. Testable seams: every run takes an injectable `LogSink` (chat is just the
default); the wire protocol is mockable without Minecraft; CLI exit codes make shell/CI checks
possible (`talos selftest.py && echo PASS`); `talos.set_seed(n)` makes humanized runs
deterministic; every rule trigger doubles as `/talos get <name>`; `/talos debug on` traces
pathing/rules/actions/script internals to `~/.talos/logs/session-*.log`. The README's
"Architecture" section carries a ready-to-extend `selftest.py` check-runner.
