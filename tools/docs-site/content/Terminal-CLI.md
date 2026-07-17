# Terminal CLI (`talos`)

`cli/talos` is a dependency-free Python 3 command that drives the running game from any terminal.
It speaks the same loopback WebSocket bridge as the VS Code extension
([protocol](https://github.com/sheepishlyroyal/talos/blob/main/vscode-extension/PROTOCOL.md)), so
script output (`print`, `talos.log`, tracebacks) streams live back into your shell.

## Install

- **Automatic:** the VS Code extension bundles the CLI and (re)installs it to `~/.talos/bin/talos`
  every time it activates. Command palette: *Talos: Install Terminal CLI* runs it loudly.
  Put it on your PATH once: `export PATH="$HOME/.talos/bin:$PATH"` in `~/.zshrc`/`~/.bashrc`.
  On Windows the extension also writes `talos.cmd` — add `%USERPROFILE%\.talos\bin` to PATH.
- **Manual:** copy [`cli/talos`](https://github.com/sheepishlyroyal/talos/blob/main/cli/talos)
  anywhere on your PATH and `chmod +x` it. Python 3.8+ only — no pip packages.

## Usage

```bash
talos harvest.py wheat 64        # push a local file into the game and run it
talos run harvest.py wheat 64    # same, explicit
talos py -c 'talos.log("hi")'    # one-liner; trailing expression echoes its repr
talos -c 'talos.player_feet()'   # same (py / python / python3 are aliases)
talos run 'import talos;talos.log("hi")'   # inline code: anything that isn't a filename
talos stop                       # hard-stop the running script
talos status                     # bridge reachability + run state
talos logs -f                    # follow the newest ~/.talos/logs/session-*.log
```

Options (must come **before** the script filename): `--port N` · `--token FILE` · `--no-color`.

## How arguments must be written

- Everything **after the script filename** is a script argument:
  `talos farm.py wheat 64 --fast` → `talos.args == ["wheat", "64", "--fast"]` — `--fast` there
  belongs to your script, not the CLI.
- Args always arrive as **strings**; convert yourself (`int(talos.args[1])`).
- Quote spaces in the shell: `talos greet.py "hello world"` arrives as one argument. In-game
  `/talos script run` is whitespace-split only — the CLI is the way to pass args containing spaces.
- Script filenames may only use letters, digits, `_`, `.`, `-` and must end in `.py`. The file is
  pushed under its **basename** into `.minecraft/talos/scripts/`, overwriting a same-named script.

## Behaviour

- First terminal run requires a one-time `/talos bridge allow` in-game (persisted); the CLI prints
  the prompt and waits, then runs automatically once allowed.
- `Ctrl-C` sends a hard-stop into the game before exiting.
- Exit codes: `0` script succeeded · `1` script raised/failed · `2` usage error · `3` bridge
  unreachable or auth failed — so `talos selftest.py && echo PASS` works as a test harness (see
  [Architecture & Testing](Architecture-and-Testing)).
- `talos logs [-f]` reads the session log directly and works with the game closed.

## Security

Same model as the VS Code bridge: loopback only, per-launch token from `~/.talos/token` sent as the
first frame (never in the URL), nothing processed before `auth_ok`, browser origins rejected, and
the one-time in-game `/talos bridge allow` gate.
