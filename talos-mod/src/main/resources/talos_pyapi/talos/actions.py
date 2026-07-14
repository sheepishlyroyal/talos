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
    """An entity snapshot: .uuid, .type (registry id), .pos, and .distance.

    .distance is the feet-to-feet distance from the local player at snapshot
    time (None on snapshots that carry no distance, e.g. event payloads).
    """
    __slots__ = ("uuid", "type", "pos", "distance")

    def __init__(self, uuid, type, pos, distance=None):
        self.uuid = uuid
        self.type = type
        self.pos = pos
        self.distance = distance

    def __repr__(self):
        return f"Entity(type={self.type!r}, pos={self.pos!r})"


class Player(Entity):
    """A player snapshot: .name (gamertag), .uuid, .pos (exact doubles), .distance.

    .type is always "minecraft:player"; positions are never floored, so full
    double precision is preserved. .distance is feet-to-feet from the local
    player (see player_feet()).
    """
    __slots__ = ("name",)

    def __init__(self, name, uuid, pos, distance):
        super().__init__(uuid, "minecraft:player", pos, distance)
        self.name = name

    def __repr__(self):
        return f"Player(name={self.name!r}, pos={self.pos!r}, distance={self.distance:.3f})"


class Hit:
    """A raycast hit snapshot: .type, .id, .pos and .distance.

    .type is "block" or "entity"; .id the struck registry id
    ("minecraft:stone", "minecraft:cow"); .pos the exact hit point (a Pos, at
    least 3-decimal precision); .distance the blocks from the eyes to that point.
    """
    __slots__ = ("type", "id", "pos", "distance")

    def __init__(self, type, id, pos, distance):
        self.type = type
        self.id = id
        self.pos = pos
        self.distance = float(distance)

    def __repr__(self):
        return f"Hit(type={self.type!r}, id={self.id!r}, pos={self.pos!r}, distance={self.distance:.3f})"


def _axis(snapshot, name):
    value = getattr(snapshot, name)
    return value() if callable(value) else value


def _wrap_pos(raw):
    return None if raw is None else Pos(_axis(raw, "x"), _axis(raw, "y"), _axis(raw, "z"))


def _wrap_entity(raw):
    if raw is None:
        return None
    distance = _axis(raw, "distance") if hasattr(raw, "distance") else None
    return Entity(_axis(raw, "uuid"), _axis(raw, "type"), _wrap_pos(_axis(raw, "pos")),
                  None if distance is None else float(distance))


def _wrap_player(raw):
    return Player(str(_axis(raw, "name")), _axis(raw, "uuid"),
                  _wrap_pos(_axis(raw, "pos")), float(_axis(raw, "distance")))


def _wrap_stack(raw):
    return {"slot": int(_axis(raw, "slot")), "id": str(_axis(raw, "id")),
            "count": int(_axis(raw, "count"))}


def _id(registry_id):
    """Normalize a registry id: bare names get the minecraft: namespace.

    Every id-taking function runs through this, so "dirt" and "minecraft:dirt"
    are interchangeable everywhere — same rule as the /talos commands."""
    name = str(registry_id)
    return name if ":" in name else "minecraft:" + name


def _flatten_names(values, kind="name"):
    """Normalize varargs: f(a, b), f([a, b]), and f((a, b)) all mean the same."""
    if len(values) == 1 and isinstance(values[0], (list, tuple)):
        values = tuple(values[0])
    for value in values:
        if isinstance(value, (list, tuple)):
            raise TypeError(f"pass {kind}s as separate arguments or ONE list, not nested lists")
    return values


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

def goto_block(block_id, radius=64):
    """Path to the nearest matching block, retrying the next-nearest candidate
    (up to 5) when one proves unreachable. Raises PathFailedError if none work."""
    return _call(_talos_host.gotoBlockType, _id(block_id), int(radius))

def follow(target, distance=3.0):
    """Follow any entity, keeping ~distance blocks, until following ENDS.

    target is a player name, an entity type ("zombie"), or a selector
    ("@e[type=cow,distance=..20]"). Blocks until the follow stops (/talos stop,
    another goto taking over, or the target staying gone), then raises
    PathFailedError describing why. Prefer `await talos.aio.follow(...)` inside
    async tasks so other work can run while following.
    """
    return _call(_talos_host.follow, str(target), float(distance))

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

