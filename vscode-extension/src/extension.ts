import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { TalosConnection, ConnectionState } from './connection';
import { makePushScript, makeRun, makeStop, ServerToClientMessage } from './protocol';

let connection: TalosConnection | undefined;
let statusBarItem: vscode.StatusBarItem;
let outputChannel: vscode.OutputChannel;
/** Resolves the in-flight run's progress bar when the game acknowledges "running". */
let pendingRunAck: (() => void) | undefined;

/** Name of the script currently pushed/running, so stop/status messages read naturally. */
let activeScriptName: string | undefined;
/** Local file path of the pushed script, so tracebacks can link back to the editor. */
let activeScriptPath: string | undefined;

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
        vscode.commands.registerCommand('talos.toggleRunOnSave', () => toggleRunOnSave()),
        vscode.commands.registerCommand('talos.installCli', () => void installCli(context, false))
    );

    // Keep the bundled terminal CLI installed/updated at ~/.talos/bin/talos.
    void installCli(context, true);

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
    void ensureStubPath(context);

    context.subscriptions.push({
        dispose: () => {
            connection?.dispose();
            connection = undefined;
        },
    });
}

/**
 * Points Pylance at the bundled `talos.pyi` stubs so `import talos` resolves with full
 * autocomplete instead of a missing-import squiggle. Only an empty or stale (previous
 * extension version) stubPath is touched — a user's own stubPath is never overwritten.
 */
async function ensureStubPath(context: vscode.ExtensionContext): Promise<void> {
    const stubsDir = vscode.Uri.joinPath(context.extensionUri, 'stubs').fsPath;
    const analysis = vscode.workspace.getConfiguration('python.analysis');
    const current = analysis.get<string>('stubPath', '');
    if (current === stubsDir) return;
    if (current && !/[\\/]talos[^\\/]*[\\/]stubs$/.test(current)) return; // user-managed
    const target = vscode.workspace.workspaceFolders?.length
        ? vscode.ConfigurationTarget.Workspace
        : vscode.ConfigurationTarget.Global;
    try {
        await analysis.update('stubPath', stubsDir, target);
        outputChannel.appendLine(`[talos] Pylance stubPath set to ${stubsDir}`);
    } catch {
        // No settings write access (e.g. restricted workspace) — autocomplete still works
        // wherever a talos.pyi sits next to the script.
    }
}

/**
 * Installs the bundled terminal CLI to ~/.talos/bin/talos so `talos <script.py>`,
 * `talos py -c '...'` etc. work from any shell against the running game. Runs
 * silently on every activation (keeps the copy current with the extension) and
 * loudly via the "Talos: Install Terminal CLI" command. Never throws.
 */
async function installCli(context: vscode.ExtensionContext, silent: boolean): Promise<void> {
    try {
        const source = vscode.Uri.joinPath(context.extensionUri, 'cli', 'talos').fsPath;
        const binDir = path.join(os.homedir(), '.talos', 'bin');
        fs.mkdirSync(binDir, { recursive: true });
        const target = path.join(binDir, 'talos');
        fs.copyFileSync(source, target);
        if (process.platform === 'win32') {
            // Windows shells don't honor shebangs — ship a .cmd shim alongside.
            fs.writeFileSync(path.join(binDir, 'talos.cmd'), '@python "%USERPROFILE%\\.talos\\bin\\talos" %*\r\n');
        } else {
            fs.chmodSync(target, 0o755);
        }
        const onPath = (process.env.PATH ?? '').split(path.delimiter).includes(binDir);
        const pathHint = process.platform === 'win32'
            ? `add ${binDir} to your PATH`
            : `add it to your PATH: export PATH="$HOME/.talos/bin:$PATH"`;
        outputChannel.appendLine(`[talos] terminal CLI installed at ${target}${onPath ? '' : ` — ${pathHint}`}`);
        if (!silent) {
            void vscode.window.showInformationMessage(
                `Talos terminal CLI installed at ${target}.${onPath ? '' : ` To use it anywhere, ${pathHint}`}`);
        } else if (!onPath && !context.globalState.get('talos.cliPathHintShown')) {
            void context.globalState.update('talos.cliPathHintShown', true);
            void vscode.window.showInformationMessage(
                `Talos terminal CLI installed to ~/.talos/bin — ${pathHint} to run scripts with \`talos\` from any terminal.`);
        }
    } catch (error) {
        outputChannel.appendLine(`[talos] terminal CLI install failed: ${String(error)}`);
    }
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
    const name = scriptNameFor(doc.uri);
    const source = doc.getText();

    // 0-100% loading bar: connect (40) -> push (35) -> game acknowledges "running" (25).
    await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: `Talos: loading "${name}"`,
        },
        async (progress) => {
            let percent = 0;
            const report = (to: number, message: string) => {
                progress.report({ increment: to - percent, message: `${message} — ${to}%` });
                percent = to;
            };
            report(1, 'connecting');
            try {
                if (conn.getState() !== 'connected') {
                    outputChannel.appendLine('[talos] Connecting...');
                    await connectAndWaitReady(conn);
                }
            } catch (err) {
                void vscode.window.showErrorMessage(`Talos: ${errMessage(err)}`);
                return;
            }
            report(40, 'connected');

            try {
                activeScriptName = name;
                activeScriptPath = doc.uri.scheme === 'file' ? doc.uri.fsPath : undefined;
                conn.send(makePushScript(name, source));
                report(75, 'script pushed');
                const ack = new Promise<void>((resolve) => {
                    pendingRunAck = resolve;
                    setTimeout(resolve, 10_000); // never wedge the bar on a lost ack
                });
                conn.send(makeRun(name));
                outputChannel.appendLine(`[talos] Running "${name}"...`);
                await ack;
                report(100, 'running');
            } catch (err) {
                void vscode.window.showErrorMessage(
                    `Talos: failed to send script: ${errMessage(err)}`
                );
            } finally {
                pendingRunAck = undefined;
            }
        }
    );
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
            outputChannel.appendLine(formatLog(msg.level, linkifyTraceback(msg.text)));
            break;
        case 'status':
            outputChannel.appendLine(`[talos] status: ${msg.state}`);
            if (msg.state === 'running' && pendingRunAck) {
                pendingRunAck();
                pendingRunAck = undefined;
            }
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

/**
 * Rewrites Python traceback frames that reference the pushed script (or a sibling .py it
 * required) to the absolute local `path:line` form, which the output channel renders as a
 * clickable jump-to-source link. Frames pointing at files we cannot locate (embedded
 * talos modules, engine internals) pass through untouched.
 */
function linkifyTraceback(text: string): string {
    if (!activeScriptPath || !text.includes('File "')) return text;
    const scriptPath = activeScriptPath;
    return text.replace(/File "([^"]+)", line (\d+)/g, (match, file: string, line: string) => {
        const base = file.split(/[\\/]/).pop();
        if (!base || !base.endsWith('.py')) return match;
        let resolved: string | undefined;
        if (base === path.basename(scriptPath)) {
            resolved = scriptPath;
        } else {
            // talos.require("lib") frames reference talos/scripts/lib.py — when the user
            // edits scripts in one folder, the sibling file is the local source.
            const sibling = path.join(path.dirname(scriptPath), base);
            if (fs.existsSync(sibling)) resolved = sibling;
        }
        return resolved ? `File "${base}", line ${line} → ${resolved}:${line}` : match;
    });
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
