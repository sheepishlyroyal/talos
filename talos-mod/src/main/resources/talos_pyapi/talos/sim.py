"""Tick-driven simulation framework: run your own pathfinding, animal AI, or any
custom loop safely. Every simulation runs on the script worker thread (never the
game thread), so a slow or broken step can never stall rendering or ticking —
and the framework adds its own limits on top:

- at most MAX_SIMS (16) simulations per script session,
- a per-step soft budget (budget_ms): steps that keep exceeding it auto-throttle
  the simulation to half its rate, with a warning,
- a circuit breaker: 5 consecutive exceptions auto-pause the simulation (resume
  with sim.resume() after fixing the bug),
- stopping the script (or /talos stop) hard-stops every simulation.

Usage:

    import talos
    from talos import sim

    sheep = sim.Simulation("sheep", hz=5)

    @sheep.tick
    def step(dt):
        ...  # one simulation step; dt = seconds since the previous step

    sheep.start()
    talos.run()
"""

MAX_SIMS = 16
_BREAKER_LIMIT = 5      # consecutive exceptions before auto-pause
_THROTTLE_LIMIT = 5     # consecutive over-budget steps before auto-throttle
_MAX_INTERVAL = 200     # ticks (10 s) — throttling never slows a sim past this

_sims = {}


def _log(level, message):
    try:
        _talos_host.logLevel(str(message), level)  # noqa: F821 - injected by the loader
        print(str(message))
    except NameError:
        print(f"[{level}] {message}")


class SimulationError(RuntimeError):
    """Raised for invalid simulation configuration (too many sims, bad rate, ...)."""


class Simulation:
    """A named, rate-limited loop with its own state, RNG, and safety limits."""

    def __init__(self, name, hz=20, budget_ms=5):
        name = str(name)
        if name in _sims and _sims[name].running:
            raise SimulationError(f"simulation {name!r} is already running")
        if len(_sims) >= MAX_SIMS and name not in _sims:
            raise SimulationError(f"too many simulations (max {MAX_SIMS})")
        hz = float(hz)
        if not 0 < hz <= 20:
            raise SimulationError("hz must be in (0, 20] — one step per tick is the fastest allowed")
        self.name = name
        self.interval = max(1, int(round(20.0 / hz)))
        self.budget_ms = max(1.0, float(budget_ms))
        self.state = {}
        import zlib as _zlib
        import random as _random
        self.rng = _random.Random(_zlib.crc32(name.encode("utf-8")))
        self._tick_fn = None
        self._start_fn = None
        self._stop_fn = None
        self._running = False
        self._paused = False
        self._errors_in_a_row = 0
        self._slow_in_a_row = 0
        _sims[name] = self

    # -- decorators -------------------------------------------------------
    def tick(self, fn):
        """Register the per-step function. Takes (dt) or no arguments."""
        import inspect as _inspect
        if _inspect.iscoroutinefunction(fn):
            raise SimulationError("@sim.tick must be a plain 'def' (steps must return quickly)")
        params = len(_inspect.signature(fn).parameters)
        self._tick_fn = (fn, params >= 1)
        return fn

    def on_start(self, fn):
        self._start_fn = fn
        return fn

    def on_stop(self, fn):
        self._stop_fn = fn
        return fn

    # -- lifecycle --------------------------------------------------------
    @property
    def running(self):
        return self._running

    @property
    def paused(self):
        return self._paused

    def seed(self, value):
        """Re-seed this simulation's RNG for reproducible runs."""
        self.rng.seed(int(value))

    def start(self):
        """Start stepping. Requires a @sim.tick function."""
        if self._tick_fn is None:
            raise SimulationError(f"simulation {self.name!r} has no @sim.tick function")
        if self._running:
            return self
        self._running = True
        self._paused = False
        self._errors_in_a_row = 0
        self._slow_in_a_row = 0
        if self._start_fn is not None:
            self._start_fn()
        from . import engine as _engine
        _engine.start(self._loop())
        return self

    def stop(self):
        """Stop stepping (the loop exits on its next scheduled tick)."""
        was_running = self._running
        self._running = False
        if was_running and self._stop_fn is not None:
            try:
                self._stop_fn()
            except BaseException as error:
                _log("warn", f"sim {self.name!r} on_stop failed: {error!r}")

    def pause(self):
        self._paused = True

    def resume(self):
        self._paused = False
        self._errors_in_a_row = 0

    # -- internals --------------------------------------------------------
    async def _loop(self):
        import time as _time
        from . import engine as _engine
        fn, wants_dt = self._tick_fn
        last = _time.monotonic()
        while self._running:
            await _engine.ticks(self.interval)
            if not self._running:
                break
            if self._paused:
                last = _time.monotonic()
                continue
            now = _time.monotonic()
            dt, last = now - last, now
            began = _time.monotonic()
            try:
                fn(dt) if wants_dt else fn()
                self._errors_in_a_row = 0
            except (KeyboardInterrupt, SystemExit):
                raise
            except BaseException as error:
                self._errors_in_a_row += 1
                _log("error", f"sim {self.name!r} step failed ({self._errors_in_a_row}/{_BREAKER_LIMIT}): {error!r}")
                if self._errors_in_a_row >= _BREAKER_LIMIT:
                    self._paused = True
                    self._errors_in_a_row = 0
                    _log("error", f"sim {self.name!r} auto-paused after {_BREAKER_LIMIT} consecutive errors — fix and sim.resume()")
                continue
            elapsed_ms = (_time.monotonic() - began) * 1000.0
            if elapsed_ms > self.budget_ms:
                self._slow_in_a_row += 1
                if self._slow_in_a_row >= _THROTTLE_LIMIT and self.interval < _MAX_INTERVAL:
                    self.interval = min(_MAX_INTERVAL, self.interval * 2)
                    self._slow_in_a_row = 0
                    _log("warn", f"sim {self.name!r} steps keep exceeding {self.budget_ms:.0f}ms — throttled to every {self.interval} ticks")
            else:
                self._slow_in_a_row = 0

    def __repr__(self):
        status = "running" if self._running else "stopped"
        if self._running and self._paused:
            status = "paused"
        return f"<Simulation {self.name!r} {status} every {self.interval}t budget {self.budget_ms:.0f}ms>"


def sims():
    """All registered simulations by name."""
    return dict(_sims)


def stop_all():
    """Stop every registered simulation."""
    for simulation in list(_sims.values()):
        simulation.stop()
