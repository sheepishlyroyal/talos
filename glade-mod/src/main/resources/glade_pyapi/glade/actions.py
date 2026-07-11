"""Action and query wrappers. Values returned by the host are immutable snapshots."""

def goto(x, y, z):
    """Path to an exact block position and wait for completion."""
    return _glade_host.gotoBlock(int(x), int(y), int(z))

def goto_near(x, y, z, range):
    """Path to within range blocks of a position."""
    return _glade_host.gotoNear(int(x), int(y), int(z), int(range))

def goto_xz(x, z):
    """Path to an X/Z column."""
    return _glade_host.gotoXZ(int(x), int(z))

def find_block(name, radius=64):
    """Return the nearest matching block position snapshot, or None."""
    return _glade_host.findBlock(str(name), int(radius))

def find_entity(entity_type, radius=64.0):
    """Return the nearest entity-type snapshot, or None."""
    return _glade_host.findEntity(str(entity_type), float(radius))

def find_item(item, radius=64.0):
    """Return the nearest dropped-item snapshot, or None."""
    return _glade_host.findItem(str(item), float(radius))

def place_block(x=None, y=None, z=None, block_id=None):
    """Place a block.

    With no coordinates, places against the block you are currently looking at
    (the crosshair target), like a right-click. With x/y/z, places at that position;
    if block_id (e.g. "minecraft:diamond_ore") is given, a matching hotbar item is
    selected first.
    """
    if x is None and y is None and z is None:
        return _glade_host.placeLook()
    if x is None or y is None or z is None:
        raise ValueError("place_block needs all of x, y, z (or none, to place where looking)")
    if block_id is None:
        return _glade_host.placeBlock(int(x), int(y), int(z))
    return _glade_host.placeBlockAs(int(x), int(y), int(z), str(block_id))


def place_look():
    """Place a hotbar block at the block you are currently looking at (crosshair target)."""
    return _glade_host.placeLook()

def break_block(x, y, z):
    """Break a block and wait for verification."""
    return _glade_host.breakBlock(int(x), int(y), int(z))

def kill_nearest(radius=6.0):
    """Kill the nearest hostile entity and wait for verification."""
    return _glade_host.killNearest(float(radius))

def look_at(x, y, z):
    """Turn the player toward a world-space point."""
    return _glade_host.lookAt(float(x), float(y), float(z))

def player_pos():
    """Return an immutable player position snapshot."""
    return _glade_host.playerPos()
