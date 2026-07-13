/**
 * Talos WebSocket protocol — shared contract between the VS Code extension
 * (client) and the Talos Fabric mod's embedded WebSocket server (host).
 *
 * This file is the canonical TypeScript definition. The Java server mirrors
 * these exact message shapes and field names — keep the two in lockstep.
 * See PROTOCOL.md for the human-readable spec and rationale.
 *
 * Every message is a JSON object with an envelope `v` (protocol version,
 * currently 1) and `type` (discriminant). Never send bare payloads.
 */

export const PROTOCOL_VERSION = 1 as const;

// ---------------------------------------------------------------------------
// Client -> Server (C2S)
// ---------------------------------------------------------------------------

/**
 * Must be the FIRST message sent on every connection. The token is read
 * from the local token file (~/.talos/token) and is never placed in the
 * WebSocket URL/query string (CSWSH hardening — query strings leak into
 * logs, referrers, and browser history-equivalents).
 */
export interface AuthMessage {
    v: 1;
    type: 'auth';
    token: string;
}

/** Uploads (or replaces) a named script's source. Does not run it. */
export interface PushScriptMessage {
    v: 1;
    type: 'push_script';
    name: string;
    source: string;
}

/** Runs a previously pushed script by name. */
export interface RunMessage {
    v: 1;
    type: 'run';
    name: string;
}

/** Stops whatever script is currently running. */
export interface StopMessage {
    v: 1;
    type: 'stop';
}

export type ClientToServerMessage =
    | AuthMessage
    | PushScriptMessage
    | RunMessage
    | StopMessage;

// ---------------------------------------------------------------------------
// Server -> Client (S2C)
// ---------------------------------------------------------------------------

/** Sent in response to a valid `auth` message. */
export interface AuthOkMessage {
    v: 1;
    type: 'auth_ok';
}

/** Sent in response to an invalid/missing/expired token, then the socket closes. */
export interface AuthErrMessage {
    v: 1;
    type: 'auth_err';
    reason: string;
}

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

/** A single line of log output streamed from the running script. */
export interface LogMessage {
    v: 1;
    type: 'log';
    level: LogLevel;
    text: string;
}

export type RunState = 'idle' | 'running' | 'stopped';

/** Server-driven run-state change, independent of log lines. */
export interface StatusMessage {
    v: 1;
    type: 'status';
    state: RunState;
}

/** Sent once when a script finishes (successfully, on error, or via stop). */
export interface ScriptDoneMessage {
    v: 1;
    type: 'script_done';
    success: boolean;
    message?: string;
}

export type ServerToClientMessage =
    | AuthOkMessage
    | AuthErrMessage
    | LogMessage
    | StatusMessage
    | ScriptDoneMessage;

export type TalosMessage = ClientToServerMessage | ServerToClientMessage;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

export function makeAuth(token: string): AuthMessage {
    return { v: PROTOCOL_VERSION, type: 'auth', token };
}

export function makePushScript(name: string, source: string): PushScriptMessage {
    return { v: PROTOCOL_VERSION, type: 'push_script', name, source };
}

export function makeRun(name: string): RunMessage {
    return { v: PROTOCOL_VERSION, type: 'run', name };
}

export function makeStop(): StopMessage {
    return { v: PROTOCOL_VERSION, type: 'stop' };
}

/**
 * Narrow, defensive parse of an incoming raw WebSocket payload into a
 * ServerToClientMessage. Returns undefined for anything that doesn't match
 * the envelope shape — callers should ignore unrecognized frames rather
 * than throw, so the protocol can grow without breaking old clients.
 */
export function parseServerMessage(raw: string): ServerToClientMessage | undefined {
    let obj: unknown;
    try {
        obj = JSON.parse(raw);
    } catch {
        return undefined;
    }
    if (typeof obj !== 'object' || obj === null) {
        return undefined;
    }
    const msg = obj as Record<string, unknown>;
    if (msg.v !== PROTOCOL_VERSION || typeof msg.type !== 'string') {
        return undefined;
    }
    switch (msg.type) {
        case 'auth_ok':
            return { v: 1, type: 'auth_ok' };
        case 'auth_err':
            return { v: 1, type: 'auth_err', reason: typeof msg.reason === 'string' ? msg.reason : 'unknown' };
        case 'log':
            if (typeof msg.text !== 'string') return undefined;
            return {
                v: 1,
                type: 'log',
                level: (['debug', 'info', 'warn', 'error'] as const).includes(msg.level as LogLevel)
                    ? (msg.level as LogLevel)
                    : 'info',
                text: msg.text,
            };
        case 'status':
            if (typeof msg.state !== 'string') return undefined;
            return { v: 1, type: 'status', state: msg.state as RunState };
        case 'script_done':
            return {
                v: 1,
                type: 'script_done',
                success: Boolean(msg.success),
                message: typeof msg.message === 'string' ? msg.message : undefined,
            };
        default:
            return undefined;
    }
}
