"""Cooperative execution engine: on_start / on_tick hooks and concurrent async tasks.

The engine is driven by the game tick. Everything runs on the single script
worker thread; `await` points are where other tasks (and on_tick hooks) get to
run, so tasks never race each other over shared state.

    import talos

    @talos.on_start          # runs once, when the script starts
    def setup():
        talos.log("ready")

    @talos.on_tick           # runs every game tick (plain def, keep it fast)
    def hud():
        ...

    @talos.task              # long-running; many can run at the same time
    async def miner():
        while True:
            ore = talos.find_block("minecraft:diamond_ore", 64)
            if ore:
                await talos.aio.goto(ore)       # yields while walking
                await talos.aio.mine(ore)       # yields while mining
            await talos.sleep(1.0)

    @talos.task
    async def guard():
        while True:
            if talos.find_entity("minecraft:zombie", 6):
                await talos.aio.kill_nearest(6)
            await talos.next_tick()

Inside `async def`, use the awaitable actions on `talos.aio` — they let the
other tasks keep running. The plain sync actions (talos.goto etc.) still work
anywhere but pause every task until they finish. Tasks can also be started
dynamically with talos.start(coro).
"""

import sys as _sys
import types as _types
import inspect as _inspect
import json as _json
import traceback as _traceback

_errors = _sys.modules["talos.errors"]
_actions = _sys.modules["talos.actions"]

_started = False
_launched = False
_tick_no = 0
_start_hooks = []
_tick_hooks = []
_declared_tasks = []
_tasks = []


# --- awaitables -------------------------------------------------------------

class _TickWaiter:
    __slots__ = ("until",)
    def __init__(self, until):
        self.until = until
    def ready(self):
        return _tick_no >= self.until
    def result(self):
        return None
    def cancel(self):
        pass


class _FutureWaiter:
    __slots__ = ("handle",)
    def __init__(self, handle):
        self.handle = handle
    def ready(self):
        return self.handle.done()
    def result(self):
        return _errors.call(self.handle.result)
    def cancel(self):
        try:
            self.handle.cancel()
        except BaseException:
            pass


@_types.coroutine
def _wait(waiter):
    while not waiter.ready():
        yield waiter
    return waiter.result()


def sleep(seconds):
    """Awaitable pause in seconds; other tasks and on_tick hooks keep running."""
    return ticks(max(1, int(round(float(seconds) * 20))))

def ticks(n):
    """Awaitable pause for n game ticks (20 ticks = 1 second)."""
    return _wait(_TickWaiter(_tick_no + max(1, int(n))))

def next_tick():
    """Awaitable pause until the next game tick."""
    return _wait(_TickWaiter(_tick_no + 1))

def tick_count():
    """Number of engine ticks since the script started."""
    return _tick_no


# --- tasks ------------------------------------------------------------------

class TaskHandle:
    """A running async task. Check .done/.error/.result, or .cancel() it."""

    def __init__(self, coro, name):
        if not _inspect.iscoroutine(coro):
            raise TypeError("talos.start() needs a coroutine - call an 'async def' function")
        self.name = name or getattr(coro, "__name__", "task")
        self._coro = coro
        self._waiter = None
        self._cancelled = False
        self.done = False
        self.error = None
        self.result = None

    def cancel(self):
        """Cancel the task: ActionCancelledError is raised at its current await point."""
        if not self.done:
            self._cancelled = True
            if self._waiter is not None:
                self._waiter.cancel()

    def _ready(self):
        return self._cancelled or self._waiter is None or self._waiter.ready()

    def _step(self):
        try:
            if self._cancelled:
                # One-shot: a task may catch the cancellation to clean up and keep going.
                self._cancelled = False
                self._waiter = self._coro.throw(
                    _errors.ActionCancelledError(f"task {self.name!r} cancelled"))
            else:
                self._waiter = self._coro.send(None)
            if not hasattr(self._waiter, "ready"):
                raise TypeError(
                    f"task {self.name!r} awaited a non-talos awaitable; "
                    "use talos.aio actions, talos.sleep(), or talos.next_tick()")
        except StopIteration as stop:
            self.done = True
            self.result = stop.value
        except _errors.ActionCancelledError as error:
            self.done = True
            self.error = error
        except (KeyboardInterrupt, SystemExit):
            raise
        except BaseException as error:
            self.done = True
            self.error = error
            _report(f"task {self.name!r}", error)


