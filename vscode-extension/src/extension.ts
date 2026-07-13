import * as vscode from 'vscode';
import { TalosConnection, ConnectionState } from './connection';
import { makePushScript, makeRun, makeStop, ServerToClientMessage } from './protocol';

let connection: TalosConnection | undefined;
let statusBarItem: vscode.StatusBarItem;
let outputChannel: vscode.OutputChannel;
/** Name of the script currently pushed/running, so stop/status messages read naturally. */
let activeScriptName: string | undefined;

export function activate(context: vscode.ExtensionContext): void {
    outputChannel = vscode.window.createOutputChannel('Talos');
    context.subscriptions.push(outputChannel);

    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    statusBarItem.command = 'talos.reconnect';
    context.subscriptions.push(statusBarItem);
    renderStatusBar('disconnected');
    statusBarItem.show();

    context.subscriptions.push(
        vscode.commands.registerCommand('talos.runScript', () => void runScript()),
        vscode.commands.registerCommand('talos.stopScript', () => void stopScript()),
        vscode.commands.registerCommand('talos.reconnect', () => void reconnect()),
        vscode.commands.registerCommand('talos.toggleRunOnSave', () => toggleRunOnSave())
    );

    // Live reload: when run-on-save is on, saving a .py re-pushes + re-runs it in
    // the already-running game — no Minecraft restart, just a fresh script session.
    context.subscriptions.push(
        vscode.workspace.onDidSaveTextDocument((doc) => {
            if (!runOnSave) return;
            if (doc.languageId !== 'python' && !doc.fileName.endsWith('.py')) return;
            void runScript(doc);
        })
    );

    renderRunOnSaveStatus();

    context.subscriptions.push({
        dispose: () => {
            connection?.dispose();
            connection = undefined;
        },
    });
}

/** Whether saving a .py auto-runs it in-game. Mirrors the `talos.runOnSave` setting,
 *  seeded from config at activation and flipped by the toggle command. */
let runOnSave = vscode.workspace.getConfiguration('talos').get<boolean>('runOnSave', false);
let runOnSaveStatus: vscode.StatusBarItem | undefined;

function toggleRunOnSave(): void {
    runOnSave = !runOnSave;
    void vscode.workspace
        .getConfiguration('talos')
        .update('runOnSave', runOnSave, vscode.ConfigurationTarget.Global);
    renderRunOnSaveStatus();
    void vscode.window.setStatusBarMessage(
        `Talos live reload ${runOnSave ? 'ON — saving a .py runs it in Minecraft' : 'OFF'}`,
        4000
    );
}

function renderRunOnSaveStatus(): void {
    if (!runOnSaveStatus) {
        runOnSaveStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 99);
        runOnSaveStatus.command = 'talos.toggleRunOnSave';
    }
    runOnSaveStatus.text = runOnSave ? '$(sync) Talos live' : '$(sync-ignored) Talos live';
    runOnSaveStatus.tooltip = runOnSave
        ? 'Talos live reload is ON — saving a .py re-runs it in Minecraft. Click to turn off.'
        : 'Talos live reload is OFF. Click to turn on (save = run in-game).';
    runOnSaveStatus.show();
}

export function deactivate(): void {
    connection?.dispose();
    connection = undefined;
}

function config() {
    const cfg = vscode.workspace.getConfiguration('talos');
    return {
        host: cfg.get<string>('host', '127.0.0.1'),
        port: cfg.get<number>('port', 43077),
        tokenPath: cfg.get<string>('tokenPath', '~/.talos/token'),
    };
}

/** Lazily creates the connection object. Does not open a socket by itself. */
function getConnection(): TalosConnection {
    if (connection) {
        return connection;
    }
    const { host, port, tokenPath } = config();
    connection = new TalosConnection({
        host,
        port,
        tokenPath,
        onStateChange: (state) => renderStatusBar(state),
        onMessage: (msg) => handleMessage(msg),
        onDiagnostic: (text) => outputChannel.appendLine(`[talos] ${text}`),
    });
    return connection;
}