def players(radius=128.0):
    """All OTHER players within radius blocks, nearest first (never the local player).

    Each Player snapshot carries .name (gamertag), .uuid, .pos (exact doubles,
    at least 3-decimal precision — never floored), and .distance. Distances are
    measured FEET-to-feet (entity origin to entity origin, the same coordinate
    player_feet() reports), not from the eyes.
    """
    return [_wrap_player(raw) for raw in _call(_talos_host.players, float(radius))]

def nearest_player(radius=128.0):
    """The nearest other player within radius, or None. Same snapshot as players()."""
    found = players(radius)
    return found[0] if found else None

def entities(type=None, radius=64.0):
    """All entities within radius blocks, nearest first, excluding the local player.

    `type` filters on the exact registry id ("minecraft:zombie"); None returns
    everything. Same position/distance conventions as players().
    """
    raw_list = _call(_talos_host.entities, None if type is None else str(type), float(radius))
    return [_wrap_entity(raw) for raw in raw_list]

def angle_to(x, y=None, z=None):
    """The (yaw, pitch) in degrees the player would need to face a point.

    Accepts the same targets as look_at(): coordinates, a Pos/Entity/Player
    snapshot, or '~ ~ ~' tokens. Angles are computed from the player's EYES
    with the exact math the rotation humanizer uses, so
    look(*talos.angle_to(target)) and talos.look_at(target) agree. Yaw is
    wrapped to -180..180 (0 = south, 90 = west); pitch -90 (up) to 90 (down).
    """
    tx, ty, tz = _coords(x, y, z)
    eye = player_pos()
    dx, dy, dz = tx - eye.x, ty - eye.y, tz - eye.z
    yaw = _math.degrees(_math.atan2(-dx, dz))
    pitch = -_math.degrees(_math.atan2(dy, _math.hypot(dx, dz)))
    return ((yaw + 180.0) % 360.0 - 180.0, pitch)

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
    return _call(_talos_host.placeBlockAs, int(x), int(y), int(z), _id(block_id))


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

def inventory():
    """Non-empty stacks in the player's 36 main slots as {slot, id, count} dicts.

    SLOT NUMBERING: these are stable PlayerInventory indices — 0-8 the hotbar
    (what select_slot()/selected_slot() use), 9-35 the main grid. They are NOT
    the raw screen-handler indices click_slot()/move_stack() take; those shift
    with whichever screen is open (use container_items() for raw indices).
    Empty slots are omitted — the "slot" field says where each stack sits.
    """
    return [_wrap_stack(raw) for raw in _call(_talos_host.inventoryItems)]

def hotbar():
    """The hotbar's non-empty stacks: inventory() entries with slot 0-8."""
    return [entry for entry in inventory() if entry["slot"] < 9]

def selected_slot():
    """The currently selected hotbar slot, 0-8 (the slot select_slot() sets)."""
    return int(_call(_talos_host.selectedSlot))

def count(item_id):
    """Total number of an item across the player's 36 main inventory slots."""
    wanted = _id(item_id)
    return sum(entry["count"] for entry in inventory() if entry["id"] == wanted)

def has(item_id):
    """True when at least one of the item sits in the player's main inventory."""
    return count(item_id) > 0

def find_slot(item_id):
    """First PlayerInventory slot index (0-35, see inventory()) holding the item, or None."""
    wanted = _id(item_id)
    for entry in inventory():
        if entry["id"] == wanted:
            return entry["slot"]
    return None

def container_items():
    """Non-empty stacks in the open container's non-player slots as {slot, id, count}.

    SLOT NUMBERING: these ARE the raw screen-handler indices, directly usable
    with click_slot()/move_stack()/take_stack() (container slots come first in
    every vanilla handler, so they typically run 0..container_slot_count()-1).
    """
    return [_wrap_stack(raw) for raw in _call(_talos_host.containerItems)]

def deposit(item_id, amount):
    """Move up to `amount` items of a type from the player into the open container.

    Best-effort exact: whole stacks are shift-clicked, a partial tail is placed
    one item at a time, and the number of items ACTUALLY moved is returned
    (0 when the container is full; deposit(t, talos.count(t)) empties you of t).
    Raises if no container screen is open.
    """
    return int(_call(_talos_host.deposit, _id(item_id), int(amount)))

def withdraw(item_id, amount):
    """Move up to `amount` items of a type from the open container into the player.

    Mirror of deposit(): returns the count actually moved (0 when your
    inventory is full or the container has none). Raises if no container is open.
    """
    return int(_call(_talos_host.withdraw, _id(item_id), int(amount)))

