# Talos WebSocket Protocol (v1)

This document is the human-readable companion to
[`src/protocol.ts`](./src/protocol.ts), which is the canonical machine
definition. The Java-side WebSocket server implemented in the Talos Fabric
mod must mirror these message shapes and field names exactly. If the two
drift, bump `PROTOCOL_VERSION` on both sides and reject mismatched versions.

## Transport

- The mod runs a WebSocket server bound to **loopback only**:
  `ws://127.0.0.1:43077` by default (configurable port, host is not meant to
  ever be anything but loopback — see Security below).
- Every frame is a single JSON text message (no binary frames, no
  multi-frame messages).
- Every message is an object with an envelope:
  ```json
  { "v": 1, "type": "<message-type>", ...fields }
  ```
  `v` is the protocol version (currently always `1`). `type` is the
  discriminant. Unknown `type` values / mismatched `v` should be ignored by
  the receiver, not treated as fatal — this lets the protocol grow forward
  without breaking older clients or servers.

## Security model (read this before implementing the server)

Pushing a script is equivalent to remote code execution inside the running
game client, so the connection is treated the same way any
localhost-RPC-with-code-execution surface should be treated (this is a
**CSWSH** — Cross-Site WebSocket Hijacking — hardened design):

1. **Loopback only.** The server must not bind to `0.0.0.0` or any
   non-loopback interface. The extension additionally refuses to connect to
   any host string other than `127.0.0.1`, `localhost`, or `::1`, regardless
   of user configuration.
2. **Origin checks.** Because any web page a user has open in a normal
   browser can attempt to open a WebSocket to `127.0.0.1:43077`, the server
   MUST validate the `Origin` header on the HTTP upgrade request and reject
   connections that don't look like they came from VS Code (e.g. no
   `Origin` header, or an origin the server maintainer explicitly allows).
   Browsers cannot forge a missing `Origin` header the way a permissive
   check might assume — verify empirically against the target VS Code
   Electron version before relying on this alone.
3. **Token auth, not in the URL.** The mod writes a random per-session
   token to a file only the local OS user can read
   (`~/.talos/token`, i.e. `os.homedir()/.talos/token`, which on Windows is
   `%USERPROFILE%\.talos\token`). The client reads that file and sends it
   in the **first** message on the socket, an `auth` frame. It is **never**
   placed in the connection URL or query string, because URLs end up in
   logs, browser history-equivalents, and are visible to anything that can
   observe the TCP handshake path (e.g. a proxy) even over plaintext ws.
4. **No frame is processed before `auth_ok`.** The server must not act on
   `push_script`/`run`/`stop` until the connection has successfully
   authenticated. Send `auth_err` and close the socket on bad/missing
   token.
5. **No silent replay on reconnect.** If the socket drops and the client
   reconnects (with backoff), it re-authenticates but does **not**
   automatically re-send the last `push_script`/`run`. Re-running a script
   is always a fresh, explicit user action from inside VS Code. This is
   enforced client-side in `src/connection.ts` / `src/extension.ts` — the
   connection object never caches "last script" for auto-replay.

## Message types

### Client → Server (C2S)

| type | fields | purpose |
|---|---|---|
| `auth` | `token: string` | Must be the first frame sent on every connection. |
| `push_script` | `name: string, source: string` | Uploads/replaces a named script's source. Does not run it. |
| `run` | `name: string` | Runs a previously pushed script by name. |
| `stop` | — | Stops whatever script is currently running. |

### Server → Client (S2C)

| type | fields | purpose |
|---|---|---|
| `auth_ok` | — | Sent once, in response to a valid `auth` frame. |
| `auth_err` | `reason: string` | Sent in response to an invalid/missing/expired token; server then closes the socket. |
| `log` | `level: "debug"\|"info"\|"warn"\|"error", text: string` | One line of log output streamed from the running script. |
| `status` | `state: "idle"\|"running"\|"stopped"` | Server-driven run-state change, independent of log lines. |
| `script_done` | `success: boolean, message?: string` | Sent once when a script finishes — success, error, or via `stop`. |

## Example exchange

```
C→S  {"v":1,"type":"auth","token":"a1b2c3..."}
S→C  {"v":1,"type":"auth_ok"}
C→S  {"v":1,"type":"push_script","name":"farm.py","source":"..."}
C→S  {"v":1,"type":"run","name":"farm.py"}
S→C  {"v":1,"type":"status","state":"running"}
S→C  {"v":1,"type":"log","level":"info","text":"Heading to farm..."}
S→C  {"v":1,"type":"log","level":"info","text":"Broke 12 blocks"}
S→C  {"v":1,"type":"script_done","success":true}
```

## Versioning

Bump `PROTOCOL_VERSION` (both `src/protocol.ts` and the Java mirror) on any
breaking change to an existing message's fields. Purely additive changes
(new optional field, new message `type`) do not require a version bump
since both sides already ignore unrecognized fields/types defensively —
but document them here regardless.
