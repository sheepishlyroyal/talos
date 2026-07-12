"""Glade's curated embedded API. pip, native packages, host classes, IO, and environment access are unavailable."""

from .actions import (goto, goto_near, goto_xz, find_block, find_entity, find_item,
                      place_block, break_block, kill_nearest, look_at, player_pos)
from .events import on
from .humanize import wait_between, set_profile, set_seed

def log(message):
    """Write a message to the Glade log and the script console (stdout)."""
    text = str(message)
    _glade_host.log(text)
    # Printed (not just logged host-side) so this reaches whatever LogSink the running
    # script was started with -- by default the in-game chat. See ScriptEngine.CHAT.
    print(text)
    return text

__all__ = ["goto", "goto_near", "goto_xz", "find_block", "find_entity", "find_item",
           "place_block", "break_block", "kill_nearest", "look_at", "player_pos", "log",
           "wait_between", "set_profile", "set_seed", "on"]
