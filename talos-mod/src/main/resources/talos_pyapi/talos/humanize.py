"""Humanization controls. Sleeping always occurs on the script worker."""

def wait(a, b=None):
    """Sleep for `a` seconds, or a seeded random duration in [a, b] seconds.

    wait(0.4) sleeps exactly 0.4 seconds; wait(0.4, 0.9) sleeps a humanized
    random duration in that range. Blocks every task; inside async tasks
    prefer `await talos.aio.wait(...)`.
    """
    if b is None:
        b = a
    return _talos_host.waitBetween(float(a), float(b))

def wait_between(a, b):
    """Sleep for a seeded random duration in [a,b] seconds. Alias of wait(a, b)."""
    return wait(a, b)

def set_profile(name):
    """Set the default profile: raw, natural, or paranoid."""
    return _talos_host.setProfile(str(name))

def set_seed(seed):
    """Seed script-side humanized waits."""
    return _talos_host.setSeed(int(seed))