def start(coro, name=None):
    """Start an async task right now and return its TaskHandle."""
    handle = TaskHandle(coro, name)
    _tasks.append(handle)
    _ensure_running()
    return handle


def cancel_all():
    """Cancel every running task."""
    for handle in list(_tasks):
        handle.cancel()


# --- decorators ---------------------------------------------------------------

def on_start(fn):
    """Run once when the engine starts (first tick after the script loads).

    Plain `def` runs to completion before anything else; `async def` becomes a task.
    """
    _start_hooks.append(fn)
    _ensure_running()
    return fn


def on_tick(fn):
    """Run every game tick. Must be a plain `def` - use @talos.task for async work."""
    if _inspect.iscoroutinefunction(fn):
        raise TypeError("@talos.on_tick handlers must be plain 'def'; use @talos.task for async")
    _tick_hooks.append(fn)
    _ensure_running()
    return fn


def task(fn):
    """Declare a long-running `async def` task, started when the engine starts."""
    if not _inspect.iscoroutinefunction(fn):
        raise TypeError("@talos.task needs an 'async def' function")
    _declared_tasks.append(fn)
    _ensure_running()
    return fn


def every(seconds=None, minutes=None, ticks=None):
    """Run the decorated function repeatedly at a fixed interval.

    Give exactly one interval (or combine seconds+minutes): @talos.every(seconds=5),
    @talos.every(minutes=1), @talos.every(ticks=10). Works on a plain `def` (must
    return quickly, like on_tick) or an `async def` (awaited to completion before
    the next interval starts, so runs never overlap). The first run happens one
    interval after the script starts.

        @talos.every(seconds=30)
        def checkpoint():
            talos.state["pos"] = list(talos.player_feet())
    """
    total = 0.0
    if seconds is not None:
        total += float(seconds)
    if minutes is not None:
        total += float(minutes) * 60.0
    if ticks is not None:
        if seconds is not None or minutes is not None:
            raise ValueError("@talos.every takes ticks OR seconds/minutes, not both")
        interval = int(ticks)
    elif seconds is None and minutes is None:
        raise ValueError("@talos.every needs seconds=, minutes=, or ticks=")
    else:
        interval = int(round(total * 20))
    if interval < 1:
        raise ValueError("@talos.every interval must be at least one tick")

    def decorate(fn):
        is_async = _inspect.iscoroutinefunction(fn)

        async def _every_loop():
            while True:
                await _wait(_TickWaiter(_tick_no + interval))
                try:
                    if is_async:
                        await fn()
                    else:
                        fn()
                except (KeyboardInterrupt, SystemExit):
                    raise
                except _errors.ActionCancelledError:
                    raise  # cancel() must still stop the loop
                except BaseException as error:
                    # One failed run gets the usual one-line report; the schedule survives.
                    _report(f"every {getattr(fn, '__name__', '?')!r}", error)

        _every_loop.__name__ = f"every {getattr(fn, '__name__', '?')}"
        if _launched:
            start(_every_loop())
        else:
            _declared_tasks.append(_every_loop)
        _ensure_running()
        return fn
    return decorate


def run():
    """Start the engine explicitly. Optional - the decorators start it automatically."""
    _ensure_running()


# --- script commands (/talos <name> ...) ---------------------------------------

# This module is re-executed on every script run, so handlers registered by the
# PREVIOUS run are gone; drop their Java-side claims too or /talos <name> would
# queue invocations nothing ever drains.
_errors.call(_talos_host.clearCommands)

_commands = {}
_commands_pumping = False


