# v1.1.0 in-game checklist

**Setup (once):** put `talos-mod-1.1.0-mc<your version>.jar` in `mods/` (from the Desktop
"Talos Builds" folder or the v1.1.0 release), launch, join any world (singleplayer is fine).

## Step 1 — automated (one command)

From a terminal (game running):

```
talos "/Users/leon/Desktop/Talos Builds/checklist.py"
```

(or copy `checklist.py` into `.minecraft/talos/scripts/` and run `/talos script run checklist`).

It prints `PASS`/`FAIL` for each item and a `N/N passed` summary on the HUD:

| # | Automated check | Proves |
|---|---|---|
| 1 | stdlib imports (`random`, `json`, `heapq`, …) | `import random` works in-game |
| 2 | `talos.chat()` message appears in chat | chat API |
| 3 | `talos.run_command("/talos human show")` prints tuning | command API |
| 4 | intensity set/get, `tune()`, clamping (9999 → ≤360), unknown-knob error, reset | humanisation tuning |
| 5 | `debug_mode` toggle + debug line visible | logging switch |
| 6 | sim steps ~20/s, pause stops it, 5 errors auto-pause (breaker), seeded rng | `talos.sim` |

First terminal run may ask for `/talos bridge allow` in-game — run it once.

## Step 2 — manual (4 quick checks, instructions also shown on the HUD)

| # | Type this | Expect |
|---|---|---|
| 1 | `/talos checklist_ping ` then **TAB** | `pong` is suggested; running it logs "checklist_ping OK" |
| 2 | `/talos human set ` then **TAB** | knob names suggested (`overshoot_prob`, `rotation_speed_max`, …) |
| 3 | `/talos example sim` → `/talos script run example_sim` | a HUD line appears and you wander/graze like a sheep; `/talos sheep pause` / `resume` obey; `/talos stop` ends it |
| 4 | `/talos human intensity 3` → `/talos look 90 0` | noticeably slow, wandering aim; `/talos human intensity 0` → snappy. `/talos human reset` restores |

## Step 3 — persistence (30 seconds)

1. `/talos human intensity 1.5` and `/talos human set overshoot_prob 0.3`
2. Quit Minecraft completely, relaunch, rejoin.
3. `/talos human show` → intensity 1.5 and the override are still there. `/talos human reset` when done.

Done. If any automated check FAILs, the failure line names the feature — send me that line.
