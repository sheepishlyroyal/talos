"""
Type stubs for the `talos` scripting API exposed inside the Minecraft
Fabric client mod's embedded Python (GraalPy) runtime.

These stubs describe the shape of the API for editor tooling only — they
carry no implementation. The real `talos` module is injected into the
script's global namespace by the mod at run time; it does not exist as an
importable package outside the game.

## How to get autocomplete

Pick one:

1. **Drop this file next to your scripts.** Pylance/Pyright will pick up a
   sibling `talos.pyi` automatically for `import talos` completions in that
   folder.
2. **Point `python.analysis.stubPath` at the `stubs/` folder** that ships
   with this extension, e.g. in your workspace `.vscode/settings.json`:

   ```json
   {
     "python.analysis.stubPath": "${workspaceFolder}/.vscode/talos-stubs"
   }
   ```

## Execution model

Scripts run inside the game on a single worker thread, driven by game ticks:

    import talos

    @talos.on_start            # once, when the script starts
    def setup(): ...

    @talos.on_tick             # every game tick (plain def, keep it fast)
    def hud(): ...

    @talos.task                # long-running; many run concurrently
    async def miner():
        ore = await talos.aio.find_block("minecraft:diamond_ore", 64)
        if ore:
            await talos.aio.goto(ore)
            await talos.aio.mine(ore)
        await talos.sleep(1.0)

Inside `async def`, use the awaitable actions on `talos.aio` — they yield to
the other tasks while the action runs in-game. The plain sync actions also
work anywhere but pause every task until they finish. Action failures raise
typed exceptions (talos.PathFailedError, talos.OutOfReachError, ...) you can
catch with try/except.
"""

from typing import Any, Awaitable, Callable, Coroutine, Iterable, Iterator, Optional, TypedDict, TypeVar, Union, overload

_F = TypeVar("_F", bound=Callable[..., Any])
_EventHandler = TypeVar("_EventHandler", bound=Callable[..., None])

# --- snapshots ---------------------------------------------------------------

class Pos:
    """A world position snapshot. `pos.pos` is itself, so results and their
    positions can be passed interchangeably to goto()/mine()/look_at()/etc."""
    x: float
    y: float
    z: float
    def __init__(self, x: float, y: float, z: float) -> None: ...
    @property
    def pos(self) -> "Pos": ...
    def __iter__(self) -> Iterator[float]: ...

class Entity:
    """An entity snapshot: .uuid, .type (registry id like "minecraft:zombie"), .pos,
    and .distance (feet-to-feet from the local player; None when not measured)."""
    uuid: str
    type: str
    pos: Pos
    distance: Optional[float]

class Player(Entity):
    """A player snapshot: .name (gamertag), .uuid, .pos (exact doubles, never floored),
    .distance (feet-to-feet from the local player). .type is "minecraft:player"."""
    name: str
    distance: float

class Hit:
    """A raycast hit: .type ("block"|"entity"), .id (registry id), .pos (exact
    impact point, >=3-decimal precision), .distance (blocks from the eyes)."""
    type: str
    id: str
    pos: Pos
    distance: float

class SlotEntry(TypedDict):
    """One non-empty stack: its slot number, item registry id, and count."""
    slot: int
    id: str
    count: int

_PosLike = Union[Pos, Entity]

# --- errors ------------------------------------------------------------------

class TalosError(Exception):
    """Base class for every error raised by talos actions."""

class PathFailedError(TalosError):
    """Pathfinding could not reach the goal (blocked, timed out, or no route)."""

class OutOfReachError(TalosError):
    """The target exists but is too far away to interact with."""

class NotFoundError(TalosError):
    """No matching block, entity, item, or crosshair target was found."""

class TargetLostError(TalosError):
    """The target changed or disappeared mid-action."""

class ActionCancelledError(TalosError):
    """The action or task was cancelled, or the script session is stopping."""

class WorldClosedError(TalosError):
    """No usable world/player (left the world, disconnected, session invalidated)."""

# --- execution engine ----------------------------------------------------------

def on_start(fn: _F) -> _F:
    """Run once when the script starts. Plain `def` runs to completion first;
    `async def` becomes a task."""
    ...

def on_tick(fn: _F) -> _F:
    """Run every game tick (20/s). Must be a plain `def`; use @talos.task for async."""
    ...

