"""Typed errors raised by talos actions, for use with try/except.

Every action failure raises a subclass of TalosError, so scripts can react
to specific failure modes instead of parsing messages:

    try:
        talos.goto(ore)
        talos.mine(ore)
    except talos.OutOfReachError:
        talos.log("too far - repathing")
    except talos.PathFailedError as error:
        talos.log(f"stuck: {error}")
"""


class TalosError(Exception):
    """Base class for every error raised by talos actions."""


class PathFailedError(TalosError):
    """Pathfinding could not reach the goal (blocked, timed out, or no route)."""


class OutOfReachError(TalosError):
    """The target exists but is too far away to interact with."""


class NotFoundError(TalosError):
    """No matching block, entity, item, or crosshair target was found."""


class TargetLostError(TalosError):
    """The target changed or disappeared mid-action (block replaced, crosshair moved)."""


class ActionCancelledError(TalosError):
    """The action or task was cancelled, or the script session is stopping."""


class WorldClosedError(TalosError):
    """No usable world/player (left the world, disconnected, or session invalidated)."""


# Checked in order; the first needle contained in the lowercased host message wins.
_RULES = (
    (PathFailedError, ("path failed", "no path", "pathing unavailable")),
    (OutOfReachError, ("out of reach", "too far")),
    (ActionCancelledError, ("cancelled", "stopping", "stopped")),
    (WorldClosedError, ("invalidated", "no active client world", "no active player", "disconnect")),
    (TargetLostError, ("target changed", "left the mining target", "changed unexpectedly",
                       "already air", "no longer", "target is gone")),
    (NotFoundError, ("no hostile entity", "unknown", "not found", "not looking at", "did not hit")),
)


def _clean(text):
    """Drop leading java exception class names: 'java.lang.IllegalStateException: msg' -> 'msg'."""
    while True:
        head, sep, rest = text.partition(": ")
        if sep and rest and " " not in head and ("." in head or head.endswith(("Exception", "Error"))):
            text = rest
            continue
        return text


def map_error(source):
    """Convert a host-side failure into the matching TalosError subclass."""
    text = _clean(str(source)) if source is not None else ""
    low = text.lower()
    for kind, needles in _RULES:
        for needle in needles:
            if needle in low:
                return kind(text)
    return TalosError(text)


def call(fn, *args):
    """Invoke a host bridge function, translating failures into TalosError types."""
    try:
        return fn(*args)
    except TalosError:
        raise
    except (KeyboardInterrupt, SystemExit):
        raise
    except BaseException as error:
        raise map_error(error) from None
