# VS Code extension

`vscode-extension/` lets you write Glade scripts in VS Code and run them
directly inside a running Minecraft client, with logs streamed back live.
It talks to the mod's built-in WebSocket server (`GladeWebSocketServer`,
started by `GladeBridge.start()` on client init) over a small JSON protocol.

## Install (development)

```bash
cd vscode-extension
npm install
npm run compile
```

Then open `vscode-extension/` in VS Code and press **F5** — this launches an
Extension Development Host with Glade active. To produce a shareable
`.vsix`:

```bash
npx vsce package   # requires vsce: npm install -g @vscode/vsce, or let npx fetch it
```

## Pairing / the `~/.glade/token` file

On startup, the mod writes a fresh, random, per-session token to
`~/.glade/token` (`%USERPROFILE%\.glade\token` on Windows) — a file only your
local OS user account can read — and starts its WebSocket server on
`127.0.0.1:43077`. The extension reads that same file and sends the token as
the **first frame** on every new connection (an `auth` message), never as
part of the connection URL. The server processes no other frame type until
that `auth` frames succeeds.

Because the token is regenerated every time Minecraft starts, you don't need
to configure anything by hand — just have both the game and VS Code running
on the same machine and the extension will authenticate automatically on
connect/reconnect.

## Running a script

1. Launch Minecraft with the Glade mod loaded.
2. Open a `.py` script in VS Code.
3. Run **Glade: Run Script in Minecraft** from the command palette, or press
   **Cmd+Alt+Enter** (macOS) / **Ctrl+Alt+Enter** (Windows/Linux). The
   extension connects if needed, authenticates, pushes the file's contents
   as a named script, and runs it.
4. Output streams live into the **Glade** output channel (revealed
   automatically).
5. Run **Glade: Stop Script** to stop whatever's running.
6. The status bar shows connection state — `●` connected, `○`
   disconnected/reconnecting, a spinner while connecting — click it any time
   to trigger **Glade: Reconnect**.

## Editor autocomplete

Scripts run inside the mod's embedded `glade` module (`goto`, `find_block`,
`place_block`, `kill_nearest`, `player_pos`, `@glade.on(...)`, etc. — see
[`docs/scripting.md`](scripting.md) for the full API). The extension ships
type stubs at `vscode-extension/stubs/glade.pyi` purely for editor tooling —
drop that file next to your scripts, or point `python.analysis.stubPath` at
the `stubs/` folder, to get Pylance autocomplete and type checking. See the
stub file's own docstring for both setup options.

## Configuration

| Setting | Default | Description |
|---|---|---|
| `glade.host` | `127.0.0.1` | Must be a loopback address — the extension refuses anything else, in code. |
| `glade.port` | `43077` | Port the mod's WebSocket server listens on. |
| `glade.tokenPath` | `~/.glade/token` | Path to the per-session auth token file. `~` expands to your home directory. |

## Security model

Pushing a script to the mod is equivalent to remote code execution inside
your running game client, so the connection is deliberately hardened the way
any localhost-RPC-with-code-execution surface should be:

- **Loopback only.** The extension only ever connects to `127.0.0.1`,
  `localhost`, or `::1` — enforced in code, not just default config. The
  server likewise must never bind to a non-loopback interface.
- **Origin-checked.** Any web page open in a normal browser can attempt to
  open a WebSocket to `127.0.0.1:43077`; the server validates the `Origin`
  header on the upgrade request and rejects connections that don't look like
  they came from VS Code. This is a **CSWSH** (Cross-Site WebSocket
  Hijacking)-hardened design.
- **Token-gated, not URL-gated.** The per-session token lives in
  `~/.glade/token` and is sent as the first WebSocket frame, never in the
  connection URL/query string — URLs end up in logs and other places a raw
  socket handshake doesn't.
- **No frame is acted on before authentication succeeds** — server-side
  contract, see `PROTOCOL.md`.
- **No silent auto-replay.** If the connection drops, the extension
  reconnects and re-authenticates in the background (capped exponential
  backoff), but it will **never** automatically re-push or re-run your last
  script. Every run is a deliberate action from inside VS Code.

**Don't run untrusted scripts.** A `.py` file pushed through this extension
has the exact same capabilities as any other Glade script — treat it with
the same caution you'd give any code that controls your game client. See
[`docs/scripting.md`](scripting.md#sandbox) for what the GraalPy sandbox does
and does not restrict.

## Protocol summary

Full spec: `vscode-extension/PROTOCOL.md`; canonical TypeScript types:
`vscode-extension/src/protocol.ts`. Every frame is `{ "v": 1, "type": "...", ...fields }`.

**Client → Server:** `auth{token}`, `push_script{name,source}`, `run{name}`, `stop`

**Server → Client:** `auth_ok`, `auth_err{reason}`,
`log{level,text}`, `status{state}`, `script_done{success,message?}`

```
C→S  {"v":1,"type":"auth","token":"a1b2c3..."}
S→C  {"v":1,"type":"auth_ok"}
C→S  {"v":1,"type":"push_script","name":"farm.py","source":"..."}
C→S  {"v":1,"type":"run","name":"farm.py"}
S→C  {"v":1,"type":"status","state":"running"}
S→C  {"v":1,"type":"log","level":"info","text":"Heading to farm..."}
S→C  {"v":1,"type":"script_done","success":true}
```

## Project layout

```
vscode-extension/
  package.json        extension manifest (commands, keybinding, settings)
  tsconfig.json
  src/
    extension.ts       activation, commands, status bar, output channel
    connection.ts       GladeConnection — WebSocket + reconnect/backoff
    protocol.ts          shared message types (mirrored by the Java server)
  stubs/
    glade.pyi             Python type stubs for the in-game `glade` API
  PROTOCOL.md
```

Running a script from VS Code pushes the current buffer's contents directly
over the socket (`push_script`, writing it into
`.minecraft/glade/scripts/<name>.py` server-side) rather than requiring the
file to already exist there, so unsaved changes run too. The **first** time
a session tries to `run` a pushed script, the server holds the run and posts
a chat message — `VS Code wants to run <name> — /glade bridge allow to
accept` — until you run `/glade bridge allow` in-game; every run after that
for the rest of the game session executes immediately.
