"""A wandering "sheep" — the reference script for talos.sim (custom simulations).

The Simulation framework runs any tick-driven loop (animal AI, custom
pathfinding, farming logic, anything) on the script worker thread with safety
limits built in: a per-step time budget that auto-throttles slow sims, a
circuit breaker that pauses a sim after 5 consecutive errors, and a cap of 16
sims per script. A broken sim can never stall or crash the game — the worst
case is its own pause.

Run:  /talos script run sheep_sim     or:  talos run sheep_sim.py
Stop: /talos script stop
"""

import math
import talos
from talos import sim

sheep = sim.Simulation("sheep", hz=4, budget_ms=5)

TURN_STEP = 10.0   # max yaw correction per step, degrees (eased steering)
WANDER = 8.0       # how far each wander target may be, blocks
ARRIVE = 1.0       # horizontal distance that counts as "there"


@sheep.on_start
def hello():
    talos.hud("sheep sim: grazing and wandering — /talos stop to end", id="sheep")


@sheep.tick
def step(dt):
    st = sheep.state
    if st.setdefault("mode", "graze") == "graze":
        # Stand still for a few humanized seconds, then pick a nearby target.
        st["graze_left"] = st.get("graze_left", sheep.rng.uniform(1.0, 4.0)) - dt
        if st["graze_left"] <= 0:
            feet = talos.player_feet()
            st["target"] = (feet.x + sheep.rng.uniform(-WANDER, WANDER),
                            feet.z + sheep.rng.uniform(-WANDER, WANDER))
            st["mode"] = "wander"
            del st["graze_left"]
        return

    # Wander: eased steering toward the target using only raw inputs.
    tx, tz = st["target"]
    feet = talos.player_feet()
    dx, dz = tx - feet.x, tz - feet.z
    if math.hypot(dx, dz) < ARRIVE:
        talos.release_keys()
        st["mode"] = "graze"
        return
    want = math.degrees(math.atan2(-dx, dz))     # MC yaw: 0=south, 90=west
    yaw, _pitch = talos.look_angle()
    delta = (want - yaw + 180.0) % 360.0 - 180.0
    talos.look(yaw + max(-TURN_STEP, min(TURN_STEP, delta)), 12.0)
    talos.key("forward")


@sheep.on_stop
def bye():
    talos.release_keys()
    talos.hud_remove("sheep")


# suggest= gives the argument tab-completion in chat (per-position lists).
@talos.command("sheep", suggest=[["start", "stop", "pause", "resume"]])
def sheep_cmd(args):
    action = args[0] if args else "start"
    getattr(sheep, action)()
    talos.log(f"sheep sim: {action}")


sheep.start()
talos.run()
