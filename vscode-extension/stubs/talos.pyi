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

   (copy or symlink this `stubs/` directory there, or point directly at the
   extension's bundled copy).

This file has no runtime effect on the mod — it is purely for editor
autocomplete/type-checking via Pylance/Pyright.
"""

from typing import Callable, NamedTuple, Optional, TypeVar

class Pos(NamedTuple):
    """An integer block position in the world."""
    x: int
    y: int
    z: int

_EventHandler = TypeVar("_EventHandler", bound=Callable[..., None])

def goto(x: int, y: int, z: int) -> bool:
    """
    Path/walk the player to the given block coordinates.

    Returns True if the destination was reached, False if pathing failed
    or was interrupted (e.g. by `stop`).
    """
    ...

def find_block(name: str, radius: int = 64) -> Optional[Pos]:
    """
    Search outward from the player for the nearest block matching `name`
    (e.g. "minecraft:iron_ore") within `radius` blocks.

    Returns the block's position, or None if nothing was found in range.
    """
    ...

def place_block(name: str, x: int, y: int, z: int) -> bool:
    """
    Place a block of type `name` at the given coordinates, using an
    instance from the player's inventory if required.

    Returns True on success.
    """
    ...

def break_block(x: int, y: int, z: int) -> bool:
    """
    Break the block at the given coordinates.

    Returns True on success.
    """
    ...

def kill_nearest(radius: float = 6.0) -> bool:
    """
    Attack the nearest hostile mob within `radius` blocks until it dies or
    it's no longer reachable.

    Returns True if a mob was killed.
    """
    ...

def wait_between(a: float, b: float) -> None:
    """
    Sleep the script for a random duration in seconds between `a` and `b`
    (inclusive). Useful for humanizing timing between actions.
    """
    ...

def look_at(x: float, y: float, z: float) -> None:
    """Rotate the player's view to face the given world coordinates."""
    ...

def player_pos() -> Pos:
    """Return the player's current block position."""
    ...

def log(msg: str) -> None:
    """
    Emit a line of text to the Talos output channel in VS Code (streamed
    over the WebSocket as a `log` frame at info level).
    """
    ...

def set_profile(name: str) -> None:
    """
    Switch the active behavior profile (e.g. movement/combat tuning
    presets) by name.
    """
    ...

def set_seed(n: int) -> None:
    """Seed the script's random number generator for reproducible runs."""
    ...

def on(event: str) -> Callable[[_EventHandler], _EventHandler]:
    """
    Decorator that registers a handler for a named game event
    (e.g. "damaged", "chat_message", "tick").

    Example:
        @talos.on("damaged")
        def handle_damage(source):
            talos.log(f"Took damage from {source}")
    """
    ...
