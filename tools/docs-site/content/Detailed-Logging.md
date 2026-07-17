# Detailed logging

One master switch controls how loud Talos is. Everything shares a single rotating sink at
`~/.talos/logs/session-<timestamp>.log` — a new file per game launch, newest 10 kept.

## The switch

| Command | Effect |
|---|---|
| `/talos debug` / `/talos debug status` | Report ON/OFF and the current session log-file path. |
| `/talos debug on` | Enable: engine trace + `talos.debug()` lines surface to chat and the file. |
| `/talos debug off` | Disable: `info`/`warn`/`error` continue; `debug` + trace go quiet. |

From Python the same switch is `talos.debug_mode(True)` / `talos.debug_mode(False)`;
`talos.debug_mode()` with no argument queries it.

## Script logging API

| Call | Behaviour |
|---|---|
| `talos.log(msg, level="info")` | Leveled line → session log file, mod log, and the script console (in-game chat, or the VS Code output channel when run through the bridge). Returns the text. `talos.log("x")` behaves exactly like before. |
| `talos.info(msg)` | Same as `talos.log(msg)`. Gray in chat. |
| `talos.warn(msg)` | Yellow in chat. Always visible. |
| `talos.error(msg)` | Red in chat. Always visible. |
| `talos.debug(msg)` | Dark-gray in chat — only visible (console **and** file) while debug mode is on. Free to leave in production scripts. |

```python
import talos

talos.debug_mode(True)                              # same as /talos debug on
talos.debug(f"starting at {talos.player_feet()}")   # visible only in debug mode
talos.warn("low durability")                        # always visible, yellow
```

## Engine trace (debug mode only)

With the switch on, the engine narrates what it's deciding — live to chat, and into the file:

- **Pathing** — plan starts, native/simulation search attempts, replans, follower stalls, final
  success/fail with detail.
- **Movement** — follow-segment starts, moving-goal retargets, route hot-swaps, stall detection.
- **Rules** — each `/talos on` rule fire with its trigger and resolved value.
- **Actions** — break/place/kill state transitions (`prepare → acquire → execute → verify`).
- **Scripts** — session start/stop and hard-stops.

This makes `/talos debug on` the first diagnostic step when a goto stalls, a rule doesn't fire, or a
script misbehaves — and the file keeps the full timestamped transcript for later.

## File format

```
16:20:31.412 [INFO] [script] harvest pass 3 done
16:20:32.001 [DEBUG] [pathing] simulation search attempt 2: partial (node cap)
16:20:33.590 [WARN] [script] low durability
```

`HH:mm:ss.SSS [LEVEL] [category] message` — categories are `script`, `pathing`, `movement`, `rules`,
`actions`. `DEBUG` lines are skipped in the file while the switch is off, so quiet sessions stay small.