def command(name, suggest=None):
    """Register a handler for `/talos <name> ...` while this script session runs.

    If `name` matches a built-in subcommand (goto, mine, place, kill), the chat
    command is forwarded here INSTEAD of the built-in - and the built-in stays
    reachable as talos.goto(...) etc., so an override can wrap the original.
    Any other name is invoked via `/talos <name>`-style `/talos cmd <name> [args]`.

    The handler receives the argument text split on whitespace, as a list of
    strings. It always runs on the script worker (never the client thread). A
    plain `def` should return quickly; returning a coroutine (or using an
    `async def` handler) starts it as a task so it can await talos.aio actions.

        @talos.command("pygoto")
        async def pygoto(args):
            x, y, z = (int(a) for a in args)
            await talos.aio.goto(x, y, z)

    Tab-completion for the arguments comes from `suggest`: a list of strings
    suggests those tokens for the FIRST argument; a list of lists suggests
    per-position (outer index = argument position). Tokens must not contain
    whitespace. Suggestions are static (captured at registration) and are served
    host-side — the game never calls into Python to compute them.

        @talos.command("farm", suggest=[["wheat", "carrot", "potato"], ["16", "64"]])
        def farm(args):
            crop, count = args[0], int(args[1])
    """
    def decorate(handler):
        if not callable(handler):
            raise TypeError("@talos.command needs a callable handler")
        _commands[str(name)] = handler
        if suggest is None:
            _errors.call(_talos_host.registerCommand, str(name))
        else:
            positions = suggest
            if positions and not isinstance(positions[0], (list, tuple)):
                positions = [positions]
            spec_rows = []
            for options in positions:
                tokens = [str(option) for option in options]
                for token in tokens:
                    if any(ch.isspace() for ch in token):
                        raise ValueError(f"suggestion tokens must not contain whitespace: {token!r}")
                spec_rows.append("\t".join(tokens))
            _errors.call(_talos_host.registerCommand, str(name), "\n".join(spec_rows))
        _ensure_command_pump()
        return handler
    return decorate


def _ensure_command_pump():
    global _commands_pumping
    if _commands_pumping:
        return
    _commands_pumping = True
    # Invocations are queued host-side by the client tick thread; this pump
    # drains them HERE, on the single script worker, through the same tick-event
    # path everything else uses - Python is never entered from the client thread.
    _talos_host.on("tick", _pump_commands)


def _pump_commands(*_ignored):
    while True:
        invocation = _talos_host.pollCommand()
        if invocation is None:
            return
        name = str(invocation.name())
        handler = _commands.get(name)
        if handler is None:
            continue
        args = str(invocation.args()).split()
        try:
            result = handler(args)
        except (KeyboardInterrupt, SystemExit):
            raise
        except BaseException as error:
            # Same one-clean-line contract as on_tick/task failures.
            _report(f"command {name!r}", error)
            continue
        if _inspect.iscoroutine(result):
            start(result, name=f"command {name}")


# --- scheduler ---------------------------------------------------------------

def _ensure_running():
    global _started
    if _started:
        return
    _started = True
    _talos_host.on("tick", _pump)


def _script_frames(error):
    """Traceback frames from the user's script only - engine internals are noise."""
    return [frame for frame in _traceback.extract_tb(error.__traceback__)
            if not frame.filename.startswith("embedded:") and not frame.filename.startswith("<")]


def _report(where, error):
    frames = _script_frames(error)
    at = ""
    if frames:
        last = frames[-1]
        at = f" ({last.filename.rsplit('/', 1)[-1]}, line {last.lineno})"
    # Expected in-game failures (path blocked, target gone, ...) get ONE readable line;
    # only genuine script bugs earn a (script-frames-only) traceback.
    print(f"{where} failed: {type(error).__name__}: {error}{at}", file=_sys.stderr)
    if not isinstance(error, _errors.TalosError):
        for frame in frames:
            print(f"  {frame.filename.rsplit('/', 1)[-1]}, line {frame.lineno}, in {frame.name}",
                  file=_sys.stderr)
            if frame.line:
                print(f"    {frame.line}", file=_sys.stderr)


def _launch():
    for fn in _start_hooks:
        if _inspect.iscoroutinefunction(fn):
            start(fn(), name=getattr(fn, "__name__", "on_start"))
        else:
            try:
                fn()
            except (KeyboardInterrupt, SystemExit):
                raise
            except BaseException as error:
                _report(f"on_start {getattr(fn, '__name__', '?')!r}", error)
    for fn in _declared_tasks:
        start(fn(), name=getattr(fn, "__name__", "task"))


