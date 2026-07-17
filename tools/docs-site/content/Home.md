# Talos documentation

**Talos** is a **client-side** Minecraft **Fabric** automation mod (MC **1.21.11 / 26.1 / 26.2**). It
gives you a physics-simulated pathfinder, a ~206-family event-rule engine, humanized aim and input
macros, and an embedded **GraalPy** Python runtime (plus a VS Code bridge). Everything runs as **client
commands** — no server permission level, no server-side mod.

> ⚠️ Automation may violate a server's rules. Talos' humanization is **best-effort obfuscation, not a
> guarantee against anti-cheat**. Use it where you're allowed to.

<a class="cta-large" href="LLM-Usage.html">USE TALOS WITH AN LLM</a>

> **The best way to run Talos is LLM + CLI.** Give an LLM the [Talos skill](LLM-Usage) so it writes
> correct automation on the first try, and drive the game through the [terminal CLI](Terminal-CLI)
> so scripts run and stream their logs without leaving the editor or shell. The loop becomes:
> describe what you want → the LLM writes the script → `talos run script.py` → watch it happen
> in-game with live output. Everything else on this site is reference for that loop.

## Start here

| I want to… | Go to |
|---|---|
| Install the mod | [Installation](Installation) |
| Drive the game from my terminal (`talos run …`) | [Terminal CLI](Terminal-CLI) |
| Learn the `/talos` commands | [Command reference](Commands) |
| Arm reactive rules (`/talos on …`) and read live values | [Event rules & getters](Event-Rules-and-Getters) |
| Write Python automation (`import talos`) | [Python scripting](Scripting) |
| See full worked scripts (lumberjack, sensors, events…) | [Example scripts](Examples) |
| Understand Human mode / fatigue | [Humanization](Humanization) |
| Push scripts from an editor | [VS Code bridge](VS-Code-Bridge) |
| **Have ChatGPT/Claude/Gemini write Talos code for me** | [**Using Talos with an LLM**](LLM-Usage) |

## What Talos does

- **Pathfinding** — a from-scratch A\* planner over a deterministic mirror of vanilla player physics
  (not a waypoint graph). It walks, jumps, sprints, parkours (including momentum-chained hops), mines,
  bridges, pillars, digs shafts and swims as needed. Deep searches run full-speed on background threads
  over an immutable world snapshot; movement never stalls, and it replans live.
- **Event rules** — `/talos on <trigger> … run <command>`, ~206 unique trigger families (vitals,
  entities, blocks, items, world, packets, text, sound/particles), each with comparisons, sustained /
  windowed temporal modes, and selector filtering.
- **Raytrace & local coordinates** — `/talos raytrace` resolves vanilla caret (`^left ^up ^forward`)
  coordinates and casts look-rays that hit **blocks and entities** with sub-block precision.
- **Follow mode** — `/talos follow` trails any entity with a moving-goal navigator.
- **Humanized aim** — an off-grid "yellow cube" target model with fast/slow rotation profiles and a live
  red preview path — never an instant snap.
- **Macros** — channel-selective recording/replay of real per-tick input.
- **Scripting** — embedded GraalPy (`import talos`), libraries (`talos.require`), CLI args (`talos.args`),
  an on-screen HUD, an in-game editor, and a VS Code extension that pushes and runs scripts over a
  local, token-gated WebSocket.

## Quick start

```
/talos goto xyz ~ ~ ~50        # walk 50 blocks north, tunnelling/bridging as needed
/talos raytrace where          # what am I looking at? (block or entity, to 3dp)
/talos follow @e[type=cow]     # trail the nearest cow
/talos on health below 6 run chat low HP!    # arm a persistent rule
/talos example sensors         # write a live-dashboard example script
/talos script run example_sensors            # ...and run it
```

Every command lives under `/talos` (`/talos` is a full alias — both prefixes work everywhere).
