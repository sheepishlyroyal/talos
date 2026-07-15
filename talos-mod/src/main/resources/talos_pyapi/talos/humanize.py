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

def human(enabled=None):
    """Toggle or query aim + session-arc "Human mode".

    human(True)/human(False) enables/disables it; human() with no argument
    returns the current bool. When on, aim uses eased, non-instant cube
    trajectories and a wall-clock fatigue model drifts the active profile over
    the session — reactions slow and spread, aim loosens and overshoots more,
    the walk wobbles wider — plus idle micro-breaks that pause pathing briefly.
    When off, aim uses the direct snap path. Best-effort obfuscation of the
    long-session statistical fingerprint; NOT a guarantee of undetectability,
    and it never makes wrong-target mistakes (only motor-level ones).
    """
    if enabled is None:
        return bool(_talos_host.humanMode())
    return _talos_host.setHumanMode(bool(enabled))

def fatigue():
    """Current session-arc fatigue in [0.0, 1.0] (0 when Human mode is off/fresh)."""
    return float(_talos_host.humanFatigue())

def on_break():
    """True while a Human-mode micro-break is pausing automation."""
    return bool(_talos_host.humanOnBreak())
