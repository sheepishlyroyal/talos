"""Talos's curated embedded API. pip, native packages, host classes, IO, and environment access are unavailable."""

from .errors import (TalosError, PathFailedError, OutOfReachError, NotFoundError,
                     TargetLostError, ActionCancelledError, WorldClosedError)
from .actions import (goto, goto_near, goto_xz, set_node_count, find_block, find_entity,
                      find_item, place_block, place_look, break_block, mine, mine_looking_at,
                      left_click, right_click, select_slot, click_slot, container_slot_count,
                      move_stack, take_stack, armor_item, equip_armor, kill_nearest, look_at,
                      player_pos, player_feet, key, release_keys, look, look_angle,
                      looking_at, block_at, on_edge, input, Pos, Entity)
from .engine import (on_start, on_tick, task, start, run, cancel_all, sleep, ticks,
                     next_tick, tick_count, aio, TaskHandle, command)
from .events import on
from .humanize import wait_between, set_profile, set_seed

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
    """Run callables concurrently, block until all finish, and return their results in order."""
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

__all__ = ["goto", "goto_near", "goto_xz", "set_node_count", "find_block", "find_entity",
           "find_item", "place_block", "place_look", "break_block", "mine", "mine_looking_at",
           "left_click", "right_click", "select_slot", "click_slot", "container_slot_count",
           "move_stack", "take_stack", "armor_item", "equip_armor", "kill_nearest", "look_at",
           "player_pos", "player_feet", "key", "release_keys", "look", "look_angle",
           "looking_at", "block_at", "on_edge", "input", "Pos", "Entity", "log",
           "wait_between", "set_profile", "set_seed", "on", "parallel", "spawn", "command",
           "on_start", "on_tick", "task", "start", "run", "cancel_all",
           "sleep", "ticks", "next_tick", "tick_count", "aio", "TaskHandle",
           "TalosError", "PathFailedError", "OutOfReachError", "NotFoundError",
           "TargetLostError", "ActionCancelledError", "WorldClosedError"]
