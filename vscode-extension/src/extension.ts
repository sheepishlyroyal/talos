import * as vscode from 'vscode';
import { GladeConnection, ConnectionState } from './connection';
import { makePushScript, makeRun, makeStop, ServerToClientMessage } from './protocol';

let connection: GladeConnection | undefined;
let statusBarItem: vscode.StatusBarItem;
let outputChannel: vscode.OutputChannel;
/** Name of the script currently pushed/running, so stop/status messages read naturally. */
let activeScriptName: string | undefined;

export function activate(context: vscode.ExtensionContext): void {
    outputChannel = vscode.window.createOutputChannel('Glade');
    context.subscriptions.push(outputChannel);

    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    statusBarItem.command = 'glade.reconnect';
    context.subscriptions.push(statusBarItem);
    renderStatusBar('disconnected');
    statusBarItem.show();

    context.subscriptions.push(
        vscode.commands.registerCommand('glade.runScript', () => void runScript()),
        vscode.commands.registerCommand('glade.stopScript', () => void stopScript()),
        vscode.commands.registerCommand('glade.reconnect', () => void reconnect())
    );

    context.subscriptions.push({
        dispose: () => {
            connection?.dispose();
            connection = undefined;
        },
    });
}

export function deactivate(): void {
    connection?.dispose();
    connection = undefined;
}

function config() {
    const cfg = vscode.workspace.getConfiguration('glade');
    return {
        host: cfg.get<string>('host', '127.0.0.1'),
        port: cfg.get<number>('port', 43077),
        tokenPath: cfg.get<string>('tokenPath', '~/.glade/token'),
    };
}

/** Lazily creates the connection object. Does not open a socket by itself. */
function getConnection(): GladeConnection {
    if (connection) {
        return connection;
    }
    const { host, port, tokenPath } = config();
    connection = new GladeConnection({
        host,
        port,
        tokenPath,
        onStateChange: (state) => renderStatusBar(state),
        onMessage: (msg) => handleMessage(msg),
        onDiagnostic: (text) => outputChannel.appendLine(`[glade] ${text}`),
    });
    return connection;
}

/** Connects and resolves once authenticated ('connected'), or rejects if the
 *  connection settles into 'disconnected' before that happens. */
function connectAndWaitReady(conn: GladeConnection, timeoutMs = 10_000): Promise<void> {
    if (conn.getState() === 'connected') {
        return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            cleanup();
            reject(new Error('Timed out waiting for Glade connection.'));
        }, timeoutMs);

        // Poll state via the status bar callback isn't wired for one-shot
        // waits, so we piggyback by re-subscribing through a temporary
        // connection option is not possible post-construction; instead poll.
        const interval = setInterval(() => {
            const state = conn.getState();
            if (state === 'connected') {
                cleanup();
                resolve();
            } else if (state === 'disconnected') {
                cleanup();
                reject(new Error('Glade connection failed. Check that Minecraft is running with Glade loaded.'));
            }
        }, 100);

        function cleanup() {
            clearTimeout(timer);
            clearInterval(interval);
        }

        conn.connect();
    });
}

async function runScript(): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        void vscode.window.showErrorMessage('Glade: no active editor.');
        return;
    }
    const doc = editor.document;
    if (doc.languageId !== 'python' && !doc.fileName.endsWith('.py')) {
        void vscode.window.showErrorMessage('Glade: active file is not a Python (.py) script.');
        return;
    }

    const conn = getConnection();
    outputChannel.show(true);

    try {
        if (conn.getState() !== 'connected') {
            outputChannel.appendLine('[glade] Connecting...');
            await connectAndWaitReady(conn);
        }
    } catch (err) {
        void vscode.window.showErrorMessage(`Glade: ${errMessage(err)}`);
        return;
    }

    const name = scriptNameFor(doc.uri);
    const source = doc.getText();

    try {
        activeScriptName = name;
        conn.send(makePushScript(name, source));
        conn.send(makeRun(name));
        outputChannel.appendLine(`[glade] Running "${name}"...`);
    } catch (err) {
        void vscode.window.showErrorMessage(`Glade: failed to send script: ${errMessage(err)}`);
    }
}

async function stopScript(): Promise<void> {
    const conn = getConnection();
    if (conn.getState() !== 'connected') {
        void vscode.window.showWarningMessage('Glade: not connected.');
        return;
    }
    try {
        conn.send(makeStop());
        outputChannel.appendLine(`[glade] Stop requested${activeScriptName ? ` for "${activeScriptName}"` : ''}.`);
    } catch (err) {
        void vscode.window.showErrorMessage(`Glade: failed to send stop: ${errMessage(err)}`);
    }
}

/** Explicit, user-initiated (re)connect. This is the ONLY path that opens a
 *  socket after a prior disconnect — reconnects never happen silently as a
 *  side effect of anything else, and never automatically re-run a script. */
async function reconnect(): Promise<void> {
    // Tear down any existing connection/backoff timers and start clean so
    // settings changes (host/port/tokenPath) take effect immediately.
    connection?.dispose();
    connection = undefined;
    const conn = getConnection();
    outputChannel.appendLine('[glade] Reconnecting...');
    try {
        await connectAndWaitReady(conn);
        outputChannel.appendLine('[glade] Connected.');
    } catch (err) {
        void vscode.window.showErrorMessage(`Glade: ${errMessage(err)}`);
    }
}

function handleMessage(msg: ServerToClientMessage): void {
    switch (msg.type) {
        case 'log':
            outputChannel.appendLine(formatLog(msg.level, msg.text));
            break;
        case 'status':
            outputChannel.appendLine(`[glade] status: ${msg.state}`);
            break;
        case 'script_done':
            outputChannel.appendLine(
                `[glade] script ${msg.success ? 'finished' : 'failed'}${msg.message ? `: ${msg.message}` : ''}`
            );
            activeScriptName = undefined;
            break;
        default:
            break;
    }
}

function formatLog(level: string, text: string): string {
    return `[${level}] ${text}`;
}

function scriptNameFor(uri: vscode.Uri): string {
    const parts = uri.path.split('/');
    return parts[parts.length - 1] || 'script.py';
}

function renderStatusBar(state: ConnectionState): void {
    switch (state) {
        case 'connected':
            statusBarItem.text = '$(circle-filled) Glade';
            statusBarItem.tooltip = 'Glade: connected. Click to reconnect.';
            break;
        case 'connecting':
        case 'authenticating':
            statusBarItem.text = '$(sync~spin) Glade';
            statusBarItem.tooltip = 'Glade: connecting...';
            break;
        case 'reconnecting':
            statusBarItem.text = '$(circle-outline) Glade';
            statusBarItem.tooltip = 'Glade: reconnecting... Click to retry now.';
            break;
        case 'disconnected':
        default:
            statusBarItem.text = '$(circle-outline) Glade';
            statusBarItem.tooltip = 'Glade: disconnected. Click to connect.';
            break;
    }
}

function errMessage(err: unknown): string {
    if (err instanceof Error) return err.message;
    return String(err);
}
