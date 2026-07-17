"""Own /talos goto from Python — the reference script for @talos.command.

Two things are demonstrated here:

  1. `pygoto` — a from-scratch goto built ONLY on raw primitives (player_feet,
     look_angle, key, look). No pathfinder involved: it steers the camera a
     little each tick, holds W, and jumps when progress stalls. Naive on
     purpose, so you can see every moving part and replace any of them.

  2. A `goto` OVERRIDE — while this script runs, `/talos goto ...` is routed
     here instead of the built-in. The override can do any custom prep
     (speedbridging, scaffolding, logging, ...) and then delegate to the REAL
     pathfinder, which is still reachable from Python as talos.goto / talos.aio.goto.

Run:    /talos script run custom_goto
Then:   /talos pygoto <x> <y> <z>      (also works as /talos cmd pygoto ...)
        /talos goto <x> <y> <z>        (now routed through goto_override below)
Stop:   /talos script stop             (all handlers unregister automatically)

Handlers run on the script worker, never the client thread, and always receive
the command arguments as a list of strings.
"""

import talos

# Horizontal distance (blocks) that counts as "arrived". 0.5 = same block cell.
ARRIVE = 0.5
# Max yaw correction per tick, in degrees. Small values turn smoothly instead of
# snapping — this is the whole "eased steering" trick.
TURN_STEP = 12.0
# Ticks without horizontal progress before we tap jump (handles 1-block steps).
STALL_TICKS = 8


def _yaw_toward(feet, x, z):
    """Yaw (degrees, Minecraft convention) facing from `feet` toward x/z.

    Minecraft yaw: 0 = south (+Z), 90 = west (-X) — hence atan2(-dx, dz).
    Matches what talos.look_angle() returns, so the two compare directly.
    """
    import math
    return math.degrees(math.atan2(-(x - feet.x), z - feet.z))


async def naive_goto(x, y, z):
    """Walk to a block position using nothing but raw inputs.

    The loop, once per tick:
      * measure the horizontal offset to the target center,
      * ease the yaw toward it (at most TURN_STEP degrees per tick),
      * hold forward,
      * tap jump when the distance stops shrinking (a step or lip ahead).
    The `finally` releases every key, so a failure never leaves W held down.
    """
    tx, tz = x + 0.5, z + 0.5  # aim for the center of the target cell
    best = None                # closest distance reached so far
    stall = 0                  # ticks since `best` last improved
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
            delta = (_yaw_toward(feet, tx, tz) - yaw + 180.0) % 360.0 - 180.0
            step = max(-TURN_STEP, min(TURN_STEP, delta))
            talos.look(yaw + step, 15.0)  # slight downward pitch: watch our feet

            talos.key("forward")

            # Progress watchdog: if we haven't gotten closer for STALL_TICKS
            # ticks, something ~1 block tall is in the way — press jump for one
            # tick, then release and re-measure.
            if best is None or dist < best - 0.05:
                best, stall = dist, 0
                jump = False
            else:
                stall += 1
                jump = stall >= STALL_TICKS
                if jump:
                    stall = 0
            talos.key("jump", jump)

            await talos.next_tick()  # yield: one control decision per game tick
    finally:
        talos.release_keys()


@talos.command("pygoto")
def pygoto(args):
    """`/talos pygoto <x> <y> <z>` — run the from-scratch goto above.

    Returning the coroutine hands it to the engine as a task, so chat (and any
    other running tasks) stay responsive while it walks.
    """
    if len(args) != 3:
        talos.log("usage: /talos pygoto <x> <y> <z>")
        return
    x, y, z = (int(float(a)) for a in args)
    talos.log(f"pygoto: walking to {x} {y} {z} (raw inputs, no pathfinder)")
    return naive_goto(x, y, z)


@talos.command("goto")
def goto_override(args):
    """Replaces `/talos goto` for as long as this script session runs.

    The override receives the RAW arguments (so `/talos goto near 1 2 3 4`
    arrives as ["near", "1", "2", "3", "4"]); this simple version only handles
    the plain `<x> <y> <z>` shape and delegates the trip itself to the real
    pathfinder — the built-in is only bypassed at the chat-command layer.
    """
    talos.log(f"goto override: /talos goto {' '.join(args)}")
    if len(args) != 3:
        talos.log("this override only handles /talos goto <x> <y> <z>")
        return
    x, y, z = (int(float(a)) for a in args)

    async def wrapped():
        # --- custom prep goes here ------------------------------------------
        # This is the hook the override exists for. For example, speedbridging:
        # probe the gap ahead with talos.block_at(...), then sneak-bridge over
        # it using talos.key("sneak") + talos.look(...) + talos.place_block()
        # before handing the remaining trip to the pathfinder below.
        # ----------------------------------------------------------------------
        result = await talos.aio.goto(x, y, z)  # the ORIGINAL goto, unchanged
        talos.log(f"goto override: built-in finished — {result}")

    return wrapped()


talos.log("custom_goto loaded: /talos goto is overridden, /talos pygoto <x> <y> <z> added")
