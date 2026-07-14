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


def run():
    """Start the engine explicitly. Optional - the decorators start it automatically."""
    _ensure_running()


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
    def wait_between(a, b):
        """Awaitable talos.wait_between: humanized random pause, other tasks keep running."""
        return sleep(_errors.call(_talos_host.randomBetween, float(a), float(b)))

    @staticmethod
    def input(prompt="Script is waiting for input"):
        """Awaitable talos.input: other tasks keep running while the user types.

        The user's next plain chat message is captured (never sent to the server)
        and returned. Commands ("/...") are not captured.
        """
        return _submit(_talos_host.submitUserInput, str(prompt))


aio = _Aio()
