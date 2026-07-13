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

from typing import Any, Awaitable, Callable, Coroutine, Iterator, Optional, TypeVar, Union

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
    """An entity snapshot: .uuid, .type (registry id like "minecraft:zombie"), .pos."""
    uuid: str
    type: str
    pos: Pos

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
    async def goto(self, x: Union[float, _PosLike], y: float = ..., z: float = ...) -> str: ...
    async def goto_near(self, x: float, y: float, z: float, range: int) -> str: ...
    async def goto_xz(self, x: float, z: float) -> str: ...
    async def find_block(self, name: str, radius: int = 64) -> Optional[Pos]: ...
    async def place_block(self, x: Union[float, _PosLike], y: float = ..., z: float = ...,
                          block_id: Optional[str] = ...) -> str: ...
    async def break_block(self, x: Union[float, _PosLike], y: float = ..., z: float = ...) -> str: ...
    async def mine(self, x: Union[float, _PosLike], y: float = ..., z: float = ...) -> str: ...
    async def mine_looking_at(self) -> str: ...
    async def kill_nearest(self, radius: float = 6.0) -> str: ...
    async def wait_between(self, a: float, b: float) -> None: ...

aio: _Aio

# --- sync actions ----------------------------------------------------------------

def goto(x: Union[float, _PosLike], y: float = ..., z: float = ...) -> str:
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

def place_block(x: Optional[Union[float, _PosLike]] = ..., y: Optional[float] = ...,
                z: Optional[float] = ..., block_id: Optional[str] = ...) -> str:
    """Place a block. No arguments = place at the crosshair target. With x/y/z,
    places at that position; block_id selects a matching hotbar item first."""
    ...

def place_look() -> str:
    """Place a hotbar block at the block you are currently looking at."""
    ...

def break_block(x: Union[float, _PosLike], y: float = ..., z: float = ...) -> str:
    """Break a block (at a position or snapshot) and wait for verification."""
    ...

def mine(x: Union[float, _PosLike], y: float = ..., z: float = ...) -> str:
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

def look_at(x: Union[float, _PosLike], y: float = ..., z: float = ...) -> None:
    """Turn the player toward a world-space point (or a snapshot's position)."""
    ...

def player_pos() -> Pos:
    """The player's current eye position snapshot."""
    ...

# --- events / misc ------------------------------------------------------------

def on(event: str) -> Callable[[_EventHandler], _EventHandler]:
    """Decorator registering a handler for a raw game event:
    "tick", "chat", "entity_hurt", or "disconnect"."""
    ...

def log(msg: object) -> str:
    """Write a message to the script console (in-game chat / VS Code output)."""
    ...

def wait_between(a: float, b: float) -> None:
    """Blocking humanized sleep for a random duration in [a, b] seconds.
    Inside async tasks prefer `await talos.aio.wait_between(a, b)`."""
    ...

def set_profile(name: str) -> None:
    """Set the humanization profile: raw, natural, or paranoid."""
    ...

def set_seed(n: int) -> None:
    """Seed script-side humanized randomness for reproducible runs."""
    ...

def spawn(callable: Callable[[], Any]) -> Any:
    """Run a function in an ISOLATED concurrent script session (separate
    interpreter, no shared state). For shared-state concurrency use @talos.task."""
    ...

def parallel(*callables: Callable[[], Any]) -> list[Any]:
    """Run functions in isolated sessions concurrently and block until all finish."""
    ...
