"""Action and query wrappers. Values returned by the host are immutable snapshots.

Failures raise typed talos errors (see talos.errors) so scripts can try/except.
Positional actions accept either explicit coordinates or any snapshot with a
position: goto(x, y, z), goto(pos), and goto(entity) all work.
"""

import math as _math

_errors = __import__("sys").modules["talos.errors"]
_call = _errors.call


class Pos:
    """A world position snapshot with plain .x/.y/.z attributes.

    `pos.pos` is the position itself, so find results and their positions can
    be passed interchangeably to goto()/mine()/look_at()/etc.
    """
    __slots__ = ("x", "y", "z")

    def __init__(self, x, y, z):
        self.x = float(x)
        self.y = float(y)
        self.z = float(z)

    @property
    def pos(self):
        return self

    def __iter__(self):
        yield self.x
        yield self.y
        yield self.z

    def __repr__(self):
        return f"Pos(x={self.x:g}, y={self.y:g}, z={self.z:g})"


class Entity:
    """An entity snapshot: .uuid, .type (registry id), and .pos."""
    __slots__ = ("uuid", "type", "pos")

    def __init__(self, uuid, type, pos):
        self.uuid = uuid
        self.type = type
        self.pos = pos

    def __repr__(self):
        return f"Entity(type={self.type!r}, pos={self.pos!r})"


def _axis(snapshot, name):
    value = getattr(snapshot, name)
    return value() if callable(value) else value


def _wrap_pos(raw):
    return None if raw is None else Pos(_axis(raw, "x"), _axis(raw, "y"), _axis(raw, "z"))


def _wrap_entity(raw):
    return None if raw is None else Entity(_axis(raw, "uuid"), _axis(raw, "type"),
                                           _wrap_pos(_axis(raw, "pos")))


def _token_offset(token):
    """Numeric tail of a '~'/'^' token: '~' -> 0, '~2.5' -> 2.5."""
    tail = token.strip()[1:]
    return float(tail) if tail else 0.0


def _resolve_relative(x, y, z):
    """Resolve Minecraft-style '~' (player-relative) and '^' (look-relative) tokens.

    '~ ~ ~' anchors at the player's FEET, exactly like vanilla commands.
    '^left ^up ^forward' anchors at the player's EYES and follows the gaze, so
    block_at("^", "^", "^3") is the cell three blocks along your crosshair line.
    Returns None when no component is a string token.
    """
    tokens = (x, y, z)
    caret = tuple(isinstance(t, str) and t.strip().startswith("^") for t in tokens)
    tilde = tuple(isinstance(t, str) and t.strip().startswith("~") for t in tokens)
    if not any(caret) and not any(tilde):
        return None
    if any(caret):
        if not all(caret):
            raise ValueError("cannot mix ^ with ~ or absolute coordinates")
        eye = player_pos()
        yaw, pitch = look_angle()
        yaw_r = _math.radians(yaw)
        pitch_r = _math.radians(pitch)
        sin_yaw, cos_yaw = _math.sin(yaw_r), _math.cos(yaw_r)
        sin_pitch, cos_pitch = _math.sin(pitch_r), _math.cos(pitch_r)
        forward = (-sin_yaw * cos_pitch, -sin_pitch, cos_yaw * cos_pitch)
        up = (-sin_yaw * sin_pitch, cos_pitch, cos_yaw * sin_pitch)
        left = (up[1] * forward[2] - up[2] * forward[1],
                up[2] * forward[0] - up[0] * forward[2],
                up[0] * forward[1] - up[1] * forward[0])
        lx, ly, lz = (_token_offset(t) for t in tokens)
        return (eye.x + left[0] * lx + up[0] * ly + forward[0] * lz,
                eye.y + left[1] * lx + up[1] * ly + forward[1] * lz,
                eye.z + left[2] * lx + up[2] * ly + forward[2] * lz)
    feet = player_feet()
    base = (feet.x, feet.y, feet.z)
    return tuple(b + _token_offset(t) if is_tilde else float(t)
                 for t, b, is_tilde in zip(tokens, base, tilde))


