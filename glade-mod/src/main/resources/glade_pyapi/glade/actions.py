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

def set_node_count(n):
    """Set navigation waypoint count; zero restores automatic path-length scaling."""
    return _glade_host.setNodeCount(int(n))

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

def mine_looking_at():
    """Mine the crosshair block over multiple ticks, stopping if the target changes."""
    return _glade_host.mineLookingAt()

def left_click():
    """Attack or start breaking the entity/block under the crosshair."""
    return _glade_host.leftClick()

def right_click():
    """Use or interact with the entity/block under the crosshair."""
    return _glade_host.rightClick()

def select_slot(n):
    """Select hotbar slot 0 through 8."""
    return _glade_host.hotbarSelect(int(n))

def click_slot(slot, right=False):
    """Left- or right-click a raw slot in the currently open screen handler."""
    return _glade_host.clickSlot(int(slot), bool(right))

def container_slot_count():
    """Return the number of non-player slots exposed by the open container screen."""
    return _glade_host.containerSlotCount()

def move_stack(from_slot, to_slot):
    """Move a stack between two raw open-screen slots using vanilla pickup clicks."""
    return _glade_host.moveStack(int(from_slot), int(to_slot))

def take_stack(container_slot, player_slot):
    """Move a stack from an open container/horse slot into a player screen slot."""
    return _glade_host.takeStack(int(container_slot), int(player_slot))

def armor_item(slot):
    """Return the item id equipped in helmet/chestplate/leggings/boots."""
    return _glade_host.armorItem(str(slot))

def equip_armor(from_slot, armor_slot):
    """Equip from a raw screen slot when the open handler exposes the armor row."""
    return _glade_host.equipArmor(int(from_slot), str(armor_slot))

def kill_nearest(radius=6.0):
    """Kill the nearest hostile entity and wait for verification."""
    return _glade_host.killNearest(float(radius))

def look_at(x, y, z):
    """Turn the player toward a world-space point."""
    return _glade_host.lookAt(float(x), float(y), float(z))

def player_pos():
    """Return an immutable player position snapshot."""
    return _glade_host.playerPos()