/** Connects and resolves once authenticated ('connected'), or rejects if the
 *  connection settles into 'disconnected' before that happens. */
function connectAndWaitReady(conn: TalosConnection, timeoutMs = 10_000): Promise<void> {
    if (conn.getState() === 'connected') {
        return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            cleanup();
            reject(new Error('Timed out waiting for Talos connection.'));
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
                reject(new Error('Talos connection failed. Check that Minecraft is running with Talos loaded.'));
            }
        }, 100);

        function cleanup() {
            clearTimeout(timer);
            clearInterval(interval);
        }

        conn.connect();
    });
}

async function runScript(target?: vscode.TextDocument): Promise<void> {
    const doc = target ?? vscode.window.activeTextEditor?.document;
    if (!doc) {
        void vscode.window.showErrorMessage('Talos: no active editor.');
        return;
    }
    if (doc.languageId !== 'python' && !doc.fileName.endsWith('.py')) {
        void vscode.window.showErrorMessage('Talos: active file is not a Python (.py) script.');
        return;
    }

    const conn = getConnection();
    outputChannel.show(true);

    try {
        if (conn.getState() !== 'connected') {
            outputChannel.appendLine('[talos] Connecting...');
            await connectAndWaitReady(conn);
        }
    } catch (err) {
        void vscode.window.showErrorMessage(`Talos: ${errMessage(err)}`);
        return;
    }

    const name = scriptNameFor(doc.uri);
    const source = doc.getText();

    try {
        activeScriptName = name;
        conn.send(makePushScript(name, source));
        conn.send(makeRun(name));
        outputChannel.appendLine(`[talos] Running "${name}"...`);
    } catch (err) {
        void vscode.window.showErrorMessage(`Talos: failed to send script: ${errMessage(err)}`);
    }
}

async function stopScript(): Promise<void> {
    const conn = getConnection();
    if (conn.getState() !== 'connected') {
        void vscode.window.showWarningMessage('Talos: not connected.');
        return;
    }
    try {
        conn.send(makeStop());
        outputChannel.appendLine(`[talos] Stop requested${activeScriptName ? ` for "${activeScriptName}"` : ''}.`);
    } catch (err) {
        void vscode.window.showErrorMessage(`Talos: failed to send stop: ${errMessage(err)}`);
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
    outputChannel.appendLine('[talos] Reconnecting...');
    try {
        await connectAndWaitReady(conn);
        outputChannel.appendLine('[talos] Connected.');
    } catch (err) {
        void vscode.window.showErrorMessage(`Talos: ${errMessage(err)}`);
    }
}

function handleMessage(msg: ServerToClientMessage): void {
    switch (msg.type) {
        case 'log':
            outputChannel.appendLine(formatLog(msg.level, msg.text));
            break;
        case 'status':
            outputChannel.appendLine(`[talos] status: ${msg.state}`);
            break;
        case 'script_done':
            outputChannel.appendLine(
                `[talos] script ${msg.success ? 'finished' : 'failed'}${msg.message ? `: ${msg.message}` : ''}`
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
            statusBarItem.text = '$(circle-filled) Talos';
            statusBarItem.tooltip = 'Talos: connected. Click to reconnect.';
            break;
        case 'connecting':
        case 'authenticating':
            statusBarItem.text = '$(sync~spin) Talos';
            statusBarItem.tooltip = 'Talos: connecting...';
            break;
        case 'reconnecting':
            statusBarItem.text = '$(circle-outline) Talos';
            statusBarItem.tooltip = 'Talos: reconnecting... Click to retry now.';
            break;
        case 'disconnected':
        default:
            statusBarItem.text = '$(circle-outline) Talos';
            statusBarItem.tooltip = 'Talos: disconnected. Click to connect.';
            break;
    }
}

function errMessage(err: unknown): string {
    if (err instanceof Error) return err.message;
    return String(err);
}
