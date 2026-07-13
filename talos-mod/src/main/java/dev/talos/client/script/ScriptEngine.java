package dev.talos.client.script;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Value;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;

/** Process scripting service. Each session owns one worker and one long-lived Python context. */
public final class ScriptEngine {
    @FunctionalInterface
    public interface LogSink {
        void log(String level, String text);
    }

    public enum State { RUNNING, STOPPING, STOPPED }
    private static final ScriptEngine INSTANCE = new ScriptEngine();
    private static final CompletableFuture<Engine> SHARED_ENGINE = new CompletableFuture<>();
    private static final List<String> API_FILES = List.of(
            "errors.py", "actions.py", "engine.py", "events.py", "humanize.py", "__init__.py");
    private static final int MAX_SESSIONS = 8;

    static {
        Thread thread = new Thread(() -> {
            try { SHARED_ENGINE.complete(Engine.newBuilder().build()); }
            catch (Throwable error) { SHARED_ENGINE.completeExceptionally(error); }
        }, "Talos GraalPy Prewarm");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Default sink used whenever a caller doesn't supply its own: forwards every
     * stdout/stderr line (and, transitively, {@code talos.log(...)}, which the Python
     * API prints to stdout) to the in-game chat, prefixed {@code [Talos]}. This is what
     * makes {@code /talos script run <name>} and the in-game editor's Run button show
     * live output instead of silently vanishing into the server log.
     */
    public static final LogSink CHAT = ScriptEngine::sendToChat;

    private final Object lock = new Object();
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextSessionId = new AtomicInteger(1);
    private volatile Session session;

    private ScriptEngine() {}
    public static ScriptEngine instance() { return INSTANCE; }

    public CompletableFuture<Void> run(String scriptName) {
        return run(scriptName, CHAT);
    }

    public CompletableFuture<Void> run(String scriptName, LogSink logSink) {
        Objects.requireNonNull(scriptName, "scriptName");
        if (!scriptName.matches("[A-Za-z0-9_.-]+") || scriptName.contains(".."))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid script name"));
        synchronized (lock) {
            if (session == null || session.state.get() == State.STOPPED) {
                session = createSession(logSink, true);
                if (session == null) return tooManySessions(logSink);
            }
            Session current = session;
            if (current.state.get() != State.RUNNING)
                return CompletableFuture.failedFuture(new IllegalStateException("Script engine is stopping"));
            Path scripts = FabricLoader.getInstance().getGameDir().resolve("talos").resolve("scripts");
            String filename = scriptName.endsWith(".py") ? scriptName : scriptName + ".py";
            Path file = scripts.resolve(filename).normalize();
            if (!file.getParent().equals(scripts))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Script must be directly in talos/scripts"));
            return current.submit(() -> { current.evaluate(file, logSink); return null; });
        }
    }

    public void tick() {
        for (Session current : List.copyOf(sessions.values()))
            if (current.state.get() == State.RUNNING) current.events.post("tick");
    }

    public void stop() {
        for (Session current : List.copyOf(sessions.values())) current.stop();
    }

    public void onDisconnect() {
        for (Session current : List.copyOf(sessions.values()))
            if (current.state.get() == State.RUNNING) current.events.post("disconnect");
        stop();
    }

    /** Entry point for client-side hooks; Python dispatch still occurs only on the worker. */
    public void postEvent(String event, Object... snapshots) {
        for (Session current : List.copyOf(sessions.values()))
            if (current.state.get() == State.RUNNING) current.events.post(event, snapshots);
    }

    public State state() { Session current = session; return current == null ? State.STOPPED : current.state.get(); }

    private Session createSession(LogSink sink, boolean primary) {
        synchronized (lock) {
            sessions.values().removeIf(current -> current.state.get() == State.STOPPED);
            if (sessions.size() >= MAX_SESSIONS) return null;
            int id = nextSessionId.getAndIncrement();
            Session created = new Session(id, sink, primary);
            sessions.put(id, created);
            return created;
        }
    }

    private static <T> CompletableFuture<T> tooManySessions(LogSink sink) {
        String message = "Talos supports at most " + MAX_SESSIONS + " concurrent script sessions";
        if (sink != null) sink.log("error", message);
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    private ScriptHandle spawn(Session parent, String payload) {
        Objects.requireNonNull(payload, "payload");
        LogSink childSink = parent.baseSink;
        Session child = createSession(childSink, false);
        if (child == null) {
            tooManySessions(childSink);
            throw new IllegalStateException("Talos supports at most " + MAX_SESSIONS + " concurrent script sessions");
        }
        CompletableFuture<String> result = child.submit(() -> child.evaluateCallable(payload));
        ScriptHandle handle = new ScriptHandle(child, result);
        result.whenComplete((ignored, error) -> child.stop());
        return handle;
    }

    /** Host-visible handle for one independently cancellable child script session. */
    public final class ScriptHandle {
        private final Session child;
        private final CompletableFuture<String> result;

        private ScriptHandle(Session child, CompletableFuture<String> result) {
            this.child = child;
            this.result = result;
        }

        @HostAccess.Export public void stop() { child.stop(); }
        @HostAccess.Export public String join() { return result.join(); }
        @HostAccess.Export public boolean isRunning() { return child.state.get() == State.RUNNING && !result.isDone(); }
    }

    /** Separate from TalosNativeBridge so concurrency never broadens the game bridge. */
    public final class ConcurrencyHost {
        private final Session owner;
        private ConcurrencyHost(Session owner) { this.owner = owner; }

        @HostAccess.Export public ScriptHandle spawn(String callablePayload) {
            return ScriptEngine.this.spawn(owner, callablePayload);
        }
    }

    /**
     * {@link LogSink#log} fires on the GraalPy worker thread (from {@code LineOutput}
     * writes or {@link #reportError}); the client tick thread must never be touched from
     * there directly, so this hops onto it via {@link GameThreadExecutor} before it goes
     * anywhere near {@code ChatHud}.
     */
    private static void sendToChat(String level, String text) {
        GameThreadExecutor.instance().submit(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.inGameHud == null) return null;
            Formatting color = "error".equals(level) ? Formatting.RED : Formatting.GRAY;
            String prefix = "[Talos] ";
            String body = text;
            if (text.startsWith("[Talos:") && text.contains("] ")) {
                int end = text.indexOf("] ") + 2;
                prefix = text.substring(0, end);
                body = text.substring(end);
            }
            MutableText message = Text.literal(prefix).formatted(Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal(body).formatted(color));
            client.inGameHud.getChatHud().addMessage(message);
            return null;
        });
    }

