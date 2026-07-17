# Simulations & custom pathfinding

Talos is open at both ends: you can run **any tick-driven simulation of your own** (animal AI,
farming brains, experiments — anything) through `talos.sim`, and you can **replace the pathfinding
engine** — either purely in Python or with a Java engine of your own.

## `talos.sim` — the simulation framework

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
- `sim.state` — a dict for the simulation's own data (positions, mode machines, counters).
- `sim.rng` — a per-simulation seeded RNG (seeded from the sim's name; re-seed with `sim.seed(n)`).
- `sim.start() / stop() / pause() / resume()`, `sim.running`, `sim.paused`; module-level
  `sim.sims()` and `sim.stop_all()`.

### Randomness vs simulation

"Random" and "simulated" are not in conflict — randomness is an *input* to the model, not a
replacement for it. An animal sim is a state machine (graze → wander → graze) where the RNG only
picks parameters: how long to graze, where to wander. The structure makes it look like an animal;
the randomness stops it looking mechanical. That is exactly how Minecraft's own mob AI works. And
because `sim.rng` is seeded, the same seed replays the identical "random" run — sims are testable
and debuggable deterministically.

### The "can't crash the game" contract

Simulations run on the script worker thread — the game thread never executes Python — and the
framework enforces hard limits on top:

| Limit | Value | What happens |
|---|---|---|
| Max simulations | 16 per script session | creating more raises `SimulationError` |
| Step rate | ≤ 20 Hz (1 step/tick) | faster rates are rejected |
| Step budget | `budget_ms` (default 5 ms) | 5 consecutive over-budget steps auto-throttle the sim to half rate, with a warning |
| Circuit breaker | 5 consecutive exceptions | the sim auto-pauses with an error log; `sim.resume()` after fixing |
| Action spam | bounded 256-slot queues | worker→game actions fail fast instead of piling up |
| Hard stop | `/talos stop`, script stop | every sim ends immediately |

### What a client-side sim can and can't do

It **can** drive the player like an animal (`/talos example sim` — a wandering sheep), keep purely
virtual creatures in `sim.state` and visualise them via HUD/glow, and read + react to real mobs
(`talos.entities()`, `talos.get(...)`). It **cannot** puppet server-controlled mobs — no client mod
can.

## Custom pathfinding — two tiers

### 1. Pure Python (no Java needed)

Build movement from raw primitives: `player_feet()`, `look_angle()`, `look(yaw, pitch)`,
`key("forward")`, `raytrace()`, `block_at()`. The shipped reference `/talos example pygoto` is a
complete from-scratch goto — eased steering (clamped yaw correction per tick), forward hold, and a
stall watchdog that taps jump — plus a `@talos.command("goto")` **override** that intercepts
`/talos goto` while the script runs. The built-in stays reachable as `talos.goto` /
`talos.aio.goto`, so an override can pre-process (speedbridge, scaffold, log) and then delegate to
the real pathfinder.

### 2. A Java engine replacement

Talos discovers pathfinding engines through the Fabric entrypoint **`talos:pathing_engine`**. Any
mod jar can ship one:

1. Implement `dev.talos.client.pathing.PathingEngine` — `isAvailable()`,
   `goTo(Goal, PathingOptions)`, `cancel()`, `isPathing()` (optional: `setNodeCount`, `retarget`,
   `getCurrentNodes`).
2. Implement `PathingEngineProvider` — `create()` and `priority()`.
3. Declare the entrypoint in your `fabric.mod.json`:
   ```json
   { "entrypoints": { "talos:pathing_engine": ["com.example.MyEngineProvider"] } }
   ```

At startup the registry collects every provider, sorts by priority, and picks the first engine that
reports `isAvailable()`. Scripts notice nothing — `talos.goto()` just uses the winning engine. This
is exactly how the optional Baritone adapter (`talos-pathing-baritone`) plugs in. With no engine
available, a `NoOpPathingEngine` fails calls with a typed error instead of crashing.

## Custom `/talos` commands with argument suggestions

Simulations and custom pathfinders usually want a control command. `@talos.command` supports
arguments and chat tab-completion:

```python
@talos.command("sheep", suggest=[["start", "stop", "pause", "resume"]])
def sheep_cmd(args):             # args: list[str]
    getattr(sheep, args[0] if args else "start")()
```

`suggest` is a list of tokens for the first argument, or a list of lists for per-position
suggestions (`suggest=[["wheat", "carrot"], ["16", "64"]]`). Tokens must not contain whitespace.
Suggestions are captured at registration and served host-side — the game never calls into Python to
compute them.

## Shipped examples

`/talos example sim` (wandering sheep + control command) · `/talos example pygoto` (from-scratch
goto + override) · `/talos example stdlib` (`import random` & friends). The same scripts live in the
repo's `examples/` folder.
