# VS Code bridge

The `vscode-extension/` pushes and runs Python scripts against the mod over a local WebSocket, with live
log streaming into a "Talos" output channel and Pylance autocomplete via the bundled `talos.pyi` stubs.

## Features

- **Run Script in Minecraft** (Cmd/Ctrl+Alt+Enter) — pushes the active file and runs it in-game.
- **Stop Script**, **Reconnect**.
- **Run-on-save live reload** (`talos.runOnSave`).
- Status-bar connection indicator.
- Clickable Python tracebacks — jump to `your/script.py:line`, including `require`'d libraries.

## Compatible editors

The extension uses **only stable VS Code APIs** plus a plain `ws` WebSocket — no proposed or proprietary
APIs — so the same `.vsix` runs unmodified on any VS Code-compatible host at engine `^1.85.0`:

| Fully supported | |
|---|---|
| **VS Code** | reference target |
| **Cursor** · **Windsurf** · **VSCodium** · **Trae** · **Google Antigravity** | VS Code forks |
| **Eclipse Theia** | via Open VSX |
| **code-server / Gitpod / GitHub Codespaces** | browser-hosted VS Code |

**Not supported** (different extension systems — would need a native client against the WebSocket protocol
in `vscode-extension/PROTOCOL.md`): JetBrains IDEs, Zed, Neovim/Vim, Sublime Text, Emacs.

Install: `<editor> --install-extension talos-<version>.vsix --force` (`code`, `cursor`, `windsurf`,
`codium`, …) or **Extensions → "Install from VSIX…"**. Talos isn't on a marketplace, so sideload the
`.vsix` directly.

## Enable the bridge in-game

Run `/talos bridge allow` for the session (check state with `/talos bridge status`).

## Configuration

| Setting | Default | Description |
|---|---|---|
| `talos.host` | `127.0.0.1` | Must be loopback — the extension refuses anything else. |
| `talos.port` | `43077` | Port the mod's WebSocket server listens on. |
| `talos.tokenPath` | `~/.talos/token` | Per-session auth token file. |
| `talos.runOnSave` | `false` | Re-push and re-run on save (live reload). |

## Security

Pushing a script is **remote code execution inside your game client**, so the bridge is hardened:

- **binds loopback only** (`127.0.0.1`),
- **token-gated** — a random per-session token in a file only your OS user can read, sent as the **first
  WebSocket frame** (never in the URL),
- **acts on no frame before auth succeeds**,
- **never auto-replays** a script on reconnect.

**Don't run untrusted scripts.**