    private final class Session {
        private final int id;
        private final LogSink outputSink;
        private volatile LogSink baseSink;
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private final ThreadPoolExecutor worker;
        private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
        private final EventDispatcher events = new EventDispatcher(this::enqueueEvent);
        private volatile Context context;
        private volatile TalosNativeBridge bridge;
        private final LineOutput stdout = new LineOutput("info");
        private final LineOutput stderr = new LineOutput("error");

        private Session(int id, LogSink sink, boolean primary) {
            this.id = id;
            this.baseSink = sink == null ? CHAT : sink;
            this.outputSink = primary ? this.baseSink
                    : (level, text) -> this.baseSink.log(level, "[Talos:" + id + "] " + text);
            this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(256), runnable -> {
                        Thread thread = new Thread(runnable, "Talos Script Worker " + id);
                        thread.setDaemon(true);
                        return thread;
                    });
            stdout.sink = this.outputSink;
            stderr.sink = this.outputSink;
        }

        private <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> task) {
            CompletableFuture<T> result = new CompletableFuture<>();
            pending.add(result);
            result.whenComplete((ignored, error) -> pending.remove(result));
            try {
                worker.execute(() -> {
                    if (state.get() != State.RUNNING) {
                        result.completeExceptionally(new IllegalStateException("Script engine is stopping"));
                        return;
                    }
                    try { result.complete(task.call()); }
                    catch (Throwable error) { result.completeExceptionally(error); }
                });
            } catch (RejectedExecutionException error) {
                result.completeExceptionally(new IllegalStateException("Talos script-worker queue is full", error));
            }
            return result;
        }

        private void enqueueEvent(Runnable event) {
            submit(() -> {
                ensureContext();
                try {
                    event.run();
                    stdout.flushLine();
                    stderr.flushLine();
                } catch (RuntimeException error) {
                    stdout.flushLine();
                    stderr.flushLine();
                    // event handlers (talos.on("tick", ...) etc.) run detached from any
                    // caller's future, so without this an exception in one would vanish
                    // silently instead of reaching the console/chat.
                    reportError(error, stdout.sink);
                    throw error;
                }
                return null;
            });
        }

        private void evaluate(Path file, LogSink logSink) throws IOException {
            if (!Files.isRegularFile(file)) throw new IOException("Script not found: " + file);
            // Left wired after this call returns (not reset to null) so output/errors from
            // event handlers the script registers via talos.on(...) keep streaming to the
            // same sink for the rest of the session.
            LogSink effectiveSink = logSink == null ? outputSink : logSink;
            baseSink = effectiveSink;
            stdout.sink = effectiveSink;
            stderr.sink = effectiveSink;
            try {
                ensureContext();
                context.eval("python", "\nimport sys\nfor _name in list(sys.modules):\n"
                        + "    if _name == 'talos' or _name.startswith('talos.') or (_name not in _talos_baseline_modules and not _name.startswith('_')):\n"
                        + "        sys.modules.pop(_name, None)\n"
                        + "for _key in list(globals()):\n"
                        + "    if _key not in {'_talos_host', '_talos_concurrency', '_talos_baseline_modules', '__builtins__'} and not _key.startswith('__'):\n"
                        + "        globals().pop(_key, None)\n");
                events.clear();
                installApi(context);
                context.eval(Source.newBuilder("python", file.toFile()).build());
                stdout.flushLine();
                stderr.flushLine();
            } catch (RuntimeException error) {
                stdout.flushLine();
                stderr.flushLine();
                reportError(error, effectiveSink);
                throw error;
            }
        }

        private String evaluateCallable(String payload) {
            try {
                ensureContext();
                String code = "import base64, importlib, marshal, pickle, types, talos\n"
                        + "_d = pickle.loads(base64.b64decode(" + py(payload) + "))\n"
                        + "_g = {'__builtins__': __builtins__, 'talos': talos}\n"
                        + "_g.update(_d['globals'])\n"
                        + "_g.update({name: importlib.import_module(module) for name, module in _d['modules'].items()})\n"
                        + "def _cell(value):\n    return (lambda: value).__closure__[0]\n"
                        + "_closure = tuple(_cell(v) for v in _d['closure']) or None\n"
                        + "_fn = types.FunctionType(marshal.loads(_d['code']), _g, _d['name'], _d['defaults'], _closure)\n"
                        + "_fn.__kwdefaults__ = _d['kwdefaults']\n"
                        + "base64.b64encode(pickle.dumps(_fn())).decode('ascii')";
                Value value = context.eval("python", code);
                stdout.flushLine();
                stderr.flushLine();
                return value.asString();
            } catch (RuntimeException error) {
                stdout.flushLine();
                stderr.flushLine();
                reportError(error, outputSink);
                throw error;
            }
        }

        /**
         * Uncaught Python exceptions surface as a {@link PolyglotException} from
         * {@code context.eval(...)} and never touch {@link LineOutput} (there's no Python
         * REPL printing a traceback here), so without this they'd only ever reach the
         * command's own {@code whenComplete} handler — invisible for event-handler errors
         * and easy to miss even for direct runs. Push the guest traceback through the same
         * sink used for stdout/stderr so every failure mode ends up in one place (chat, by
         * default).
         */
        private static void reportError(Throwable error, LogSink logSink) {
            if (logSink == null) return;
            String message = error instanceof PolyglotException poly && poly.getMessage() != null
                    ? poly.getMessage()
                    : String.valueOf(error.getMessage() != null ? error.getMessage() : error);
            for (String line : message.split("\n")) logSink.log("error", line);
        }

        private void ensureContext() {
            if (context != null) return;
            Thread.currentThread().setContextClassLoader(ScriptEngine.class.getClassLoader());
            Context created = Context.newBuilder("python")
                    .engine(SHARED_ENGINE.join())
                    .allowHostAccess(HostAccess.EXPLICIT)
                    .allowHostClassLookup(className -> false)
                    .allowIO(IOAccess.NONE)
                    .allowNativeAccess(false)
                    .allowCreateProcess(false)
                    .allowCreateThread(false)
                    .allowEnvironmentAccess(EnvironmentAccess.NONE)
                    .allowPolyglotAccess(PolyglotAccess.NONE)
                    .out(stdout)
                    .err(stderr)
                    .build();
            if (state.get() != State.RUNNING) {
                created.close(true);
                throw new IllegalStateException("Script engine is stopping");
            }
            bridge = new TalosNativeBridge(GameThreadExecutor.instance(), events);
            created.getBindings("python").putMember("_talos_host", bridge);
            created.getBindings("python").putMember("_talos_concurrency", new ConcurrencyHost(this));
            context = created;
            installApi(created);
            created.eval("python", "_talos_baseline_modules = frozenset(__import__('sys').modules)");
        }

        private static void installApi(Context context) {
            StringBuilder code = new StringBuilder("import sys, types\n");
            for (String file : API_FILES) {
                String module = file.equals("__init__.py") ? "talos" : "talos." + file.substring(0, file.length() - 3);
                String source = readResource("/talos_pyapi/talos/" + file);
                code.append("_m=types.ModuleType(").append(py(module)).append(")\n")
                        .append("_m.__package__=").append(py(file.equals("__init__.py") ? "talos" : "talos")).append("\n")
                        .append("_m.__file__=").append(py("embedded:/talos/" + file)).append("\n")
                        .append(file.equals("__init__.py") ? "_m.__path__=[]\n" : "")
                        .append("_m.__dict__['_talos_host']=_talos_host\n")
                        .append("_m.__dict__['_talos_concurrency']=_talos_concurrency\n")
                        .append("sys.modules[").append(py(module)).append("]=_m\n")
                        .append("exec(compile(").append(py(source)).append(", _m.__file__, 'exec'), _m.__dict__)\n");
            }
            context.eval("python", code.toString());
        }

        private static String readResource(String name) {
            try (InputStream stream = ScriptEngine.class.getResourceAsStream(name)) {
                if (stream == null) throw new IllegalStateException("Missing Python API resource " + name);
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException error) { throw new IllegalStateException("Cannot read " + name, error); }
        }
        private static String py(String value) {
            return "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\r", "\\r").replace("\n", "\\n") + "'";
        }

        private void stop() {
            if (!state.compareAndSet(State.RUNNING, State.STOPPING)) return;
            TalosNativeBridge currentBridge = bridge;
            if (currentBridge != null) currentBridge.invalidate();
            Context currentContext = context;
            if (currentContext != null) {
                try { currentContext.close(true); } catch (RuntimeException ignored) { }
            }
            worker.shutdownNow();
            for (CompletableFuture<?> future : List.copyOf(pending))
                future.completeExceptionally(new CancellationException("Talos script session stopped"));
            events.clear();
            state.set(State.STOPPED);
            sessions.remove(id, this);
        }

        private static final class LineOutput extends OutputStream {
            private final String level;
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();
            private volatile LogSink sink;

            private LineOutput(String level) { this.level = level; }

            @Override
            public synchronized void write(int value) {
                if (value == '\n') flushLine();
                else if (value != '\r') line.write(value);
            }

            private synchronized void flushLine() {
                if (line.size() == 0) return;
                LogSink current = sink;
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (current != null) current.log(level, text);
            }
        }
    }
}
