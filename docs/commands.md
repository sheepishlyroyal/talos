# `/glade` commands

All commands are **client commands** (registered via
`ClientCommandRegistrationCallback`), so they work without any
permission/operator level and produce no server-side effect beyond whatever
the underlying game action does. Source: `glade-mod/src/main/java/dev/glade/client/command/GladeCommands.java`.

## `/glade find block <blockPredicate> [radius]`

Scans loaded chunks outward from the player for the nearest block matching
`blockPredicate` and reports its position in chat.

- `blockPredicate` — a block/blockstate predicate (Brigadier's block-predicate
  argument type — same syntax as vanilla commands like `/execute if block`,
  e.g. `minecraft:diamond_ore` or `minecraft:chest[facing=north]`).
- `radius` — optional, `1`–`64`. Defaults to your current render distance
  (view distance setting).

```
/glade find block minecraft:diamond_ore
/glade find block minecraft:chest[facing=north] 48
```

Only one scan runs at a time — a second `find` while one is in progress
replies with "A world scan is already running." No match replies with
"No match found."

## `/glade goto ...`

Paths the player to a destination through the active `PathingEngine`
(Baritone, if installed; otherwise every call fails with a typed
"Baritone is not installed" error — see the README's install section).

### `/glade goto <x> <y> <z>`
Path to an exact block position.

### `/glade goto near <x> <y> <z> <range>`
Path to within `range` blocks of a position (`range` ≥ 0).

### `/glade goto xz <x> <z>`
Path to an X/Z column, ignoring Y.

```
/glade goto 100 64 200
/glade goto near 100 64 200 3
/glade goto xz 100 200
```

Replies `Pathing started`, then `Arrived` or `Pathing failed: <detail>` once
the path resolves.

## `/glade glow <x> <y> <z> [seconds]`

Draws a highlighted wireframe box around the block at that position for
`seconds` (`1`–`3600`, default defined by `GlowCommand.DEFAULT_SECONDS`).
Useful for confirming what `find`/scripted lookups actually resolved to.

```
/glade glow 100 64 200
/glade glow 100 64 200 30
```

## `/glade mine <x> <y> <z>`

Breaks the block at that position through the humanized `BreakBlockAction`
state machine (tool-aware, aborts if the target changes underneath it).
Replies with progress/result feedback; failures report why (e.g. no
line-of-sight, tool change, out of reach).

```
/glade mine 100 64 200
```

## `/glade place <x> <y> <z>`

Places whatever block is on your currently-held hotbar slot at that position
through the humanized `PlaceBlockAction` state machine, then verifies the
placement against the authoritative server-reported block state.

```
/glade place 100 65 200
```

## `/glade kill nearest [radius]`

Attacks and kills the nearest living `HostileEntity` within `radius` blocks
(`1.0`–`64.0`, default `6.0`), selecting a weapon via `WeaponSelector` and
tracking the target through `KillEntityAction` until it's dead or the target
becomes invalid. Replies "No hostile entity found within N blocks" if none is
in range.

```
/glade kill nearest
/glade kill nearest 12
```

## `/glade ui`

Opens the liquid-glass settings screen (`GladeScreen`) — currently a single
panel with a Dark/Light theme toggle and a close button. See
[`docs/ui.md`](ui.md).

```
/glade ui
```

## `/glade script run <name>`

Starts the named script from `.minecraft/glade/scripts/<name>.py` (the `.py`
suffix is optional on the command line) on the script engine's worker thread.
Replies immediately with "Started script: `<name>`", then "Script finished:
`<name>`" or "Script failed: `<error>`" asynchronously when it completes. See
[`docs/scripting.md`](scripting.md) for the execution model.

```
/glade script run hello
/glade script run auto_mine.py
```

## `/glade script stop`

Stops whatever script is currently running (hard-stops the GraalPy context,
even mid-`while True` loop with no yield points) and replies
"Stopped script engine".

```
/glade script stop
```

## `/glade bridge allow`

Allows the currently-connected VS Code extension session to push and run
scripts for the rest of this game session, without needing an explicit
per-push confirmation. Replies "VS Code bridge allowed for this session", or
"Glade bridge is not running" if the WebSocket server failed to start (e.g.
port already in use). See [`docs/vscode.md`](vscode.md) for the security
model this fits into.

```
/glade bridge allow
```

## `/glade bridge status`

Reports the bridge's current state (running/not running, connection state)
in chat.

```
/glade bridge status
```

## Not implemented yet

`/glade editor` (native block editor) and a `/glade config` command do not
exist in the current codebase — settings are edited by hand in
`config/glade.json` or (partially) from the `/glade ui` panel. See the
README's "Current status" section.
