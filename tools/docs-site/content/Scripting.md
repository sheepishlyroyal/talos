# Python scripting (`import talos`)

`/talos script run <name>` runs `.minecraft/talos/scripts/<name>.py` through an embedded **GraalPy**
runtime with a curated `talos` module already in scope.

- Python runs on a **dedicated worker thread**; the game tick thread never enters Python, so blocking
  calls block only your script — render FPS is unaffected.
- `pip`, native packages, host classes, filesystem and environment access are **unavailable**. It's a
  hardened capability surface, not full CPython. **Only `import talos`.**
- **No `pip install talos`** — `talos` is provided by the mod's embedded GraalPy runtime, not a PyPI
  package. You install nothing: drop a `.py` in `.minecraft/talos/scripts/` and `import talos`.
- A plain `talos.goto(...)` **pauses every Python task in the session** until it returns. To run things
  concurrently, use `await talos.aio.goto(...)` inside an `async def`.
- End a script with `talos.run()` if it registers any task/event/`@every`/`@command` (implicit at module
  end, but be explicit).

> ⚠️ **Your own chat echoes back** into the `chat` event. Always guard `chat`/`mention` handlers against
> reacting to your own messages.

## API by category

**Metadata & libraries** — `talos.args` (`list[str]` from `/talos script run <name> args…`) ·
`talos.require("lib")` (import another `talos/scripts/` file as a module) · `talos.state` (persistent
per-script dict).

**Logging** — `talos.log(msg, level="info")` (session log file + mod log + script console) ·
`talos.debug/info/warn/error(msg)` shorthands (`warn` yellow, `error` red in chat; `debug` only visible
while debug mode is on) · `talos.debug_mode(enabled=None)` (query/toggle the master switch — same as
`/talos debug on|off`). Debug mode also streams the engine's own trace (pathing, rules, actions, script
lifecycle) to chat and `~/.talos/logs/session-<timestamp>.log` (newest 10 kept). See
[Detailed logging](Detailed-Logging).

**Libraries** — with no pip, `talos.require` *is* the module system: a library is just another `.py`
in `talos/scripts/` defining functions (`lib = talos.require("mininglib"); lib.vein("diamond_ore")`).
Cached per run (CPython semantics), cache resets on every (re)run so edits apply next run; same
sandbox + trust summary as scripts; only files directly in `talos/scripts/`. Keep module-level code
to `def`s/constants. Test one standalone from a terminal:
`talos py -c 'lib = talos.require("mininglib"); talos.log(lib.vein("stone", 8))'` — see
[Terminal CLI](Terminal-CLI) and the debugging workflow in
[Architecture & Testing](Architecture-and-Testing).

**Movement & pathing** — `goto(x, y=None, z=None)` · `goto_near(x, y, z, range)` · `goto_xz(x, z)` ·
`goto_block(block_id, radius=64)` · `follow(target, distance=3.0)` · `move_ahead(distance)` (neg = back
off) · `set_node_count(n)`. All accept numbers, `~`/`^` token strings, one `"~ ~1 ~"` string, or a
`Pos`/`Entity`.

**Named-process control (non-blocking watchdog)** — `killprocess(name)` (alias `kill_process`) ·
`process_time(name)` → secs or `-1` · `time_exceeds(name, secs)` → bool. Names: `goto`, `goto_block`,
`follow`, `path`, `pathing`, or a task name.

**Raytrace & local coords** — `local(left, up, forward)` → `Pos` (from eyes; `local(0,0,5)` = 5 ahead) ·
`ahead(distance)` → `Pos` · `raytrace(max_distance=64.0)` → `Hit | None` ·
`raytrace_if(block=None, entity=None, max_distance=64.0)` → bool.

**Finding** — `find_block(name, radius=64)` → `Pos|None` · `find_entity(type, radius=64.0)` →
`Entity|None` · `find_item(item, radius=64.0)` → `Entity|None` · `players(radius=128.0)` ·
`nearest_player(radius=128.0)` · `entities(type=None, radius=64.0)`.

**World actions** — `place_block(x=None, y=None, z=None, block_id=None)` (no coords = at crosshair) ·
`place_look()` · `break_block(x, y=None, z=None)` · `mine(...)` (alias) · `mine_looking_at()` ·
`left_click()` · `right_click()` · `kill_nearest(radius=6.0)`.

**Aim & look** — `look(yaw, pitch)` · `look_at(x, y=None, z=None)` · `look_angle()` → `(yaw, pitch)` ·
`looking_at()` → `Pos|None` · `angle_to(x, y=None, z=None)` → `(yaw, pitch)`.

**Chat & commands** — `chat(msg)` sends a chat message to the server (a leading `/` runs it as a
command) · `run_command(cmd)` runs a command (`/` optional): `/talos …` client commands dispatch
locally so scripts can drive Talos itself; anything unhandled goes to the server. Both return the
text. ⚠️ Own messages echo into the `chat` event — guard against loops; while `talos.input()` is
waiting, a plain `chat()` is consumed as that answer; neither is humanized — rate-limit with
`wait_between`. Distinct from `@talos.command(name)`, which *registers* a `/talos` subcommand.

**Position & sensing** — `player_pos()` (eye) · `player_feet()` · `block_at(x, y=None, z=None)` → id ·
`on_edge(margin=0.3)` → bool · `get(name, *args)` → the shared catalog
([Event rules & getters](Event-Rules-and-Getters)).

**Input** — `key(name, pressed=True)` · `tap(name)` (one tick) · `release_keys(*names)` (none = all) ·
`select_slot(0-8)`.