def task(fn: _F) -> _F:
    """Declare a long-running `async def` task, started when the script starts.
    Any number can run concurrently; `await` points are where they interleave."""
    ...

def every(seconds: Optional[float] = None, minutes: Optional[float] = None,
          ticks: Optional[int] = None) -> Callable[[_F], _F]:
    """Run the decorated function repeatedly at a fixed interval.

    Give exactly one interval (or combine seconds+minutes):
    @talos.every(seconds=5), @talos.every(minutes=1), @talos.every(ticks=10).
    Works on a plain `def` (keep it fast) or an `async def` (awaited to
    completion before the next interval, so runs never overlap). The first run
    happens one interval after the script starts; a failed run is reported and
    the schedule keeps going."""
    ...

class TaskHandle:
    """A running async task started with talos.start()."""
    name: str
    done: bool
    error: Optional[BaseException]
    result: Any
    def cancel(self) -> None:
        """Cancel the task: ActionCancelledError is raised at its current await point."""
        ...

def start(coro: Coroutine[Any, Any, Any], name: Optional[str] = ...) -> TaskHandle:
    """Start an async task right now (from anywhere) and return its handle."""
    ...

def cancel_all() -> None:
    """Cancel every running task."""
    ...

def run() -> None:
    """Start the engine explicitly. Optional — the decorators start it automatically."""
    ...

def sleep(seconds: float) -> Awaitable[None]:
    """Awaitable pause; other tasks and on_tick hooks keep running."""
    ...

def ticks(n: int) -> Awaitable[None]:
    """Awaitable pause for n game ticks (20 ticks = 1 second)."""
    ...

def next_tick() -> Awaitable[None]:
    """Awaitable pause until the next game tick."""
    ...

def tick_count() -> int:
    """Number of engine ticks since the script started."""
    ...

