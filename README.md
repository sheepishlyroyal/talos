# Glade

Glade is a **client-side Fabric mod for Minecraft 1.21.11** that turns
repetitive in-game actions into scripted, humanized automation, and gives it
a liquid-glass in-game UI to match. Automations are written two ways: real
**Python** (embedded GraalPy, runnable from a VS Code extension) and — planned,
not yet shipped — a native drag-and-drop **block editor** that compiles to the
same Python. Movement is driven by an optional **Baritone** pathfinder behind
a swappable adapter; every synthetic action (aim, timing, movement) passes
through a **humanization layer** so bot behavior isn't a single fixed,
easily-fingerprinted motion.

This is a v1, actively developed project. See "Current status" below for
what's real today versus planned.

## Feature overview

- **`/glade` commands** — find blocks, path to a position, mine/place blocks,
  kill the nearest hostile, highlight a block, run/stop scripts, open the UI,
  manage the VS Code bridge. Full reference: [`docs/commands.md`](docs/commands.md).
- **Python scripting** — drop a `.py` file in `.minecraft/glade/scripts/` and
  run it with `/glade script run <name>`, or push it live from VS Code. The
  script runs in a sandboxed GraalPy context on its own worker thread; game
  interaction happens through a small curated `glade` API
  (`goto`, `find_block`, `place_block`, `break_block`, `kill_nearest`,
  `wait_between`, `look_at`, `player_pos`, `log`, `set_profile`, `set_seed`,
  `@glade.on(...)`). Full reference: [`docs/scripting.md`](docs/scripting.md).
- **VS Code extension** — write scripts with autocomplete, push-and-run with
  one keybinding, stream logs back into an output channel, all over a
  token-gated loopback WebSocket. Full reference: [`docs/vscode.md`](docs/vscode.md).
- **In-game UI** — `/glade ui` opens a frosted "liquid-glass" settings panel
  (`GladeScreen`) with a Dark/Light theme toggle; warm "soft" palette variants
  exist in code but aren't wired to a selectable option yet. Full reference:
  [`docs/ui.md`](docs/ui.md).
- **Baritone-powered pathfinding** — `/glade goto` and the scripting API's
  `goto`/`goto_near`/`goto_xz` route through a swappable `PathingEngine`. If no
  pathing adapter is installed, pathing calls fail cleanly with a typed
  "not installed" error instead of crashing.
- **Humanization** — rotation, timing, and (when Baritone is present) movement
  all sample from named profiles (`raw`, `natural`, `paranoid`) instead of
  snapping instantly or using a single fixed randomization curve.

## Current status (v1 — read before relying on anything)

- **Implemented:** `find`, `goto` (block/near/xz), `mine`, `place`,
  `kill nearest`, `glow`, `/glade ui` (theme toggle only), `script run|stop`,
  `bridge allow|status`, the full Python `glade` API, the VS Code extension +
  WebSocket bridge, the reflective Baritone adapter, the humanizer and its
  three profiles.
- **Not implemented yet:** `/glade editor` (the native block editor) and any
  `/glade config` command — settings currently live only in
  `config/glade.json`, and in-UI theme/profile changes are **not yet
  persisted** to that file (only the values loaded at startup are applied;
  see `GladeClientMod.onInitializeClient` for the TODO). Treat the plan
  document referenced in this repo's history as the target architecture, not
  a description of what ships today.

## Install

**Requirements:** Java 21, Minecraft 1.21.11, Fabric Loader ≥ 0.19.3,
[Fabric API](https://modrinth.com/mod/fabric-api) 0.141.4+1.21.11 (**required**
— Glade will not load without it).

1. **Build the mod:**
   ```bash
   ./gradlew build
   ```
   This builds two Fabric mod jars you need:
   - `glade-mod/build/libs/glade-mod-<version>.jar` — the mod itself.
   - `glade-pathing-baritone/build/libs/glade-pathing-baritone-<version>.jar`
     — the optional Baritone adapter (only needed if you want pathfinding;
     see below).
2. **Install:** copy `glade-mod-<version>.jar` (and, if you want pathing,
   `glade-pathing-baritone-<version>.jar`) into your `.minecraft/mods/`
   folder, alongside Fabric API.
3. **(Optional) Pathfinding:** Glade never bundles Baritone — it's LGPL and
   there's no official 1.21.11 release. `glade-pathing-baritone` talks to it
   purely through **reflection** against `baritone.api.*`, so there's no
   compile-time dependency either. To get pathfinding:
   - Build or obtain a **1.21.11-compatible Baritone fork** yourself and drop
     its jar in `mods/` too.
   - Drop `glade-pathing-baritone-<version>.jar` in `mods/`.
   - If Baritone's jar isn't present (or its API shape doesn't match what the
     adapter expects), `/glade goto` and the scripting `goto*` calls fail
     with a clear "Baritone is not installed" error instead of crashing —
     Glade works fine without it, you just lose pathfinding.
4. **(Optional) VS Code extension:** see [`docs/vscode.md`](docs/vscode.md).

## Quick start

1. Launch Minecraft with the mods above installed.
2. In chat, try:
   ```
   /glade find block minecraft:diamond_ore 32
   /glade goto near 100 64 200 3
   /glade ui
   ```
3. Drop a script in `.minecraft/glade/scripts/hello.py`:
   ```python
   import glade

   glade.log("hello from a Glade script")
   pos = glade.player_pos()
   glade.log(f"standing at {pos.x():.1f}, {pos.y():.1f}, {pos.z():.1f}")
   ```
   then run it with `/glade script run hello`.
4. Install the VS Code extension (`vscode-extension/`) to edit and run
   scripts with autocomplete straight from your editor — see
   [`docs/vscode.md`](docs/vscode.md).

## Documentation

- [`docs/commands.md`](docs/commands.md) — every `/glade` subcommand.
- [`docs/scripting.md`](docs/scripting.md) — the Python `glade` API, threading
  model, sandbox, and humanization profiles.
- [`docs/vscode.md`](docs/vscode.md) — the VS Code extension and its wire
  protocol.
- [`docs/ui.md`](docs/ui.md) — the in-game liquid-glass UI and its current
  scope.
- [`docs/architecture.md`](docs/architecture.md) — module layout, package map,
  and how to build/contribute.

## Safety and ethics — read this

Glade's humanizer is **best-effort obfuscation, not a guarantee of
undetectability.** It varies rotation trajectories, timing distributions, and
(via Baritone settings) movement behavior instead of using one fixed
randomization curve — but no client-side humanization defeats a determined
anti-cheat, and none of it changes the fact that automating actions on a
server **may violate that server's rules or terms of service**, independent
of how convincing the automation looks. You are responsible for knowing and
following the rules of any server you connect to. Use this mod responsibly,
and expect it to be detected if a server actively looks for it.
