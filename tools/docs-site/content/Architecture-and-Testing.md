# Architecture & Testing

Talos is a set of engines behind thin entry points. Every part below names its package under
`talos-mod/src/main/java/dev/talos/` and the seam you can hook to test it without touching the
others.

## Component map

```
 you                          the mod (client only)                        Minecraft
 ───                          ─────────────────────                        ─────────
 /talos …  ──────────► client/command/TalosCommands ──┐
 VS Code / talos CLI ─► client/bridge/ (WebSocket) ───┤
                          BridgeProtocol JSON v1      ├─► client/script/ScriptEngine
                                                      │     └ ≤8 Sessions: 1 worker thread
 .py files ───────────────────────────────────────────┘       + 1 GraalPy Context each
                                                              │
                              Python `import talos` (resources/talos_pyapi/)
                                                              │
                          client/script/TalosNativeBridge  (default-deny exports)
                                                              │  every call marshals via
                          client/script/GameThreadExecutor ◄──┘  submit() → client tick
                                                              │
        ┌──────────────┬───────────────┬──────────────┬───────┴──────┬─────────────┐
  client/pathing   client/action   client/rules   client/humanize  client/scan  client/hud
        └──────────────┴───────────────┴── client/log/TalosLog ── ~/.talos/logs/session-*.log
```

## Key invariants (what tests should assert)

- The client tick thread **never enters Python** — scripts run on session worker threads and touch
  the game only through `GameThreadExecutor.submit(...)` (bounded queue, drained each tick).
- Every script run/eval takes an injectable **`LogSink`** (`void log(String level, String text)`).
  Chat is merely the default sink; the bridge substitutes a WebSocket sink; the CLI prints the same
  stream in a terminal; the session file gets it all.
- A stop (command, bridge frame, or CLI Ctrl-C) must unblock **any** stuck call: sessions
  invalidate their native bridge, cancel in-flight game-thread futures, and hard-close the GraalPy
  context.
- The bridge speaks versioned JSON, loopback-only, token-gated, nothing before `auth_ok`.

## Testing seams

| Layer | Seam | How to test it |
|---|---|---|
| Wire protocol | Plain JSON over a WebSocket | Mock either side without Minecraft. The CLI was validated against an ~80-line stdlib mock bridge asserting `push_script`/`run`/`eval` shapes and replaying `log`/`script_done`. |
| Script engine + API | CLI exit codes | `talos selftest.py && echo PASS` — `0` ok, `1` script raised, `3` bridge down. Scriptable from CI or a shell loop. |
| Python API surface | In-game self-test script | The check-runner below; the run fails (exit `1`) if any check fails. |
| Log pipeline | `~/.talos/logs/session-*.log` | Every level-tagged line lands in the file; `talos logs` reads it with the game closed. |
| Engine internals | `/talos debug on` | Pathing/movement/rules/actions/script categories narrate decisions to chat + file — grep the session log for `[pathing]` etc. |
| Humanizer | `talos.set_seed(n)` | Seeded runs are deterministic — replay and compare. |
| Rules engine | `/talos get <trigger>` | Every rule trigger is also a getter — read the value a rule would see without firing it. |

## Bug-hunting workflow

1. `/talos debug on` (or `talos py -c 'talos.debug_mode(True)'`) — the engine narrates pathing
   plans/replans/stalls, rule fires, action state transitions and script lifecycle to chat + file.
2. `talos logs -f` follows the session log from a terminal while you play; grep `[pathing]`,
   `[rules]`, `[actions]`, `[script]` after the fact.
3. Probe state without writing a script: `talos py -c 'talos.get("server_tps")'`; in-game
   `/talos get <name>` reads exactly what a rule trigger would see.
4. Sprinkle `talos.debug(...)` in scripts — invisible until debug mode is on, safe to leave in.
5. Reduce to a repro script and bisect from the shell (`while ! talos repro.py; do …; done`) or use
   VS Code run-on-save for fast iteration.
6. `/talos script profile` toggles per-event dispatch profiling. Attach the session log to bug
   reports — it is the full timestamped transcript.

## The self-test pattern

Drop into `.minecraft/talos/scripts/selftest.py`, or run `talos selftest.py` from a terminal —
non-zero exit means a check failed. Extend with one `@check(...)` per feature you care about:

```python
import talos

CHECKS = []
def check(name):
    def wrap(fn):
        CHECKS.append((name, fn))
        return fn
    return wrap

@check("player position readable")
def _(): assert talos.player_feet() is not None

@check("world block lookup")
def _():
    feet = talos.player_feet()
    assert ":" in talos.block_at(feet.x, feet.y - 1, feet.z)

@check("observable catalog")
def _(): assert talos.get("health") > 0

@check("raytrace does not raise")
def _(): talos.raytrace(8.0)          # None (no hit) is fine

@check("inventory snapshot")
def _(): assert isinstance(list(talos.inventory()), list)

@check("logging pipeline")
def _(): assert talos.log("selftest ping") == "selftest ping"

failed = 0
for name, fn in CHECKS:
    try:
        fn()
        talos.info("PASS " + name)
    except Exception as error:
        failed += 1
        talos.error("FAIL " + name + ": " + repr(error))
talos.log(f"{len(CHECKS) - failed}/{len(CHECKS)} checks passed")
if failed:
    raise RuntimeError(f"{failed} check(s) failed")   # → exit code 1 in the CLI
```
