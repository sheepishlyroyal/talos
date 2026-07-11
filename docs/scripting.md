# Scripting

Glade embeds [GraalPy](https://www.graalvm.org/python/) (a GraalVM Python
implementation) directly in the mod. Scripts are plain `.py` files that
`import glade` and call a small, curated host API — there's no general
Python standard library, no `pip`, and no filesystem/network access beyond
what `glade` exposes.

## Where scripts live

Scripts must be **directly inside** `.minecraft/glade/scripts/` (no
subdirectories — `ScriptEngine.run` rejects any resolved path whose parent
isn't exactly that folder). Create the folder if it doesn't exist yet.

```
.minecraft/
  glade/
    scripts/
      hello.py
      auto_mine.py
```

Run one with:
```
/glade script run hello
```
(the `.py` suffix on the command name is optional), and stop whatever's
running with:
```
/glade script stop
```
or from the VS Code extension (see [`docs/vscode.md`](vscode.md)), which
pushes source over the WebSocket bridge instead of reading from disk.

## The `glade` API

```python
import glade
```

`glade` is a small embedded package (`glade-mod/src/main/resources/glade_pyapi/glade/`)
re-installed fresh into every script run — `sys.modules` and script globals
are wiped between runs, so one script can't leak state into the next.

### Pathing

```python
glade.goto(x, y, z)                 # path to an exact block position, blocks until arrived
glade.goto_near(x, y, z, range)      # path to within `range` blocks of a position
glade.goto_xz(x, z)                  # path to an X/Z column
```
All three return a status string and raise if pathing fails (including
`"Baritone is not installed"` if no pathing adapter is present). They block
the **script's** worker thread until the path resolves or fails — the game
keeps rendering and ticking normally while a script is waiting on one of
these.

### Finding things

```python
glade.find_block(name, radius=64)          # -> Pos | None
glade.find_entity(entity_type, radius=64.0) # -> EntityInfo | None
glade.find_item(item, radius=64.0)          # -> EntityInfo | None
```
- `find_block` takes a block ID string (e.g. `"minecraft:diamond_ore"`),
  `radius` in `1..64`.
- `find_entity`/`find_item` take an entity-type or item ID string,
  `radius` in `(0, 128]`.
- Returned `Pos` objects expose `.x()`, `.y()`, `.z()`; `EntityInfo` exposes
  `.uuid()`, `.type()`, `.pos()`. These are **immutable snapshots** taken on
  the game thread at call time, not live handles — re-query if the world may
  have changed.

### Acting

```python
glade.place_block(x, y, z)   # place your held hotbar block, verified
glade.break_block(x, y, z)   # break the block, verified
glade.kill_nearest(radius=6.0)  # kill the nearest hostile within radius
glade.look_at(x, y, z)       # turn toward a world-space point (humanized)
glade.player_pos()           # -> Pos, your current eye position
```
`place_block`/`break_block`/`kill_nearest` all run through the same verified
action state machines as the equivalent `/glade` commands and raise with the
failure reason (e.g. "No hostile entity within 6.0 blocks") if the action
doesn't succeed.

### Humanization

```python
glade.wait_between(a, b)   # sleep a seeded-random duration in [a, b] seconds
glade.set_profile(name)    # "raw" | "natural" | "paranoid"
glade.set_seed(seed)       # seed this script's humanized waits (int)
```
`wait_between` sleeps the **script worker thread**, not the game thread — the
client keeps rendering while a script sleeps. `set_profile` changes the
default humanization profile used by pathing/aim/action humanizers for the
rest of the session (see below); `set_seed` only affects `wait_between`'s own
random source, seeded independently per script.

### Events

```python
@glade.on("tick")
def handler():
    ...
```
`glade.on(event)` is a decorator that registers a handler on the **script
worker thread** — handlers are dispatched asynchronously from game-thread
events and never block the game thread. Recognized events (from
`EventDispatcher`/`ScriptEngine`): `"tick"`, `"chat"`, `"entity_hurt"`,
`"disconnect"`. A `disconnect` event also triggers automatic script
teardown.

### Logging

```python
glade.log(message)
```
Writes an info-level line to the mod's logger (and to the VS Code output
channel / `/glade script run` caller, if a log sink is attached).

