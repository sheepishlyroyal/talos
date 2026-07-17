# Humanization

Talos routes aim, timing and movement through a composable humanization layer. There are two independent
dials: a **stationary profile** (raw/natural/paranoid) and **Human mode** (a session-arc fatigue model
plus eased cube-aim).

> ⚠️ This is **best-effort obfuscation of long-session statistical detection, not a guarantee of
> undetectability**, and automation may still violate a server's rules. It models only *motor-level*
> imperfection (overshoot, hesitation, breaks) — never *semantic* mistakes like attacking the wrong target.

## Profiles

`set_profile("raw" | "natural" | "paranoid")` (Python) or the profile Talos reports on `/talos human off`.
Profiles are **categories of distributions**, not fixed knobs — the trajectory *family* varies
(Bézier / minimum-jerk / piecewise-noise), because a fixed distribution shape is itself a fingerprint.

- **raw** — minimal shaping; fastest.
- **natural** — right-skewed timing, bounded velocity/accel, overshoot + micro-correction.
- **paranoid** — widest variance, most conservative motion.

`set_seed(seed)` makes a run reproducible (one `SeededRng` per task, never a global) — useful for testing.

## Tuning — more/less humanisation, per-knob

The profiles are starting points, not the ceiling. Two layers of user tuning sit on top, settable
from Python or chat and **persisted in the mod config across sessions**:

### Intensity — one dial

`talos.intensity(1.5)` or `/talos human intensity 1.5`. Scales the humanness knobs together:
reaction delays, overshoot probability/magnitude, timing jitter and path wobble scale **up** with
intensity, while rotation speed scales **down**. `0` is near-robotic, `1` is the profile as
authored, `3` is the exaggerated maximum.

### Per-knob overrides

`talos.tune(overshoot_prob=0.3, rotation_speed_max=12)` or `/talos human set <knob> <value>`
(knob names tab-complete). Every knob is clamped into a safe range — bad values can tune aim but
never break it:

| Knob | Meaning | Safe range |
|---|---|---|
| `reaction_median_ms` | median reaction delay before an action | 1–5000 |
| `reaction_sigma` | log-normal spread of reaction delays | 0–2 |
| `rotation_speed_min` / `rotation_speed_max` | aim speed range, degrees per tick | 0.5–360 |
| `max_accel` | max angular acceleration, deg/tick² | 0.5–360 |
| `overshoot_prob` | chance an aim overshoots then corrects | 0–1 |
| `overshoot_min` / `overshoot_max` | overshoot magnitude range, degrees | 0–30 |
| `jitter_phi` | AR(1) correlation of timing jitter | 0–0.95 |
| `path_deviation` | lateral walk/aim wobble stdev | 0–2 |
| `visibility_check` | 1 = only aim at visible targets | 0/1 |

### Trajectory families

Restrict which aim-path shapes are used: `talos.tune(families=["bezier", "min_jerk"])` — options
`bezier`, `min_jerk`, `linear`.

### Inspect & reset

`talos.human_knobs()` returns a dict with `profile`, `intensity`, `human_mode`, `families`,
`overrides` (your tuning) and `effective` (the final numbers actually used for aim/timing);
`/talos human show` prints the same in chat. Clear everything with `talos.reset_tuning()` or
`/talos human reset`.

> Design note: knobs, families and intensity are the supported way to change *how* humanisation
> behaves. Python callbacks cannot supply aim curves directly — aim plans are computed on the game
> thread, and the game thread never enters Python (a core stability invariant).

## Human mode — `/talos human [on|off]` · `talos.human(True/False)`

The single Human-mode toggle. **On** bundles two things:

### 1. Eased cube-aim (no direct snap)

Command and Python aim — absolute angles, coordinates, blocks and entities — runs through the cube-aim
controller (`AimController`):

- A **1×1m yellow guide cube** is rendered off-grid, centred exactly on the intended point.
- The actual aim spot — the **red X** — lands on a **visible face** chosen with probability proportional
  to that face's visible flat area, center-biased, then a random spot on that face.
- Rotation draws a random **fast** sensitivity far out and a random **slow** sensitivity for the final
  approach, blending smoothly (never instantly) once the look ray passes within 0.4m of the cube, with
  per-tick jitter and a **red dotted preview line** tracing the exact curve the crosshair is about to draw.
- A per-session **quadratic speed modulation** (peaking mid-flight, never linear) adds a small speed
  swell/sag and a slight bow to the path.

`/talos track` keeps the same aim session alive against a moving target. Navigation gaze during
`/talos goto` speaks the same language against a full-block cube on the look-ahead target's hitbox — while
the walking bearing stays honest (the mark pulls the gaze only a few degrees off the route line; the
physics rollouts still choose the actual movement inputs).

### 2. Session-arc fatigue

On top of the stationary profile, a wall-clock **fatigue** model (`SessionArc`) drifts your behaviour
over the session so the input stream is **non-stationary** — because a real human's parameters drift, and
a fixed-parameter profile is exactly what a server can find over hours:

- reactions **slow and spread**,
- aim **loosens and overshoots** more,
- the walk **wobbles wider**,
- **idle micro-breaks** pause pathing briefly — more often, and longer, as fatigue rises.

The HUD shows a `human » fatigue N% (Mm)` line (or `on break (Ns)`) while it's on.

**Off** uses direct snap aiming and disables the session drift.

## Python surface

```python
talos.human(True)        # enable; talos.human() returns current state
talos.human(False)       # disable
talos.fatigue()          # 0.0–1.0 current fatigue
talos.on_break()         # True while an idle micro-break is pausing pathing
talos.set_profile("natural")
talos.set_seed(1234)
talos.wait_between(2, 5)  # right-skewed humanized pause (the human-ish sleep)
```