class _Aio:
    """Awaitable versions of the long-running actions, for use inside async tasks.
    Each starts the in-game action and yields to other tasks until it finishes."""
    async def goto(self, x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> str: ...
    async def goto_near(self, x: float, y: float, z: float, range: int) -> str: ...
    async def goto_xz(self, x: float, z: float) -> str: ...
    async def goto_block(self, block_id: str, radius: int = 64) -> str: ...
    async def follow(self, target: str, distance: float = 3.0) -> str: ...
    async def find_block(self, name: str, radius: int = 64) -> Optional[Pos]: ...
    async def place_block(self, x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...,
                          block_id: Optional[str] = ...) -> str: ...
    async def break_block(self, x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> str: ...
    async def mine(self, x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> str: ...
    async def mine_looking_at(self) -> str: ...
    async def kill_nearest(self, radius: float = 6.0) -> str: ...
    async def wait(self, a: float, b: Optional[float] = None) -> None:
        """Awaitable talos.wait: pause for `a` seconds (or a humanized random
        duration in [a, b] when `b` is given); other tasks keep running."""
        ...
    async def wait_between(self, a: float, b: float) -> None:
        """Awaitable humanized random pause. Alias of talos.aio.wait(a, b)."""
        ...
    async def input(self, prompt: str = ...) -> str:
        """Awaitable talos.input: other tasks keep running while the user types."""
        ...

aio: _Aio

# --- sync actions ----------------------------------------------------------------

def goto(x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> str:
    """Path to a block position (or a snapshot's position) and wait for arrival.
    Raises PathFailedError if no route was found. Blocks all tasks; inside
    async tasks prefer `await talos.aio.goto(...)`."""
    ...

def goto_near(x: float, y: float, z: float, range: int) -> str:
    """Path to within `range` blocks of a position."""
    ...

def goto_xz(x: float, z: float) -> str:
    """Path to an X/Z column."""
    ...

def goto_block(block_id: str, radius: int = 64) -> str:
    """Path to the nearest matching block, retrying the next-nearest candidate
    (up to 5) when one proves unreachable. Raises PathFailedError if none work."""
    ...

def killprocess(name: str) -> bool:
    """Cancel a named automation process without stopping the script."""
    ...

def kill_process(name: str) -> bool:
    """Snake-case alias of killprocess()."""
    ...

def process_time(name: str) -> float:
    """Seconds a named path process has run, or -1 when not running."""
    ...

def time_exceeds(name: str, seconds: float) -> bool:
    """Whether a running named process is older than seconds."""
    ...

def follow(target: str, distance: float = 3.0) -> str:
    """Follow any entity, keeping ~distance blocks, until following ENDS.

    target is a player name, an entity type ("zombie"), or a selector
    ("@e[type=cow,distance=..20]"). Blocks until the follow stops (/talos stop,
    another goto taking over, or the target staying gone), then raises
    PathFailedError describing why. Prefer `await talos.aio.follow(...)` inside
    async tasks."""
    ...

def set_node_count(n: int) -> None:
    """Set navigation waypoint count; zero restores automatic path-length scaling."""
    ...

def find_block(name: str, radius: int = 64) -> Optional[Pos]:
    """Nearest matching block (e.g. "minecraft:iron_ore") within radius, or None."""
    ...

def find_entity(entity_type: str, radius: float = 64.0) -> Optional[Entity]:
    """Nearest entity of a registry type (e.g. "minecraft:zombie"), or None."""
    ...

def find_item(item: str, radius: float = 64.0) -> Optional[Entity]:
    """Nearest dropped item of a registry id, or None."""
    ...

def get(name: str, *args: object) -> int | float | bool | str:
    """Read the same 206-trigger/observable catalog as `/talos get`.

    Names may use spaces or underscores. Parameterized triggers take the same subject plus an
    optional radius; `get("entity_location", runtime_id)` returns a 3dp location string.
    """
    ...

def players(radius: float = 128.0) -> list[Player]:
    """All OTHER players within radius, nearest first (never the local player).
    Positions are exact doubles; .distance is feet-to-feet from the local player."""
    ...

def nearest_player(radius: float = 128.0) -> Optional[Player]:
    """The nearest other player within radius, or None."""
    ...

def entities(type: Optional[str] = None, radius: float = 64.0) -> list[Entity]:
    """All entities within radius, nearest first, excluding the local player.
    `type` filters on the exact registry id ("minecraft:zombie"); None = everything."""
    ...

def angle_to(x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> tuple[float, float]:
    """(yaw, pitch) in degrees the player would need to face a point/snapshot,
    computed from the EYES with the same math look_at() uses. Yaw wrapped to
    -180..180 (0 = south, 90 = west); pitch -90 (up) to 90 (down)."""
    ...

def place_block(x: Optional[Union[float, _PosLike]] = ..., y: Optional[float] = ...,
                z: Optional[float] = ..., block_id: Optional[str] = ...) -> str:
    """Place a block. No arguments = place at the crosshair target. With x/y/z,
    places at that position; block_id selects a matching hotbar item first."""
    ...

def place_look() -> str:
    """Place a hotbar block at the block you are currently looking at."""
    ...

def break_block(x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> str:
    """Break a block (at a position or snapshot) and wait for verification."""
    ...

def mine(x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> str:
    """Break the block at a position or snapshot. Alias of break_block."""
    ...

def mine_looking_at() -> str:
    """Mine the crosshair block over multiple ticks, stopping if the target changes."""
    ...

def left_click() -> str:
    """Attack or start breaking the entity/block under the crosshair."""
    ...

def right_click() -> str:
    """Use or interact with the entity/block under the crosshair."""
    ...

def select_slot(n: int) -> None:
    """Select hotbar slot 0 through 8."""
    ...

def click_slot(slot: int, right: bool = False) -> str:
    """Left- or right-click a raw slot in the currently open screen handler."""
    ...

def container_slot_count() -> int:
    """Number of non-player slots exposed by the open container screen."""
    ...

def move_stack(from_slot: int, to_slot: int) -> str:
    """Move a stack between two raw open-screen slots using vanilla pickup clicks."""
    ...

def take_stack(container_slot: int, player_slot: int) -> str:
    """Move a stack from an open container/horse slot into a player screen slot."""
    ...

def inventory() -> list[SlotEntry]:
    """Non-empty stacks in the player's 36 main slots as {slot, id, count}.

    Slot numbers are stable PlayerInventory indices: 0-8 hotbar (what
    select_slot()/selected_slot() use), 9-35 main grid — NOT the raw
    screen-handler indices click_slot()/move_stack() take."""
    ...

def hotbar() -> list[SlotEntry]:
    """The hotbar's non-empty stacks: inventory() entries with slot 0-8."""
    ...

def selected_slot() -> int:
    """The currently selected hotbar slot, 0-8."""
    ...

def count(item_id: str) -> int:
    """Total number of an item across the player's 36 main inventory slots."""
    ...

def has(item_id: str) -> bool:
    """True when at least one of the item sits in the player's main inventory."""
    ...

def find_slot(item_id: str) -> Optional[int]:
    """First PlayerInventory slot index (0-35, see inventory()) holding the item, or None."""
    ...

def container_items() -> list[SlotEntry]:
    """Non-empty stacks in the open container's non-player slots as {slot, id, count}.
    Slot numbers ARE the raw screen-handler indices, usable with click_slot()/move_stack()."""
    ...

def deposit(item_id: str, amount: int) -> int:
    """Move up to `amount` items of a type from the player into the open container.
    Best-effort exact; returns the count ACTUALLY moved (0 when the container is
    full). deposit(t, talos.count(t)) empties you of t. Raises if no container is open."""
    ...

def withdraw(item_id: str, amount: int) -> int:
    """Move up to `amount` items of a type from the open container into the player.
    Mirror of deposit(); returns the count actually moved."""
    ...

def craft(item_id: str, count: int = 1) -> str:
    """Craft `count` results of an OUTPUT item via the recipe book. Needs the
    inventory (2x2) or a crafting table open. 1.21.11 does not sync recipe ids
    to the client, so item_id names the recipe's RESULT item; recipes whose
    ingredients you have are preferred. Raises (with partial progress) when
    ingredients run out."""
    ...

def screen() -> Optional[str]:
    """Name of the open screen ("minecraft:generic_9x3", "minecraft:crafting",
    "minecraft:inventory", a title for other screens), or None when none is open."""
    ...

def close_screen() -> None:
    """Close whatever screen is open; safe when none is."""
    ...

def hud(text: str, id: str = "hud") -> None:
    """Pin a line of text to the on-screen overlay (top-left).

    Repeated calls with the same id update the line in place; different ids
    stack in first-set order (max 20 lines, 256 chars each). Legacy `§` color
    codes work. The overlay is cleared automatically when the script stops."""
    ...

def hud_remove(id: str = "hud") -> None:
    """Remove one overlay line by id; safe when the id was never set."""
    ...

def hud_clear() -> None:
    """Remove every overlay line."""
    ...

def armor_item(slot: str) -> str:
    """Item id equipped in helmet/chestplate/leggings/boots."""
    ...

def equip_armor(from_slot: int, armor_slot: str) -> str:
    """Equip from a raw screen slot when the open handler exposes the armor row."""
    ...

def kill_nearest(radius: float = 6.0) -> str:
    """Kill the nearest hostile entity and wait for verification.
    Raises NotFoundError if nothing hostile is in range."""
    ...

def look_at(x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> None:
    """Turn the player toward a world-space point (or a snapshot's position)."""
    ...

def player_pos() -> Pos:
    """The player's current eye position snapshot."""
    ...

def player_feet() -> Pos:
    """The player's feet (bottom-center) position — the block-space coordinate."""
    ...

def key(name: str, pressed: bool = True) -> None:
    """Hold (or release) one of the player's logical keys, like a physical press.

    Names: forward, back, left, right, jump, sneak, sprint, attack, use. These
    are LOGICAL keybindings, not raw key codes, so user rebinds don't matter.
    key(name, True) HOLDS the key until key(name, False) or release_keys(), so
    pair every press with a release (try/finally). For a single one-tick press,
    use tap(name) instead."""
    ...

def tap(name: str) -> str:
    """Press a logical key for exactly ONE game tick, then release it.

    The scripted equivalent of a quick physical tap — jump over a lip, flick
    sneak. Same names as key(); returns once the key has been released, so no
    cleanup is ever needed."""
    ...

def release_keys(*names: Union[str, list[str], tuple[str, ...]]) -> None:
    """Release keys held by key(). With no arguments, releases ALL of them.

    With names, releases only those, e.g. release_keys("forward", "jump")
    keeps a held "sneak" pressed. A single list/tuple also works:
    release_keys(["forward", "jump"]). Safe to call unconditionally."""
    ...

def look(yaw: float, pitch: float) -> None:
    """Snap the view to absolute yaw/pitch degrees (same convention as look_angle()).

    Yaw 0 = south, 90 = west; pitch -90 (up) to 90 (down). For smooth turning,
    step the yaw a few degrees toward the target each tick."""
    ...

def look_angle() -> tuple[float, float]:
    """Current view rotation as (yaw, pitch) in degrees.

    Yaw is wrapped to -180..180 (0 = south, 90 = west); pitch is -90 (up) to 90 (down)."""
    ...

def looking_at() -> Optional[Pos]:
    """The block cell under the crosshair, or None (air, entity, out of reach).

    Truthy like find_block: `if talos.looking_at(): ...`"""
    ...

def block_at(x: Union[float, str, _PosLike], y: Union[float, str] = ..., z: Union[float, str] = ...) -> str:
    """Block id at a cell, e.g. "minecraft:stone" ("minecraft:air" if empty)."""
    ...

def local(left: float, up: float, forward: float) -> Pos:
    """World Pos of the caret coordinate ^left ^up ^forward (look-relative, from
    the eyes). local(0, 0, 5) is 5 blocks ahead; same frame as goto("^ ^ 5")."""
    ...

def ahead(distance: float) -> Pos:
    """World Pos `distance` blocks along the gaze — local(0, 0, distance). Follows
    pitch; use move_ahead() to actually walk forward on the horizontal."""
    ...

def raytrace(max_distance: float = ...) -> Optional[Hit]:
    """Cast a ray from the eyes along the look; the first block/entity Hit, or None.
    The entity-aware, sub-block-precise version of looking_at()."""
    ...

def raytrace_if(block: Optional[str] = ..., entity: Optional[str] = ..., max_distance: float = ...) -> bool:
    """True when the first thing the look ray hits matches. Pass exactly one of
    block="minecraft:stone" or entity="zombie"."""
    ...

def move_ahead(distance: float) -> str:
    """Walk `distance` blocks forward along your horizontal heading (pitch ignored)
    and wait for arrival. Raises PathFailedError if unreachable."""
    ...

def input(prompt: str = ...) -> str:
    """Ask the user for input via chat and block until they answer.

    The user's next plain chat message is captured (it never reaches the server)
    and returned. Commands ("/...") are not captured, so /talos still works."""
    ...

def on_edge(margin: float = 0.3) -> bool:
    """True when the feet stand within `margin` of a block boundary on X or Z.

    The player hitbox half-width is 0.3, so the default means part of the hitbox
    overhangs the neighboring cell. Combine with block_at() to detect a drop."""
    ...

# --- events / misc ------------------------------------------------------------

def command(name: str) -> Callable[[_F], _F]:
    """Decorator registering a handler for `/talos <name> ...` while this script
    session runs. If `name` matches a built-in subcommand (goto, mine, place,
    kill), the chat command is forwarded here INSTEAD of the built-in — which
    stays reachable as talos.goto(...) etc., so an override can wrap the
    original. Other names are invoked via `/talos cmd <name> [args]`.

    The handler receives the argument text split on whitespace (list[str]) and
    runs on the script worker. Returning a coroutine (an `async def` handler)
    starts it as a task so it can await talos.aio actions.

        @talos.command("pygoto")
        async def pygoto(args: list[str]) -> None:
            x, y, z = (int(a) for a in args)
            await talos.aio.goto(x, y, z)
    """
    ...

def on(event: str) -> Callable[[_EventHandler], _EventHandler]:
    """Decorator registering a handler for a raw game event.

    Events and handler signatures:
      "tick"        fn()                     | "chat"       fn(message, sender)
      "entity_hurt" fn(type_id, id, x, y, z) | "health"     fn(health)
      "death"       fn()                     | "item_pickup" fn(item_id, amount)
      "goto_start"  fn(x, y, z)              | "goto_done"  fn(success, detail)
      "goto_stuck"  fn(detail)               | "disconnect" fn()

    "chat" sender is the player's name, or None for system lines; your own
    messages echo back, so guard against replying to yourself."""
    ...

def log(message: object, level: str = "info") -> str:
    """Write a leveled message to the Talos log and script console."""
    ...

def debug(message: object) -> str:
    """Write a debug message when detailed logging is enabled."""
    ...

def info(message: object) -> str:
    """Write an info message."""
    ...

def warn(message: object) -> str:
    """Write a warning message."""
    ...

def error(message: object) -> str:
    """Write an error message."""
    ...

@overload
def debug_mode() -> bool: ...
@overload
def debug_mode(enabled: bool) -> None: ...
def debug_mode(enabled: bool | None = None):
    """Toggle or query detailed engine/script debug logging."""
    ...

def chat(message: object) -> str:
    """Send a chat message to the server; a leading "/" runs it as a command.

    Own messages echo back into the "chat" event -- guard handlers against loops."""
    ...

def run_command(command: object) -> str:
    """Run a command (leading "/" optional): /talos client commands dispatch
    locally, anything else is sent to the server. Distinct from
    @talos.command(name), which registers a /talos subcommand."""
    ...

args: list[str]
"""Arguments from `/talos script run <name> <args...>`, whitespace-split.

Empty for VS Code / snippet runs. Example: `/talos script run farm wheat 64`
gives ["wheat", "64"]."""

def require(name: str) -> Any:
    """Load another script from talos/scripts as a module and return it.

    require("mylib") reads talos/scripts/mylib.py, executes it once, and caches
    it (CPython import semantics). Libraries run in the same sandbox as scripts,
    go through the same first-run trust summary, and reload fresh on every
    script (re)run. Only files directly in talos/scripts can be required."""
    ...

def wait(a: float, b: Optional[float] = None) -> None:
    """Sleep for `a` seconds, or a seeded random duration in [a, b] seconds.

    wait(0.4) sleeps exactly 0.4 seconds; wait(0.4, 0.9) sleeps a humanized
    random duration in that range. Blocks every task; inside async tasks
    prefer `await talos.aio.wait(...)`."""
    ...

def wait_between(a: float, b: float) -> None:
    """Blocking humanized sleep for a random duration in [a, b] seconds.
    Alias of wait(a, b) — see talos.wait."""
    ...

def set_profile(name: str) -> None:
    """Set the humanization profile: raw, natural, or paranoid."""
    ...

def set_seed(n: int) -> None:
    """Seed script-side humanized randomness for reproducible runs."""
    ...

@overload
def human() -> bool: ...
@overload
def human(enabled: bool) -> None: ...
def human(enabled: bool | None = None):
    """Toggle or query session-arc "Human mode".

    human(True)/human(False) enables/disables it; human() returns the current
    bool. When on, a wall-clock fatigue model drifts the active profile over
    the session (slower/wider reactions, looser aim, wider path wobble) plus
    idle micro-breaks that pause pathing. Best-effort obfuscation of the
    long-session statistical fingerprint — not a guarantee of undetectability,
    and it never makes wrong-target mistakes (only motor-level ones)."""
    ...

def fatigue() -> float:
    """Current session-arc fatigue in [0.0, 1.0] (0 when Human mode is off/fresh)."""
    ...

def on_break() -> bool:
    """True while a Human-mode micro-break is pausing automation."""
    ...

def spawn(callable: Callable[[], Any]) -> Any:
    """Run a function in an ISOLATED concurrent script session (separate
    interpreter, no shared state). For shared-state concurrency use @talos.task."""
    ...

def parallel(*callables: Union[Callable[[], Any], Iterable[Callable[[], Any]]]) -> list[Any]:
    """Run functions in isolated sessions concurrently and block until all finish.
    Accepts separate arguments or a single list/tuple: parallel(a, b) == parallel([a, b])."""
    ...

# --- persistent state -----------------------------------------------------------

class _State:
    """Dict-like storage that survives restarts: talos.state["key"] = value.

    Values must be JSON-serializable (str/int/float/bool/None/list/dict) —
    anything else raises TypeError on assignment. The whole mapping is capped at
    256KB serialized (ValueError beyond it, mutation rolled back). Contents live
    ONLY at <gameDir>/talos/state/<script>.json, named after the running script;
    no paths ever cross the boundary and no other file APIs exist. Every mutation
    persists immediately; save() forces a write anyway."""
    def __getitem__(self, key: str) -> Any: ...
    def __setitem__(self, key: str, value: Any) -> None: ...
    def __delitem__(self, key: str) -> None: ...
    def __contains__(self, key: str) -> bool: ...
    def __len__(self) -> int: ...
    def __iter__(self) -> Iterator[str]: ...
    def get(self, key: str, default: Any = None) -> Any: ...
    def setdefault(self, key: str, default: Any = None) -> Any: ...
    def keys(self) -> Iterable[str]: ...
    def values(self) -> Iterable[Any]: ...
    def items(self) -> Iterable[tuple[str, Any]]: ...
    def pop(self, key: str, *default: Any) -> Any: ...
    def update(self, mapping: Union[dict[str, Any], Iterable[tuple[str, Any]]]) -> None: ...
    def clear(self) -> None: ...
    def save(self) -> None:
        """Persist the current contents now (mutations already save automatically)."""
        ...

state: _State