**Inventory** — `inventory()` → `list[{slot,id,count}]` · `hotbar()` · `selected_slot()` · `count(id)` ·
`has(id)` · `find_slot(id)` · `container_items()` · `container_slot_count()` ·
`click_slot(slot, right=False)` · `move_stack(from, to)` · `take_stack(container_slot, player_slot)` ·
`deposit(id, amount)` → moved · `withdraw(id, amount)` → moved · `craft(id, count=1)` · `armor_item(slot)`
· `equip_armor(from, armor_slot)` · `screen()` · `close_screen()`.

**HUD** — `hud(text, id="hud")` pins a top-left line (same id updates in place; different ids stack; max
20 lines / 256 chars; `§` colour codes work; cleared when the script stops) · `hud_remove(id="hud")` ·
`hud_clear()`.

**Humanization & timing** — `wait(a, b=None)` / `wait_between(a, b)` (right-skewed pause) ·
`set_profile("raw"|"natural"|"paranoid")` · `set_seed(seed)` · `human(enabled=None)` · `fatigue()` → 0–1 ·
`on_break()` → bool · `intensity(v=None)` (global more/less dial, 0–3) · `tune(**knobs, families=[...])`
(per-knob overrides, clamped safe) · `human_knobs()` → dict · `reset_tuning()` · `sleep(secs)` ·
`ticks(n)` · `next_tick()` · `tick_count()`. See [Humanization](Humanization) for the knob table.

**Simulations (`talos.sim`)** — `sim.Simulation(name, hz=20, budget_ms=5)` with `@sim.tick(dt)`,
`@sim.on_start`/`@sim.on_stop`, `start/stop/pause/resume`, `sim.state` dict, seeded `sim.rng`. Safety
limits: max 16 sims, ≤20 Hz, over-budget auto-throttle, 5-error circuit breaker. See
[Simulations & custom pathfinding](Simulations-and-Custom-Pathfinding).

### Python standard library

Pure-Python stdlib imports work out of the box — `random`, `math`, `json`, `collections`, `heapq`,
`itertools`, `re`, `dataclasses`, `time`, …:

```python
import random
import talos
talos.log(random.randint(1, 10))
```

**Not available** (blocked by the sandbox, by design): pip packages, native extensions (`numpy`,
`PIL`), host file/socket IO (`open`, `socket`, `urllib`), `threading`, `subprocess`, `os.environ`.
Demo: `/talos example stdlib`, or from the terminal:
`talos run 'import random;import talos;talos.log(random.randint(1,10))'`.

## Events — `@talos.on("<event>")`

Registers a handler on the worker thread. These are push callbacks — **not** the 206 rule triggers (read
those with `talos.get`).

| Event | Handler |
|---|---|
| `tick` | `fn()` — every game tick |
| `chat` | `fn(message, sender)` — `sender` is a player name or `None` (system). **Own messages echo — guard.** |
| `entity_hurt` | `fn(type_id, entity_id, x, y, z)` |
| `health` | `fn(health)` |
| `death` / `disconnect` | `fn()` |
| `item_pickup` | `fn(item_id, amount)` |
| `goto_start` | `fn(x, y, z)` · `goto_done` `fn(success, detail)` · `goto_stuck` `fn(detail)` |

## Async, tasks & commands

- `@talos.task` / `talos.start(coro, name=None)` — run an `async def` as a concurrent task.
- `talos.aio.*` — awaitable versions of the blocking actions (`goto`, `goto_near`, `goto_xz`,
  `goto_block`, `follow`, `find_block`, `place_block`, `break_block`, `mine`, `mine_looking_at`,
  `kill_nearest`, `wait`, `wait_between`, `input`). Use inside `async def` so other tasks keep running.
- `@talos.on_start` / `@talos.on_tick` — module-level lifecycle hooks.
- `@talos.every(seconds=…, minutes=…, ticks=…)` — run a function on a cadence.
- `@talos.command("name", suggest=None)` — register `/talos <name>` in Python; the handler receives the
  arguments as a `list[str]` (async handler runs as a task; overrides a built-in when the name matches).
  `suggest` adds chat tab-completion: a list of tokens for the first argument, or a list of lists
  per-position — `@talos.command("farm", suggest=[["wheat", "carrot"], ["16", "64"]])`. Tokens must not
  contain whitespace; suggestions are captured at registration and served host-side (the game never
  calls into Python to compute them).
- `talos.run()` — start the loop. `talos.cancel_all()`, `TaskHandle.cancel()`, `talos.parallel(...)`,
  `talos.spawn(fn, …)`.
- `talos.input(prompt)` / `await talos.aio.input(prompt)` — block for the user's next chat message
  (captured locally, never sent).

## Snapshot types & errors

`Pos(.x/.y/.z)` · `Entity(.uuid/.type/.pos/.distance)` · `Player(.name/…)` ·
`Hit(.type/.id/.pos/.distance)` (`.type` = `"block"`/`"entity"`). Failures raise `TalosError`,
`PathFailedError`, `OutOfReachError`, `NotFoundError`, `TargetLostError`, `ActionCancelledError`,
`WorldClosedError`.

## Gotchas checklist

- [ ] `import talos` only; no stdlib file/network/process/`pip`.
- [ ] `talos.run()` present if any `@task`/`@on`/`@every`/`@command`/`@on_tick` is used.
- [ ] Used `await talos.aio.<action>()` (not the blocking form) when other tasks must keep running.
- [ ] `chat`/`mention` handlers guard against your own echoed messages.
- [ ] `^` = **local frame** in `raytrace`/scripts but a **relative angle** in `look`/`coords`/`mine
      direction`.
- [ ] Long loops call `talos.wait_between(...)` / `next_tick()` so they yield and stay cancellable.

See [Example scripts](Examples) for complete, runnable programs.
