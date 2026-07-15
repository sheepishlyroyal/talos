"""Talos's curated embedded API. pip, native packages, host classes, IO, and environment access are unavailable."""

from .errors import (TalosError, PathFailedError, OutOfReachError, NotFoundError,
                     TargetLostError, ActionCancelledError, WorldClosedError)
from .actions import (goto, goto_near, goto_xz, goto_block, follow, set_node_count,
                      find_block, find_entity, get,
                      find_item, players, nearest_player, entities, angle_to,
                      place_block, place_look, break_block, mine, mine_looking_at,
                      left_click, right_click, select_slot, click_slot, container_slot_count,
                      move_stack, take_stack, armor_item, equip_armor, kill_nearest, look_at,
                      player_pos, player_feet, key, tap, release_keys, look, look_angle,
                      looking_at, block_at, local, ahead, raytrace, raytrace_if, move_ahead,
                      on_edge, input, inventory, hotbar, selected_slot,
                      count, has, find_slot, container_items, deposit, withdraw, craft,
                      screen, close_screen, hud, hud_remove, hud_clear, Pos, Entity, Player, Hit)
from .engine import (on_start, on_tick, task, every, start, run, cancel_all, sleep, ticks,
                     next_tick, tick_count, aio, TaskHandle, command, state)
from .events import on
from .humanize import wait, wait_between, set_profile, set_seed, human, fatigue, on_break

import base64 as _base64
import marshal as _marshal
import pickle as _pickle
import time as _time
import types as _types


def _callable_payload(function):
    if not isinstance(function, _types.FunctionType):
        raise TypeError("talos.spawn() requires a Python function")
    referenced = {}
    modules = {}
    for name in function.__code__.co_names:
        if name not in function.__globals__ or name in {"talos", "__builtins__"}:
            continue
        value = function.__globals__[name]
        if isinstance(value, _types.ModuleType):
            modules[name] = value.__name__
            continue
        try:
            _pickle.dumps(value)
        except Exception as error:
            raise TypeError(f"global {name!r} captured by {function.__name__} is not serializable") from error
        referenced[name] = value
    closure = tuple(cell.cell_contents for cell in (function.__closure__ or ()))
    data = {
        "code": _marshal.dumps(function.__code__),
        "name": function.__name__,
        "globals": referenced,
        "modules": modules,
        "defaults": function.__defaults__,
        "kwdefaults": function.__kwdefaults__,
        "closure": closure,
    }
    try:
        return _base64.b64encode(_pickle.dumps(data)).decode("ascii")
    except Exception as error:
        raise TypeError(f"values captured by {function.__name__} are not serializable") from error


class _SpawnHandle:
    def __init__(self, host_handle):
        self._host_handle = host_handle

    def stop(self):
        """Request immediate cancellation of this concurrent script part."""
        self._host_handle.stop()

    def join(self):
        """Block until this part finishes and return its result, or re-raise its failure."""
        encoded = self._host_handle.join()
        return _pickle.loads(_base64.b64decode(encoded))

    @property
    def running(self):
        """Whether this script part is still running."""
        return bool(self._host_handle.isRunning())


def spawn(callable):
    """Start callable in an isolated concurrent script session and return a stoppable handle."""
    return _SpawnHandle(_talos_concurrency.spawn(_callable_payload(callable)))


def parallel(*callables):
    """Run callables concurrently, block until all finish, and return their results in order.

    Accepts separate arguments or a single list/tuple: parallel(a, b) and
    parallel([a, b]) are equivalent.
    """
    if len(callables) == 1 and isinstance(callables[0], (list, tuple)):
        callables = tuple(callables[0])
    handles = []
    try:
        handles = [spawn(function) for function in callables]
        results = [None] * len(handles)
        pending = set(range(len(handles)))
        while pending:
            finished = [index for index in pending if not handles[index].running]
            if not finished:
                _time.sleep(0.01)
                continue
            for index in finished:
                results[index] = handles[index].join()
                pending.remove(index)
        return results
    except BaseException:
        for handle in handles:
            if handle.running:
                handle.stop()
        raise

def log(message):
    """Write a message to the Talos log and the script console (stdout)."""
    text = str(message)
    _talos_host.log(text)
    # Printed (not just logged host-side) so this reaches whatever LogSink the running
    # script was started with -- by default the in-game chat. See ScriptEngine.CHAT.
    print(text)
    return text

def require(name):
    """Load another script from talos/scripts as a module and return it.

    require("mylib") reads talos/scripts/mylib.py, executes it once, and caches
    it (CPython import semantics: repeat calls return the same module object,
    and during an import cycle the partially-initialized module is returned).
    Libraries run in the same sandbox as scripts, go through the same first-run
    trust summary, and reload fresh on every script (re)run. Only files directly
    in talos/scripts can be required -- no paths, no traversal.
    """
    import sys
    stem = str(name)
    stem = stem[:-3] if stem.endswith(".py") else stem
    key = "taloslib." + stem
    cached = sys.modules.get(key)
    if cached is not None:
        return cached
    source = _talos_host.readScriptSource(stem)
    module = _types.ModuleType(key)
    module.__file__ = "talos/scripts/" + stem + ".py"
    module.__package__ = ""
    module.__dict__["_talos_host"] = _talos_host
    sys.modules[key] = module
    try:
        exec(compile(source, module.__file__, "exec"), module.__dict__)
    except BaseException:
        sys.modules.pop(key, None)
        raise
    return module


def __getattr__(name):
    # talos.args: arguments from `/talos script run <name> <args...>` as a list of
    # strings (empty for VS Code / snippet runs). Resolved lazily so it is always
    # current for the run that is executing, even when the session context is reused.
    if name == "args":
        return [str(value) for value in _talos_host.scriptArgs()]
    raise AttributeError(f"module 'talos' has no attribute {name!r}")

__all__ = ["args", "require",
           "goto", "goto_near", "goto_xz", "goto_block", "follow",
           "set_node_count", "find_block", "find_entity", "get",
           "find_item", "players", "nearest_player", "entities", "angle_to",
           "place_block", "place_look", "break_block", "mine", "mine_looking_at",
           "left_click", "right_click", "select_slot", "click_slot", "container_slot_count",
           "move_stack", "take_stack", "armor_item", "equip_armor", "kill_nearest", "look_at",
           "player_pos", "player_feet", "key", "tap", "release_keys", "look", "look_angle",
           "looking_at", "block_at", "local", "ahead", "raytrace", "raytrace_if", "move_ahead",
           "on_edge", "input", "inventory", "hotbar", "selected_slot",
           "count", "has", "find_slot", "container_items", "deposit", "withdraw", "craft",
           "screen", "close_screen", "hud", "hud_remove", "hud_clear",
           "Pos", "Entity", "Player", "Hit", "log",
           "wait", "wait_between", "set_profile", "set_seed", "human", "fatigue", "on_break",
           "on", "parallel", "spawn", "command",
           "on_start", "on_tick", "task", "every", "start", "run", "cancel_all",
           "sleep", "ticks", "next_tick", "tick_count", "aio", "TaskHandle", "state",
           "TalosError", "PathFailedError", "OutOfReachError", "NotFoundError",
           "TargetLostError", "ActionCancelledError", "WorldClosedError"]