def _coords(x, y=None, z=None):
    """Accept (x, y, z) numbers, '~'/'^' tokens, '~ ~1 ~' strings, or a snapshot."""
    if isinstance(x, str) and y is None and z is None:
        parts = x.split()
        if len(parts) == 3:
            x, y, z = parts
        else:
            raise ValueError("a single coordinate string needs three parts, e.g. '~ ~1 ~'")
    if y is None and z is None:
        p = getattr(x, "pos", x)
        if callable(p):
            p = p()
        return _axis(p, "x"), _axis(p, "y"), _axis(p, "z")
    if y is None or z is None:
        raise ValueError("expected all of x, y, z (or a single position)")
    relative = _resolve_relative(x, y, z)
    if relative is not None:
        return relative
    return float(x), float(y), float(z)


def goto(x, y=None, z=None):
    """Path to an exact block position (or a snapshot's position) and wait for arrival."""
    x, y, z = _coords(x, y, z)
    return _call(_talos_host.gotoBlock, int(x), int(y), int(z))

def goto_near(x, y, z, range):
    """Path to within range blocks of a position."""
    return _call(_talos_host.gotoNear, int(x), int(y), int(z), int(range))

def goto_xz(x, z):
    """Path to an X/Z column."""
    return _call(_talos_host.gotoXZ, int(x), int(z))

def set_node_count(n):
    """Set navigation waypoint count; zero restores automatic path-length scaling."""
    return _call(_talos_host.setNodeCount, int(n))

def find_block(name, radius=64):
    """Return the nearest matching block position snapshot, or None."""
    return _wrap_pos(_call(_talos_host.findBlock, str(name), int(radius)))

def find_entity(entity_type, radius=64.0):
    """Return the nearest entity-type snapshot, or None."""
    return _wrap_entity(_call(_talos_host.findEntity, str(entity_type), float(radius)))

def find_item(item, radius=64.0):
    """Return the nearest dropped-item snapshot, or None."""
    return _wrap_entity(_call(_talos_host.findItem, str(item), float(radius)))

def place_block(x=None, y=None, z=None, block_id=None):
    """Place a block.

    With no coordinates, places against the block you are currently looking at
    (the crosshair target), like a right-click. With x/y/z, places at that position;
    if block_id (e.g. "minecraft:diamond_ore") is given, a matching hotbar item is
    selected first.
    """
    if x is None and y is None and z is None:
        return _call(_talos_host.placeLook)
    if x is not None and y is None and z is None:
        x, y, z = _coords(x)
    if x is None or y is None or z is None:
        raise ValueError("place_block needs all of x, y, z (or none, to place where looking)")
    if block_id is None:
        return _call(_talos_host.placeBlock, int(x), int(y), int(z))
    return _call(_talos_host.placeBlockAs, int(x), int(y), int(z), str(block_id))


def place_look():
    """Place a hotbar block at the block you are currently looking at (crosshair target)."""
    return _call(_talos_host.placeLook)

def break_block(x, y=None, z=None):
    """Break a block (at a position or snapshot) and wait for verification."""
    x, y, z = _coords(x, y, z)
    return _call(_talos_host.breakBlock, int(x), int(y), int(z))

def mine(x, y=None, z=None):
    """Break the block at a position or snapshot. Alias of break_block."""
    return break_block(x, y, z)

def mine_looking_at():
    """Mine the crosshair block over multiple ticks, stopping if the target changes."""
    return _call(_talos_host.mineLookingAt)

def left_click():
    """Attack or start breaking the entity/block under the crosshair."""
    return _call(_talos_host.leftClick)

def right_click():
    """Use or interact with the entity/block under the crosshair."""
    return _call(_talos_host.rightClick)

def select_slot(n):
    """Select hotbar slot 0 through 8."""
    return _call(_talos_host.hotbarSelect, int(n))

def click_slot(slot, right=False):
    """Left- or right-click a raw slot in the currently open screen handler."""
    return _call(_talos_host.clickSlot, int(slot), bool(right))

def container_slot_count():
    """Return the number of non-player slots exposed by the open container screen."""
    return _call(_talos_host.containerSlotCount)

def move_stack(from_slot, to_slot):
    """Move a stack between two raw open-screen slots using vanilla pickup clicks."""
    return _call(_talos_host.moveStack, int(from_slot), int(to_slot))

def take_stack(container_slot, player_slot):
    """Move a stack from an open container/horse slot into a player screen slot."""
    return _call(_talos_host.takeStack, int(container_slot), int(player_slot))

def armor_item(slot):
    """Return the item id equipped in helmet/chestplate/leggings/boots."""
    return _call(_talos_host.armorItem, str(slot))

def equip_armor(from_slot, armor_slot):
    """Equip from a raw screen slot when the open handler exposes the armor row."""
    return _call(_talos_host.equipArmor, int(from_slot), str(armor_slot))

