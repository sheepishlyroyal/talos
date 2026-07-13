"""Humanization controls. Sleeping always occurs on the script worker."""

def wait_between(a, b):
    """Sleep for a seeded random duration in [a,b] seconds."""
    return _talos_host.waitBetween(float(a), float(b))

def set_profile(name):
    """Set the default profile: raw, natural, or paranoid."""
    return _talos_host.setProfile(str(name))

def set_seed(seed):
    """Seed script-side humanized waits."""
    return _talos_host.setSeed(int(seed))