def _pump(*_ignored):
    global _tick_no, _launched
    _tick_no += 1
    if not _launched:
        _launched = True
        _launch()
    for hook in list(_tick_hooks):
        try:
            hook()
        except (KeyboardInterrupt, SystemExit):
            raise
        except BaseException as error:
            _report(f"on_tick {getattr(hook, '__name__', '?')!r}", error)
    for handle in list(_tasks):
        if not handle.done and handle._ready():
            handle._step()
        if handle.done:
            try:
                _tasks.remove(handle)
            except ValueError:
                pass


# --- awaitable actions --------------------------------------------------------

async def _submit(submit, *args, wrap=None):
    raw = await _wait(_FutureWaiter(_errors.call(submit, *args)))
    return wrap(raw) if wrap else raw


class _Aio:
    """Awaitable versions of the long-running actions, for use inside async tasks.

    Each call starts the action and yields to the other tasks until it finishes;
    the same typed talos errors are raised on failure.
    """

    @staticmethod
    def goto(x, y=None, z=None):
        """Awaitable talos.goto."""
        x, y, z = _actions._coords(x, y, z)
        return _submit(_talos_host.submitGoto, int(x), int(y), int(z))

    @staticmethod
    def goto_near(x, y, z, range):
        """Awaitable talos.goto_near."""
        return _submit(_talos_host.submitGotoNear, int(x), int(y), int(z), int(range))

    @staticmethod
    def goto_xz(x, z):
        """Awaitable talos.goto_xz."""
        return _submit(_talos_host.submitGotoXZ, int(x), int(z))

    @staticmethod
    def goto_block(block_id, radius=64):
        """Awaitable talos.goto_block (nearest matching block, retry on unreachable)."""
        return _submit(_talos_host.submitGotoBlockType, str(block_id), int(radius))

    @staticmethod
    def follow(target, distance=3.0):
        """Awaitable talos.follow: resolves when following ENDS (see talos.follow)."""
        return _submit(_talos_host.submitFollow, str(target), float(distance))

    @staticmethod
    def find_block(name, radius=64):
        """Awaitable talos.find_block; returns a Pos or None."""
        return _submit(_talos_host.submitFindBlock, str(name), int(radius),
                       wrap=_actions._wrap_pos)

    @staticmethod
    def place_block(x, y=None, z=None, block_id=None):
        """Awaitable talos.place_block (coordinates required)."""
        if y is None and z is None:
            x, y, z = _actions._coords(x)
        if block_id is None:
            return _submit(_talos_host.submitPlaceBlock, int(x), int(y), int(z))
        return _submit(_talos_host.submitPlaceBlockAs, int(x), int(y), int(z), str(block_id))

    @staticmethod
    def break_block(x, y=None, z=None):
        """Awaitable talos.break_block."""
        x, y, z = _actions._coords(x, y, z)
        return _submit(_talos_host.submitBreakBlock, int(x), int(y), int(z))

    @staticmethod
    def mine(x, y=None, z=None):
        """Awaitable talos.mine (alias of break_block)."""
        return _Aio.break_block(x, y, z)

    @staticmethod
    def mine_looking_at():
        """Awaitable talos.mine_looking_at."""
        return _submit(_talos_host.submitMineLookingAt)

    @staticmethod
    def kill_nearest(radius=6.0):
        """Awaitable talos.kill_nearest."""
        return _submit(_talos_host.submitKillNearest, float(radius))

    @staticmethod
    def wait(a, b=None):
        """Awaitable talos.wait: pause for `a` seconds (or a humanized random
        duration in [a, b] when `b` is given); other tasks keep running."""
        if b is None:
            return sleep(float(a))
        return sleep(_errors.call(_talos_host.randomBetween, float(a), float(b)))

    @staticmethod
    def wait_between(a, b):
        """Awaitable humanized random pause. Alias of talos.aio.wait(a, b)."""
        return _Aio.wait(a, b)

    @staticmethod
    def input(prompt="Script is waiting for input"):
        """Awaitable talos.input: other tasks keep running while the user types.

        The user's next plain chat message is captured (never sent to the server)
        and returned. Commands ("/...") are not captured.
        """
        return _submit(_talos_host.submitUserInput, str(prompt))