def kill_nearest(radius=6.0):
    """Kill the nearest hostile entity and wait for verification."""
    return _call(_talos_host.killNearest, float(radius))

def look_at(x, y=None, z=None):
    """Turn the player toward a world-space point (or a snapshot's position)."""
    x, y, z = _coords(x, y, z)
    return _call(_talos_host.lookAt, float(x), float(y), float(z))

def player_pos():
    """Return the player's EYE position snapshot (use player_feet() for the body)."""
    return _wrap_pos(_call(_talos_host.playerPos))

def player_feet():
    """Return the player's feet (bottom-center) position — the block-space coordinate."""
    return _wrap_pos(_call(_talos_host.playerFeet))

def key(name, pressed=True):
    """Hold (or release) one of the player's logical keys, like a physical press.

    Names: forward, back, left, right, jump, sneak, sprint, attack, use. These
    are LOGICAL keybindings, not raw key codes, so user rebinds don't matter.
    key(name, True) HOLDS the key until key(name, False) or release_keys(), so
    pair every press with a release (try/finally) - a crashed script that held
    "forward" would otherwise keep walking. For a single one-tick press, use
    tap(name) instead.
    """
    return _call(_talos_host.setKey, str(name), bool(pressed))

def tap(name):
    """Press a logical key for exactly ONE game tick, then release it.

    The scripted equivalent of a quick physical tap - jump over a lip, flick
    sneak. Same names as key(); returns once the key has been released, so no
    cleanup is ever needed.
    """
    return _call(_talos_host.tapKey, str(name))

def release_keys(*names):
    """Release keys held by key(). With no arguments, releases ALL of them.

    With names, releases only those, e.g. release_keys("forward", "jump")
    keeps a held "sneak" pressed. Safe to call unconditionally.
    """
    if not names:
        return _call(_talos_host.releaseKeys)
    for name in names:
        _call(_talos_host.setKey, str(name), False)

def look(yaw, pitch):
    """Snap the view to absolute yaw/pitch degrees (same convention as look_angle()).

    Yaw 0 = south, 90 = west; pitch -90 (up) to 90 (down). For smooth, human-ish
    turning, step the yaw a few degrees toward the target each tick instead of
    jumping straight to it - see the custom_goto example script.
    """
    return _call(_talos_host.setLook, float(yaw), float(pitch))

def look_angle():
    """Return the current view rotation as a (yaw, pitch) tuple in degrees.

    Yaw is wrapped to -180..180 (0 = south, 90 = west); pitch is -90 (up) to 90 (down).
    """
    angles = _call(_talos_host.lookAngle)
    return (float(angles[0]), float(angles[1]))

def looking_at():
    """Return the Pos of the block under the crosshair, or None (air/entity/out of reach).

    Truthy like find_block: `if talos.looking_at(): ...`
    """
    return _wrap_pos(_call(_talos_host.lookingAtBlock))

def block_at(x, y=None, z=None):
    """Return the block id at a cell, e.g. "minecraft:stone" ("minecraft:air" if empty)."""
    x, y, z = _coords(x, y, z)
    return _call(_talos_host.blockAt, int(x), int(y), int(z))

def input(prompt="Script is waiting for input"):
    """Ask the user for input via chat and block until they answer.

    The prompt is shown in chat; the user's NEXT plain chat message is captured —
    it never reaches the server — and returned as a string. Commands ("/...")
    are not captured, so /talos script stop still works while waiting.

        answer = talos.input("How many logs?")
        count = int(answer)

    Blocking here only pauses this script (or task); use `await talos.aio.input(...)`
    inside async tasks to let other tasks keep running.
    """
    return _call(_talos_host.userInput, str(prompt))

def on_edge(margin=0.3):
    """True when the player's feet stand within `margin` of a block boundary on X or Z.

    The player hitbox half-width is 0.3, so the default margin means "part of the
    hitbox overhangs the neighboring cell". Combine with block_at() to ask whether
    that neighbor is a drop:

        feet = talos.player_feet()
        if talos.on_edge() and talos.block_at(feet.x + 0.5, feet.y - 1, feet.z) == "minecraft:air":
            ...
    """
    feet = player_feet()
    fx = feet.x % 1.0  # fractional position inside the cell, 0..1
    fz = feet.z % 1.0
    return min(fx, 1.0 - fx) < margin or min(fz, 1.0 - fz) < margin
