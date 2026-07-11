"""Glade's curated embedded API. pip, native packages, host classes, IO, and environment access are unavailable."""

from .actions import (goto, goto_near, goto_xz, find_block, find_entity, find_item,
                      place_block, break_block, kill_nearest, look_at, player_pos)
from .events import on
from .humanize import wait_between, set_profile, set_seed

def log(message):
    """Write a message to the Glade log."""
    return _glade_host.log(str(message))

__all__ = ["goto", "goto_near", "goto_xz", "find_block", "find_entity", "find_item",
           "place_block", "break_block", "kill_nearest", "look_at", "player_pos", "log",
           "wait_between", "set_profile", "set_seed", "on"]
