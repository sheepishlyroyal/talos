import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import WebSocket from 'ws';
import {
    ClientToServerMessage,
    ServerToClientMessage,
    makeAuth,
    parseServerMessage,
} from './protocol';

export type ConnectionState = 'disconnected' | 'connecting' | 'authenticating' | 'connected' | 'reconnecting';

export interface GladeConnectionOptions {
    host: string;
    port: number;
    tokenPath: string;
    /** Called whenever the high-level connection state changes. */
    onStateChange: (state: ConnectionState) => void;
    /** Called for every parsed message coming from the server. */
    onMessage: (msg: ServerToClientMessage) => void;
    /** Called with human-readable diagnostic text (not game log output). */
    onDiagnostic?: (text: string) => void;
}

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30_000;
const BACKOFF_MULTIPLIER = 2;

/**
 * Wraps a single WebSocket connection to the Glade mod's localhost server.
 *
 * Security invariants enforced here (see PROTOCOL.md):
 *  - Only ever connects to ws://127.0.0.1:<port> or ws://<host>:<port> where
 *    host is whatever the user explicitly configured (default 127.0.0.1) —
 *    callers are responsible for keeping that setting loopback-only.
 *  - The auth token is read from a local file and sent as the FIRST frame
 *    over the socket, never embedded in the connection URL.
 *  - On unexpected disconnect this class will attempt to reconnect and
 *    re-authenticate with exponential backoff, but it will NOT resend any
 *    `push_script` / `run` frames automatically. Re-running a script after
 *    a reconnect is always a fresh, explicit user action (see extension.ts).
 */
export class GladeConnection {
    private ws: WebSocket | undefined;
    private state: ConnectionState = 'disconnected';
    private backoffMs = INITIAL_BACKOFF_MS;
    private reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    private disposed = false;
    /** True once the user has explicitly asked us to be connected. Reconnect
     *  attempts only happen while this is true; calling dispose() or stop()
     *  clears it so a dropped socket doesn't silently keep retrying forever
     *  after the user has moved on. */
    private wantConnected = false;

    constructor(private readonly options: GladeConnectionOptions) {}

    getState(): ConnectionState {
        return this.state;
    }

    /** Resolves the configured token file path, expanding ~ to the home dir. */
    static resolveTokenPath(configuredPath: string): string {
        if (configuredPath.startsWith('~')) {
            return path.join(os.homedir(), configuredPath.slice(1));
        }
        return configuredPath;
    }

    private readToken(): string {
        const resolved = GladeConnection.resolveTokenPath(this.options.tokenPath);
        const raw = fs.readFileSync(resolved, 'utf8');
        return raw.trim();
    }

    /** Opens a fresh connection and authenticates. Safe to call when already connected (no-op). */
    connect(): void {
        if (this.state === 'connected' || this.state === 'connecting' || this.state === 'authenticating') {
            return;
        }
        this.disposed = false;
        this.wantConnected = true;
        this.backoffMs = INITIAL_BACKOFF_MS;
        if (this.reconnectTimer) {
            // A backoff retry was already scheduled (state 'reconnecting');
            // this explicit connect() supersedes it so we don't open two sockets.
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = undefined;
        }
        this.openSocket();
    }

    private openSocket(): void {
        if (this.disposed || !this.wantConnected) {
            return;
        }

        let token: string;
        try {
            token = this.readToken();
        } catch (err) {
            this.diagnostic(
                `Could not read Glade token file at ${GladeConnection.resolveTokenPath(this.options.tokenPath)}: ${errMessage(err)}`
            );
            this.setState('disconnected');
            this.wantConnected = false;
            return;
        }

        if (token.length === 0) {
            this.diagnostic('Glade token file is empty.');
            this.setState('disconnected');
            this.wantConnected = false;
            return;
        }

        // Hard pin to loopback semantics: only 127.0.0.1 / localhost / ::1 are
        // accepted regardless of configuration drift, since a script push is
        // effectively remote code execution in the running game client.
        const host = this.options.host;
        if (!isLoopbackHost(host)) {
            this.diagnostic(`Refusing to connect to non-loopback host "${host}". Glade only connects to 127.0.0.1.`);
            this.setState('disconnected');
            this.wantConnected = false;
            return;
        }

        const url = `ws://${host}:${this.options.port}/`;
        this.setState('connecting');

        const ws = new WebSocket(url);
        this.ws = ws;

        ws.on('open', () => {
            if (this.disposed) return;
            this.setState('authenticating');
            this.rawSend(makeAuth(token));
        });

        ws.on('message', (data) => {
            if (this.disposed) return;
            const text = typeof data === 'string' ? data : data.toString('utf8');
            const msg = parseServerMessage(text);
            if (!msg) {
                this.diagnostic(`Ignoring unrecognized frame: ${text.slice(0, 200)}`);
                return;
            }
            if (msg.type === 'auth_ok') {
                this.backoffMs = INITIAL_BACKOFF_MS;
                this.setState('connected');
                return;
            }
            if (msg.type === 'auth_err') {
                this.diagnostic(`Authentication failed: ${msg.reason}`);
                this.wantConnected = false;
                ws.close();
                return;
            }
            this.options.onMessage(msg);
        });

        ws.on('error', (err) => {
            if (this.disposed) return;
            this.diagnostic(`Connection error: ${errMessage(err)}`);
        });

        ws.on('close', () => {
            if (this.disposed) return;
            this.ws = undefined;
            if (this.wantConnected) {
                this.scheduleReconnect();
            } else {
                this.setState('disconnected');
            }
        });
    }

    private scheduleReconnect(): void {
        this.setState('reconnecting');
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
        }
        const delay = this.backoffMs;
        this.backoffMs = Math.min(this.backoffMs * BACKOFF_MULTIPLIER, MAX_BACKOFF_MS);
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = undefined;
            this.openSocket();
        }, delay);
    }

    private rawSend(msg: ClientToServerMessage): void {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            throw new Error('Glade: not connected');
        }
        this.ws.send(JSON.stringify(msg));
    }

    /** Sends a message. Throws if not fully connected (authenticated). */
    send(msg: ClientToServerMessage): void {
        if (this.state !== 'connected') {
            throw new Error('Glade: cannot send, not connected');
        }
        this.rawSend(msg);
    }

    /** Explicitly stops trying to stay connected and closes any open socket. No auto-reconnect afterward. */
    disconnect(): void {
        this.wantConnected = false;
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = undefined;
        }
        this.ws?.close();
        this.ws = undefined;
        this.setState('disconnected');
    }

    dispose(): void {
        this.disposed = true;
        this.disconnect();
    }

    private setState(state: ConnectionState): void {
        if (this.state === state) return;
        this.state = state;
        this.options.onStateChange(state);
    }

    private diagnostic(text: string): void {
        this.options.onDiagnostic?.(text);
    }
}

function isLoopbackHost(host: string): boolean {
    return host === '127.0.0.1' || host === 'localhost' || host === '::1';
}

function errMessage(err: unknown): string {
    if (err instanceof Error) return err.message;
    return String(err);
}
