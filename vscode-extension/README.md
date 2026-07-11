# Glade Scripting — VS Code extension

Write Python automation scripts for the Glade Minecraft Fabric client mod
and run them straight from VS Code. The extension pushes your script over a
local WebSocket to the running game client and streams its logs back into a
"Glade" output channel.

## Install (development)

1. `cd vscode-extension && npm install && npm run compile`
2. Open this folder in VS Code and press **F5** — this launches an Extension
   Development Host with Glade active.
3. To produce a shareable `.vsix`: `npx vsce package` (requires `vsce`;
   `npm install -g @vscode/vsce` or `npx` will fetch it on demand).

## Usage

1. Launch Minecraft with the Glade mod loaded. On startup the mod writes a
   fresh per-session token to `~/.glade/token` (`%USERPROFILE%\.glade\token`
   on Windows) and starts its WebSocket server on `127.0.0.1:43077`.
2. Open a `.py` script in VS Code.
3. Run **Glade: Run Script in Minecraft** from the command palette, or
   press **Cmd+Alt+Enter** / **Ctrl+Alt+Enter**. The extension connects
   (if not already connected), authenticates, pushes your script, and runs
   it. Output streams live into the **Glade** output channel, which is
   revealed automatically.
4. Run **Glade: Stop Script** to stop whatever's running.
5. The status bar shows connection state (`●` connected, `○`
   disconnected/reconnecting, spinner while connecting) — click it to
   trigger **Glade: Reconnect** at any time.

### Autocomplete for the `glade` API

Python scripts run inside the mod's embedded runtime with a `glade` module
already in scope (`goto`, `find_block`, `place_block`, `kill_nearest`,
`player_pos`, the `@glade.on(...)` event decorator, etc.). This extension
ships type stubs at [`stubs/glade.pyi`](./stubs/glade.pyi) purely for editor
tooling — drop that file next to your scripts, or point
`python.analysis.stubPath` at the `stubs/` folder, to get Pylance
autocomplete and type checking. See the stub file's docstring for both
options.

## Configuration

| Setting | Default | Description |
|---|---|---|
| `glade.host` | `127.0.0.1` | Must be a loopback address. The extension refuses anything else. |
| `glade.port` | `43077` | Port the mod's WebSocket server listens on. |
| `glade.tokenPath` | `~/.glade/token` | Path to the per-session auth token file. `~` expands to your home directory. |

## Security

Pushing a script to the mod is equivalent to remote code execution inside
your running game client, so this is designed like any localhost-RPC
surface that can execute code should be:

- The extension **only ever connects to loopback** (`127.0.0.1` /
  `localhost` / `::1`) — this is enforced in code, not just by default
  configuration.
- **Token-gated.** The mod writes a random per-session token to a file only
  your local OS user account can read. The extension reads that file and
  sends the token as the very first WebSocket frame (an `auth` message) —
  it is never placed in the connection URL/query string, since URLs can
  leak into logs and other places a raw socket connection doesn't.
- **No frame is acted on before authentication succeeds** (server-side
  contract — see [`PROTOCOL.md`](./PROTOCOL.md)).
- **No silent auto-replay.** If the connection drops, the extension
  reconnects and re-authenticates in the background (with capped
  exponential backoff), but it will **never** automatically re-push or
  re-run your last script. Every run is a deliberate action you take from
  VS Code.

**Don't run untrusted scripts.** A `.py` file you push through this
extension has the same capabilities as any other Glade script — treat it
with the same caution you'd give any code that controls your game client.

## Protocol

The extension and the mod's WebSocket server speak a small versioned JSON
protocol over a single connection. Full spec and rationale:
[`PROTOCOL.md`](./PROTOCOL.md); canonical TypeScript types:
[`src/protocol.ts`](./src/protocol.ts).

Summary:

- **C2S:** `auth{token}`, `push_script{name,source}`, `run{name}`, `stop`
- **S2C:** `auth_ok`, `auth_err{reason}`, `log{level,text}`,
  `status{state}`, `script_done{success,message?}`

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
