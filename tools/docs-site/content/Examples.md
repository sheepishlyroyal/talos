# Example scripts

Talos ships a set of **bundled reference scripts**. Write one to `talos/scripts/` and run it:

```
/talos example                 # list every bundled example
/talos example lumberjack      # writes talos/scripts/example_lumberjack.py
/talos script run example_lumberjack [maxTrees]
```

`/talos example <name>` **overwrites** its file every time — they're reference material, not saved state.
Read them, then copy and adapt. Every one uses only the documented API (see [Python scripting](Scripting)).

| Example | What it shows |
|---|---|
| [`goto`](#goto--custom-goto--command-override) | A from-scratch pathless goto on raw primitives, plus overriding the built-in `/talos goto` |
| [`farm`](#farm--find--goto--mine-loop) | A tiny find → goto → harvest loop with humanized waits |
| [`follow`](#follow--override--from-scratch-shadow) | Overriding `/talos follow` and a from-scratch follow loop |
| [`lumberjack`](#lumberjack--fell-trees) | A practical multi-wood tree feller |
| [`events`](#events--every-taloson-handler) | Every `talos.on(...)` push event, printed live |
| [`sensors`](#sensors--live-taloget-dashboard) | A live dashboard built entirely from `talos.get()` — a tour of the getter families |
| `sim` | A wandering "sheep" — the `talos.sim` simulation framework (state machine + seeded RNG), plus a `suggest=`-completed control command. See [Simulations & custom pathfinding](Simulations-and-Custom-Pathfinding) |
| `pygoto` | Write-your-own pathfinding from raw primitives + a `/talos goto` override (sibling of `goto`) |
| `stdlib` | The Python standard library out of the box: `random`, `json`, `math`, `collections`, … |

---

## `goto` — custom goto + command override

`/talos example goto` — a pure-Python goto built on raw primitives (no pathfinder), plus a `/talos goto`
override that wraps the built-in. This is the pattern for **replacing or extending any built-in command**
from Python: register `@talos.command("goto")`, do your custom prep, then delegate to `talos.aio.goto(...)`
(the original stays reachable).

```python
# example_goto.py -- a pure-Python goto built on raw primitives, plus a
# /talos goto override. Reference material: /talos example goto rewrites it.
#
# Run:    /talos script run example_goto
# Then:   /talos pygoto <x> <y> <z>     (from-scratch goto, no pathfinder)
#         /talos goto xyz <x> <y> <z>   (now routed through goto_override)
# Stop:   /talos script stop            (handlers unregister automatically)

import math
import talos

ARRIVE = 0.5        # horizontal distance (blocks) that counts as "arrived"
TURN_STEP = 12.0    # max yaw correction per tick -- small = smooth turning
STALL_TICKS = 8     # ticks without progress before we tap jump


def yaw_toward(feet, x, z):
    # Minecraft yaw: 0 = south (+Z), 90 = west (-X) -- hence atan2(-dx, dz).
    return math.degrees(math.atan2(-(x - feet.x), z - feet.z))


async def naive_goto(x, y, z):
    # Once per tick: measure the offset, ease the yaw toward it, hold forward,
    # and tap jump when the distance stops shrinking (a step or lip ahead).
    tx, tz = x + 0.5, z + 0.5   # aim for the center of the target cell
    best = None                 # closest distance reached so far
    stall = 0                   # ticks since best last improved
    try:
        while True:
            feet = talos.player_feet()
            dx, dz = tx - feet.x, tz - feet.z
            dist = (dx * dx + dz * dz) ** 0.5
            if dist <= ARRIVE:
                talos.log(f"pygoto: arrived ({dist:.2f} blocks from target)")
                return

            # Eased steering: shortest signed turn toward the target, clamped.
            yaw, _pitch = talos.look_angle()
            delta = (yaw_toward(feet, tx, tz) - yaw + 180.0) % 360.0 - 180.0
            step = max(-TURN_STEP, min(TURN_STEP, delta))
            talos.look(yaw + step, 15.0)  # slight downward pitch: watch our feet

            talos.key("forward")          # HELD until released -- see finally

            # Progress watchdog: a ~1-block step ahead stops us; tap jump.
            if best is None or dist < best - 0.05:
                best, stall = dist, 0
            else:
                stall += 1
                if stall >= STALL_TICKS:
                    stall = 0
                    talos.tap("jump")     # one tick, releases itself

            await talos.next_tick()       # one control decision per game tick
    finally:
        talos.release_keys()              # never leave W held down


@talos.command("pygoto")
def pygoto(args):
    # /talos pygoto <x> <y> <z> -- returning the coroutine starts it as a task,
    # so chat (and other tasks) stay responsive while it walks.
    if len(args) != 3:
        talos.log("usage: /talos pygoto <x> <y> <z>")
        return
    x, y, z = (int(float(a)) for a in args)
    talos.log(f"pygoto: walking to {x} {y} {z} (raw inputs, no pathfinder)")
    return naive_goto(x, y, z)


@talos.command("goto")
def goto_override(args):
    # Replaces /talos goto while this script runs. The built-ins stay reachable
    # as talos.goto / talos.aio.goto / talos.aio.goto_block, so the override can
    # prep, then delegate. Handles both forms:
    #   /talos goto xyz <x> <y> <z>
    #   /talos goto block <blockId> [radius]
    async def wrapped_xyz(x, y, z):
        # Custom prep goes here (speedbridging, scaffolding, logging, ...).
        result = await talos.aio.goto(x, y, z)  # the ORIGINAL goto, unchanged
        talos.log(f"goto override: built-in finished -- {result}")

    async def wrapped_block(block_id, radius):
        result = await talos.aio.goto_block(block_id, radius)
        talos.log(f"goto override: goto_block finished -- {result}")

    if args and args[0] == "block":
        radius = int(args[2]) if len(args) > 2 else 64
        return wrapped_block(args[1], radius)
    if args and args[0] == "xyz":
        args = args[1:]
    if len(args) != 3:
        talos.log("this override handles xyz <x> <y> <z> and block <id> [radius]")
        return
    x, y, z = (int(float(a)) for a in args)
    return wrapped_xyz(x, y, z)


talos.log("example_goto loaded: /talos goto overridden, /talos pygoto added")
```

---

## `farm` — find → goto → mine loop

`/talos example farm` — scan for a crop, walk to it, harvest, and wait a humanized moment before scanning
again.

```python
# example_farm.py -- a tiny farming loop: find a crop, walk to it, harvest
# it, and wait a humanized moment before scanning again. Reference material:
# /talos example farm rewrites it.
#
# Run:    /talos script run example_farm
# Stop:   /talos script stop

import talos

# Crops to harvest, in preference order. Add "minecraft:carrots" etc. here.
CROPS = ["minecraft:sugar_cane", "minecraft:wheat"]


@talos.task
async def farm():
    while True:
        target = None
        for crop in CROPS:
            target = await talos.aio.find_block(crop, 32)
            if target:
                break
        if target is None:
            talos.log("farm: nothing to harvest nearby, waiting...")
            await talos.aio.wait(2.0, 4.0)   # humanized pause between scans
            continue

        await talos.aio.goto_near(int(target.x), int(target.y), int(target.z), 2)
        await talos.aio.mine(target)

        # Replanting: for wheat, select the hotbar slot holding seeds and
        # place them back on the farmland, e.g.:
        #   talos.select_slot(0)                          # slot with seeds
        #   talos.place_block(target.x, target.y, target.z)
        # Sugar cane regrows from the stump, so it needs no replant.

        await talos.aio.wait(0.4, 0.9)       # human-ish pause per harvest
```

---

## `follow` — override + from-scratch shadow

`/talos example follow` — override `/talos follow` with custom behaviour layered on the built-in, plus a
from-scratch `/talos shadow` loop on raw primitives for full control.

```python
# example_follow.py -- customize /talos follow. Reference material:
# /talos example follow rewrites it.
#
# Run:    /talos script run example_follow
# Then:   /talos follow <name|type|@e[...]> [distance]   (routed through here)
#         /talos shadow <target>                          (from-scratch loop)
# Stop:   /talos script stop
#
# Targets accept players AND any entity: "Steve", "zombie",
# "@e[type=cow,distance=..20]", "@p", "@n". The built-in stays reachable as
# talos.follow / talos.aio.follow.

import talos


@talos.command("follow")
def follow_override(args):
    # Peel a trailing number off as the keep-distance, like the built-in does.
    if not args:
        talos.log("usage: /talos follow <target> [distance]")
        return
    distance = 3.0
    if len(args) > 1:
        try:
            distance = float(args[-1])
            args = args[:-1]
        except ValueError:
            pass
    target = " ".join(args)

    async def wrapped():
        talos.log(f"following {target!r}, keeping ~{distance} blocks")
        # Custom behavior goes here: sprint-only follow, waypoint logging,
        # auto-eat while following, breaking off when health drops, ...
        try:
            await talos.aio.follow(target, distance)  # the ORIGINAL follow
        except talos.PathFailedError as error:
            talos.log(f"follow ended: {error}")

    return wrapped()


@talos.command("shadow")
def shadow(args):
    # A from-scratch follow on raw primitives: re-goto the target's feet
    # whenever they stray, with everything (pace, distance, pathing options)
    # under your control.
    if not args:
        talos.log("usage: /talos shadow <target>")
        return
    target = " ".join(args)

    async def loop():
        while True:
            entity = talos.find_entity(target, 64) if ":" in target \
                else next((p for p in talos.players()
                           if p.name.lower() == target.lower()), None)
            if entity is None:
                talos.log("shadow: target not in range, waiting...")
                await talos.aio.wait(1.0, 2.0)
                continue
            if entity.distance > 4.0:
                await talos.aio.goto_near(int(entity.pos.x), int(entity.pos.y),
                                          int(entity.pos.z), 2)
            await talos.aio.wait(0.3, 0.6)

    return loop()


talos.log("example_follow loaded: /talos follow overridden, /talos shadow added")
```

---

## `lumberjack` — fell trees

`/talos example lumberjack` — find a log of any wood type, walk to it, mine the trunk column upward, and
move on to the next tree. Pass a count (`/talos script run example_lumberjack 5`) or omit it to run until
you `/talos script stop`.

```python
# example_lumberjack.py -- fell nearby trees: find a log, walk to it, mine
# the trunk column upward, then look for the next one. Reference material:
# /talos example lumberjack rewrites it.
#
# Run:    /talos script run example_lumberjack [maxTrees]   (0/omitted = forever)
# Stop:   /talos script stop

import talos

LOGS = ["minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log",
        "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
        "minecraft:mangrove_log", "minecraft:cherry_log"]


def nearest_log(radius=48):
    # find_block returns the nearest of ONE id; scan every log type and keep
    # the closest hit across them all.
    best = None
    feet = talos.player_feet()
    for log in LOGS:
        pos = talos.find_block(log, radius)
        if pos is None:
            continue
        d = ((pos.x - feet.x) ** 2 + (pos.y - feet.y) ** 2 + (pos.z - feet.z) ** 2) ** 0.5
        if best is None or d < best[0]:
            best = (d, pos)
    return best[1] if best else None


@talos.task
async def chop():
    goal = int(talos.args[0]) if talos.args else 0   # 0 = run until stopped
    felled = 0
    while goal == 0 or felled < goal:
        tree = nearest_log()
        if tree is None:
            talos.hud("§6lumberjack §7» no trees in range, waiting...", id="lj")
            await talos.aio.wait(2.0, 4.0)
            continue

        tx, ty, tz = int(tree.x), int(tree.y), int(tree.z)
        talos.hud(f"§6lumberjack §7» felling tree at {tx} {ty} {tz}", id="lj")
        await talos.aio.goto_near(tx, ty, tz, 2)

        # Mine straight up the trunk: same X/Z column, block by block, for as
        # long as the block there is a log.
        y = ty
        while True:
            here = talos.block_at(tx, y, tz)
            if "log" not in here:
                break
            await talos.aio.mine(tx, y, tz)
            await talos.aio.wait(0.15, 0.4)   # humanized swing cadence
            y += 1

        felled += 1
        logs = talos.count("minecraft:oak_log") + talos.count("minecraft:birch_log")
        talos.hud(f"§6lumberjack §7» felled {felled} · logs carried {logs}", id="lj")
        await talos.aio.wait(0.5, 1.2)
    talos.log(f"lumberjack: done, felled {felled} trees")

talos.run()
```

---

## `events` — every `talos.on(...)` handler

`/talos example events` — subscribe to **every** `talos.on(...)` push event and print what fires, so you
can watch the live event stream. (These ten push callbacks are distinct from the 206 `/talos on` rule
triggers — read those with `talos.get`; see `sensors` below.)

```python
# example_events.py -- subscribe to EVERY talos.on(...) event and print what
# fires, so you can watch the live event stream. Reference material:
# /talos example events rewrites it.
#
# Run:    /talos script run example_events
# Stop:   /talos script stop
#
# NOTE: talos.on(...) push events (below) are a small set of worker-thread
# callbacks. They are NOT the 206 /talos on <trigger> rule names -- every one
# of those is readable instead via talos.get(name, *args); see example_sensors.

import talos

@talos.on("tick")
def _tick():
    # Fires ~20x/second. Keep it cheap. Here it does nothing but prove it runs.
    pass

@talos.on("chat")
def _chat(message, sender):
    # sender = a player name, or None for system lines. YOUR OWN messages echo
    # back here too -- always guard against reacting to yourself.
    who = sender if sender is not None else "<system>"
    talos.log(f"[chat] {who}: {message}")

@talos.on("health")
def _health(health):
    talos.hud(f"§chealth §f{health:.1f}", id="ev_health")

@talos.on("entity_hurt")
def _entity_hurt(type_id, entity_id, x, y, z):
    talos.log(f"[entity_hurt] {type_id} #{entity_id} @ {x:.1f} {y:.1f} {z:.1f}")

@talos.on("item_pickup")
def _pickup(item_id, amount):
    talos.log(f"[item_pickup] +{amount} {item_id}")

@talos.on("death")
def _death():
    talos.hud("§4you died", id="ev_death")

@talos.on("goto_start")
def _goto_start(x, y, z):
    talos.log(f"[goto_start] planning to {x} {y} {z}")

@talos.on("goto_done")
def _goto_done(success, detail):
    talos.log(f"[goto_done] success={success} -- {detail}")

@talos.on("goto_stuck")
def _goto_stuck(detail):
    talos.log(f"[goto_stuck] replanning -- {detail}")

@talos.on("disconnect")
def _disconnect():
    talos.log("[disconnect] left the world")

talos.hud("§aevent monitor armed §7» watch the log", id="ev_title")
talos.run()
```

---

## `sensors` — live `talos.get()` dashboard

`/talos example sensors` — a live HUD dashboard built entirely from `talos.get()`, with a startup tour of
every getter **family**: plain metrics, descriptive strings, booleans, spatial getters (coords),
parameterized getters (selectors/items/enchants/regions), and event getters. Every one of the 206
`/talos on` triggers is also a getter — `/talos get list` dumps them all.

```python
# example_sensors.py -- a live dashboard built entirely from talos.get(). Every
# one of the 206 /talos on triggers is ALSO a getter: talos.get(name, *args).
# Numbers come back as int/float, booleans as bool, everything else as str.
# Reference material: /talos example sensors rewrites it.
#
# Run:    /talos script run example_sensors
# Stop:   /talos script stop
# See:    /talos get list   (dumps every observable + all 206 trigger names)

import talos

def dump_catalog_demo():
    # A guided tour of the getter FAMILIES, printed once at startup.
    talos.log("== talos.get() families ==")

    # 1) plain live numeric metrics (no args)
    for name in ("health", "hunger", "air", "xp_level", "armor_points",
                 "speed", "server_tps", "fps", "ping", "light_level"):
        talos.log(f"  {name} = {talos.get(name)}")

    # 2) descriptive string getters
    for name in ("position", "dimension", "biome", "held_item", "weather", "time"):
        talos.log(f"  {name} = {talos.get(name)}")

    # 3) boolean state getters
    for name in ("sneaking", "sprinting", "on_ground", "moving", "inventory_full"):
        talos.log(f"  {name} = {talos.get(name)}")

    # 4) spatial getters accept coords: absolute, ~ feet-relative, or ^ local frame
    talos.log(f"  light_level(^ ^ ^8 ahead) = {talos.get('light_level', '^ ^ ^8')}")
    talos.log("  block_count(diamond_ore r16, 4 below) = "
              f"{talos.get('block_count', 'minecraft:diamond_ore', 16, '~', '~-4', '~')}")

    # 5) parameterized getters: selectors, items, enchants, regions
    talos.log(f"  entity_count(@e[type=zombie] r32) = {talos.get('entity_count', '@e[type=zombie]', 32)}")
    talos.log(f"  item_count(diamond) = {talos.get('item_count', 'minecraft:diamond')}")
    talos.log(f"  held_enchant(efficiency) = {talos.get('held_enchant', 'minecraft:efficiency')}")
    talos.log(f"  entered_region(100 60 100..120 80 120) = "
              f"{talos.get('entered_region', 100, 60, 100, 120, 80, 120)}")

    # 6) event getters: latest occurrence + '[N.NNs ago]', or 'never observed'
    for name in ("damage_taken", "item_picked_up", "villager_profession_changed",
                 "sound", "explosion", "block_at_feet"):
        talos.log(f"  {name} = {talos.get(name)}")

dump_catalog_demo()

@talos.every(ticks=5)
def dashboard():
    # A compact vitals HUD refreshed 4x/second, every value straight from get().
    hp = talos.get("health"); food = talos.get("hunger")
    tps = talos.get("server_tps"); spd = talos.get("speed")
    hostile = talos.get("nearest_hostile_distance")
    talos.hud(f"§chp§f {hp:.0f}  §6food§f {food:.0f}  §aTPS§f {tps:.1f}  "
              f"§dspd§f {spd:.1f}  §chostile§f {hostile:.1f}m", id="dash_vitals")
    talos.hud(f"§7{talos.get('position')} · {talos.get('dimension')} · "
              f"{talos.get('biome')}", id="dash_where")
    talos.hud(f"§7looking at §f{talos.get('looking_at')}", id="dash_look")

talos.hud("§asensor dashboard live §7» /talos get list for all 206", id="dash_title")
talos.run()
```