aio = _Aio()


# --- persistent state -----------------------------------------------------------

_STATE_MAX_BYTES = 256 * 1024


def _script_name():
    """Name of the running script, from the interpreter's own frame data.

    The main script (and every function it defines) carries the real file path
    as its code's filename, while the embedded talos modules use "embedded:/..."
    — the same distinction _script_frames() relies on. Never user input: Python
    code cannot choose where its frames claim to come from without also being
    the file at that path, and the host re-validates the name before any IO.
    """
    frame = _sys._getframe(1)
    while frame is not None:
        filename = frame.f_code.co_filename
        if not filename.startswith("embedded:") and not filename.startswith("<"):
            name = filename.replace("\\", "/").rsplit("/", 1)[-1]
            return name[:-3] if name.endswith(".py") else name
        frame = frame.f_back
    raise _errors.TalosError("cannot determine the running script's name for talos.state")


class _State:
    """Dict-like storage that survives restarts: talos.state["key"] = value.

    Values must be JSON-serializable (str/int/float/bool/None/list/dict) —
    anything else raises TypeError on assignment. The whole mapping is capped
    at 256KB serialized; a mutation that would cross the cap is rolled back and
    raises ValueError. Contents live ONLY at <gameDir>/talos/state/<script>.json
    (one file per script, named after the running script — no paths ever cross
    the boundary, and no other file APIs exist). Every mutation persists
    immediately, so state survives crashes and /talos script stop alike;
    save() forces a write anyway if you want one.
    """

    __slots__ = ("_data", "_name")

    def __init__(self):
        self._data = None
        self._name = None

    def _load(self):
        if self._data is None:
            self._name = _script_name()
            raw = _errors.call(_talos_host.stateLoad, self._name)
            data = _json.loads(str(raw)) if raw is not None else {}
            self._data = data if isinstance(data, dict) else {}
        return self._data

    def save(self):
        """Persist the current contents now (mutations already save automatically)."""
        data = self._load()
        text = _json.dumps(data)
        if len(text.encode("utf-8")) > _STATE_MAX_BYTES:
            raise ValueError(
                f"talos.state exceeds the 256KB limit ({len(text.encode('utf-8'))} bytes serialized)")
        _errors.call(_talos_host.stateSave, self._name, text)

    def _mutate(self, action):
        """Apply one mutation, persist, and roll back if the 256KB cap is crossed."""
        data = self._load()
        snapshot = dict(data)
        action(data)
        try:
            self.save()
        except ValueError:
            self._data = snapshot
            raise

    def __setitem__(self, key, value):
        if not isinstance(key, str):
            raise TypeError("talos.state keys must be strings")
        try:
            _json.dumps(value)
        except (TypeError, ValueError) as error:
            raise TypeError(f"talos.state values must be JSON-serializable: {error}") from None
        self._mutate(lambda data: data.__setitem__(key, value))

    def __getitem__(self, key):
        return self._load()[key]

    def __delitem__(self, key):
        self._mutate(lambda data: data.__delitem__(key))

    def __contains__(self, key):
        return key in self._load()

    def __len__(self):
        return len(self._load())

    def __iter__(self):
        return iter(self._load())

    def __repr__(self):
        return f"talos.state({self._load()!r})"

    def get(self, key, default=None):
        return self._load().get(key, default)

    def setdefault(self, key, default=None):
        data = self._load()
        if key not in data:
            self[key] = default  # routes through validation + persist
        return data[key]

    def keys(self):
        return self._load().keys()

    def values(self):
        return self._load().values()

    def items(self):
        return self._load().items()

    def pop(self, key, *default):
        data = self._load()
        if key not in data:
            if default:
                return default[0]
            raise KeyError(key)
        value = data[key]
        self._mutate(lambda d: d.__delitem__(key))
        return value

    def update(self, mapping):
        for key, value in (mapping.items() if hasattr(mapping, "items") else mapping):
            self[key] = value

    def clear(self):
        self._mutate(lambda data: data.clear())


state = _State()