## Example: simple mining loop

```python
import glade

glade.set_profile("natural")

for _ in range(10):
    ore = glade.find_block("minecraft:diamond_ore", radius=48)
    if ore is None:
        glade.log("no more diamond ore in range")
        break
    glade.goto_near(int(ore.x()), int(ore.y()), int(ore.z()), 2)
    glade.break_block(int(ore.x()), int(ore.y()), int(ore.z()))
    glade.wait_between(1.5, 4.0)

glade.log("done")
```

## Example: auto-defend handler

```python
import glade

glade.set_profile("paranoid")

@glade.on("tick")
def defend(*_args):
    target = glade.find_entity("minecraft:zombie", radius=8.0)
    if target is not None:
        glade.log(f"engaging {target.type()}")
        glade.kill_nearest(radius=8.0)
```

Run this with `/glade script run auto_defend` and it keeps running until you
`/glade script stop` it, disconnect, or the script otherwise stops.

## Threading model

- Each script session owns **one dedicated worker thread** ("Glade Script
  Worker") and **one long-lived GraalPy `Context`**, reset (not recreated)
  between runs.
- **The game/client tick thread never enters Python.** All `glade.*` calls
  marshal to the game thread via `GameThreadExecutor`, run there, and hand
  the result back as a future the worker thread blocks on. This means a
  slow/blocking script call only blocks the **script**, never rendering or
  the rest of the game loop.
- `@glade.on(...)` handlers are posted from the game thread into a bounded
  queue and drained on the worker thread — dispatch is always async, so a
  slow handler can't stall the event source.
- Stopping a script (`/glade script stop`, a disconnect, or a level unload)
  invalidates every in-flight world-handle future and force-closes the
  GraalPy context (`Context.close(true)`), which is a hard stop even for a
  `while True:` loop with no yield points.

## Sandbox

The embedded `Context` is built with everything locked down by default
(`ScriptEngine.ensureContext`):

- `HostAccess.EXPLICIT` — Python can only call methods explicitly annotated
  `@HostAccess.Export` on the one bridge object it's given (`GladeNativeBridge`);
  no reflection into arbitrary Java classes.
- `allowHostClassLookup(_ -> false)` — no `Class.forName`-style host class
  access at all.
- `allowIO(IOAccess.NONE)`, `allowCreateProcess(false)`,
  `allowCreateThread(false)`, `allowNativeAccess(false)`,
  `allowEnvironmentAccess(EnvironmentAccess.NONE)`,
  `allowPolyglotAccess(PolyglotAccess.NONE)` — no filesystem, network,
  subprocess, thread creation, native calls, env var access, or access to
  other Truffle languages.

Practically: **no arbitrary filesystem or network access, no `pip`, and no
native/compiled Python packages** (numpy, etc. are out of scope — there's no
native access to load them). Only the pure-Python standard library modules
bundled with GraalPy and the curated `glade` package are available. Treat any
script you didn't write yourself as untrusted, the same way you would with
any other code that controls your game client.

## Humanization profiles

Set with `glade.set_profile(name)` or the `/glade` command layer; defined in
`HumanizationProfile`:

| Profile | Reaction time | Rotation speed | Overshoot | Notes |
|---|---|---|---|---|
| `raw` | ~1ms, no jitter | fixed 180°/tick | none | effectively instant/mechanical — no obfuscation |
| `natural` | ~185ms median, jittered | 8–16°/tick | 13% chance | mixed trajectory families (Bézier / minimum-jerk / piecewise-linear) |
| `paranoid` | ~310ms median, more jitter | 4–10°/tick | 25% chance | slower, more deviation, always visibility-checked before acting |

These drive `RotationHumanizer`/`TimingHumanizer` (and, when Baritone is
installed, its `freeLook`/`antiCheatCompatibility`/`legitMine` settings). See
the README's safety note — this is best-effort variation across trajectory
*families*, not a guarantee anything looks human to a determined observer.
