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

def intensity(value=None):
    """Query or set the global humanisation intensity.

    0 = near-robotic (fast, precise, no overshoot), 1 = the selected profile as
    authored, up to 3 = exaggerated (slower, sloppier, more overshoot). Applies
    on top of the profile and any tune() overrides; persisted across sessions.
    """
    if value is None:
        return float(_talos_host.humanIntensity())
    return _talos_host.setHumanIntensity(float(value))

def tune(families=None, **knobs):
    """Override individual humanisation knobs; persisted across sessions.

    Knob names (all clamped into safe ranges — bad values can tune aim, never
    break it): reaction_median_ms, reaction_sigma, rotation_speed_min,
    rotation_speed_max, max_accel, overshoot_prob, overshoot_min, overshoot_max,
    jitter_phi, path_deviation, visibility_check (0/1).

    families restricts aim trajectory shapes: any of "bezier", "min_jerk",
    "linear" (e.g. tune(families=["bezier", "min_jerk"])).

        talos.tune(overshoot_prob=0.3, rotation_speed_max=12)
    """
    for name, value in knobs.items():
        _talos_host.setHumanKnob(str(name), float(value))
    if families is not None:
        _talos_host.setHumanFamilies(",".join(str(f) for f in families))

def human_knobs():
    """Current tuning + effective humanisation values, as a dict.

    Keys: profile, intensity, human_mode, families, overrides (your tune()
    values), effective (the final numbers actually used for aim/timing).
    """
    import json as _json
    return _json.loads(_talos_host.humanKnobs())

def reset_tuning():
    """Clear intensity, all tune() overrides and family restrictions back to the pure profile."""
    return _talos_host.resetHumanKnobs()

def fatigue():
    """Current session-arc fatigue in [0.0, 1.0] (0 when Human mode is off/fresh)."""
    return float(_talos_host.humanFatigue())

def on_break():
    """True while a Human-mode micro-break is pausing automation."""
    return bool(_talos_host.humanOnBreak())