def craft(item_id, count=1):
    """Craft `count` results of an OUTPUT item via the recipe book.

    Needs a crafting screen open: the player inventory (2x2 recipes) or a
    crafting table. LIMITATION: 1.21.11 servers no longer send recipe resource
    ids to the client (recipes arrive as anonymous network ids plus displays),
    so `item_id` names the recipe's RESULT item ("minecraft:crafting_table"),
    not a recipe id — when several recipes share an output, one whose
    ingredients you currently have is preferred. Each craft is one grid fill
    (count=2 for oak planks yields 8). Waits for the server to fill the grid
    between crafts; raises (reporting partial progress) when ingredients run out.
    """
    return _call(_talos_host.craft, _id(item_id), int(count))

def screen():
    """Name of the open screen, or None when none is open.

    Screen-handler screens report their registry id ("minecraft:generic_9x3",
    "minecraft:crafting", ...); the player's own inventory reports
    "minecraft:inventory"; other screens report their title or class name.
    """
    name = _call(_talos_host.screenName)
    return None if name is None else str(name)

def close_screen():
    """Close whatever screen is open (container, inventory, ...); safe when none is."""
    return _call(_talos_host.closeScreen)

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
    keeps a held "sneak" pressed. A single list/tuple also works:
    release_keys(["forward", "jump"]). Safe to call unconditionally.
    """
    names = _flatten_names(names, "key name")
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

def local(left, up, forward):
    """World Pos of the caret coordinate ^left ^up ^forward (look-relative, from the eyes).

    Same frame vanilla's ^ ^ ^ uses: +forward is along your gaze (negative is
    behind), +up is above your view, +left is to your left. local(0, 0, 5) is
    the point 5 blocks ahead; pass it straight to goto()/look_at()/block_at().
    Equivalent to the string form goto("^ ^ 5").
    """
    return _wrap_pos(_call(_talos_host.localCoords, float(left), float(up), float(forward)))

def ahead(distance):
    """World Pos `distance` blocks straight along the gaze — local(0, 0, distance).

    Follows pitch, so looking down aims into the ground; use move_ahead() to
    actually WALK forward on the horizontal.
    """
    return local(0.0, 0.0, float(distance))

def raytrace(max_distance=64.0):
    """Cast a ray from the eyes along the look; return the first Hit, or None.

    Returns a Hit with .type ("block"/"entity"), .id, .pos (the exact impact
    point, ≥3-decimal precision) and .distance. Unlike looking_at(), this sees
    ENTITIES too and gives the precise sub-block hit position, out to
    max_distance blocks (default 64).
    """
    raw = _call(_talos_host.raycast, float(max_distance))
    if raw is None:
        return None
    return Hit(str(_axis(raw, "type")), str(_axis(raw, "id")),
               _wrap_pos(_axis(raw, "pos")), float(_axis(raw, "distance")))

def raytrace_if(block=None, entity=None, max_distance=64.0):
    """True when the first thing the look ray hits matches. Pass exactly one of:

    block="minecraft:stone" (exact block id) or entity="zombie" (entity type;
    bare names get a minecraft: prefix). The predicate form of raytrace().
    """
    if (block is None) == (entity is None):
        raise ValueError("pass exactly one of block= or entity=")
    hit = raytrace(max_distance)
    if hit is None:
        return False
    if block is not None:
        want = _id(block)
        return hit.type == "block" and hit.id == want
    want = _id(entity)
    return hit.type == "entity" and hit.id == want

def move_ahead(distance):
    """Walk `distance` blocks forward along your HORIZONTAL heading, and wait for arrival.

    Uses your yaw only (pitch ignored), so looking up or down never sends the
    path into the air or ground. Equivalent to pathing to the projected point;
    raises PathFailedError if it can't get there.
    """
    yaw, _ = look_angle()
    feet = player_feet()
    yaw_r = _math.radians(yaw)
    return goto(feet.x - _math.sin(yaw_r) * float(distance), feet.y,
                feet.z + _math.cos(yaw_r) * float(distance))

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

def hud(text, id="hud"):
    """Pin a line of text to the on-screen overlay (top-left).

    Repeated calls with the same id update the line in place; different ids
    stack in first-set order (max 20 lines, 256 chars each). Legacy `§` color
    codes work. The overlay is cleared automatically when the script stops.

        talos.hud(f"mined {count} / {goal}")
        talos.hud("§eidle", id="state")
    """
    _talos_host.hudSet(str(id), str(text))

def hud_remove(id="hud"):
    """Remove one overlay line by id; safe when the id was never set."""
    _talos_host.hudRemove(str(id))

def hud_clear():
    """Remove every overlay line."""
    _talos_host.hudClear()
