# Architecture

A developer-facing overview of how Glade is put together. This documents the
code as it exists today — see the README's "Current status" and the other
docs' "not implemented yet" notes for what's staged but not built.

## Module layout

```
glade/
  settings.gradle.kts               includes the 3 Gradle modules
  gradle.properties                 pinned versions
  glade-mod/                        the client-only Fabric mod — never references Baritone
  glade-pathing-baritone/           separate Fabric mod jar: reflective Baritone adapter (LGPL isolated here)
  glade-graalpy-runtime/            plain java-library, nested into glade-mod via Loom include()
  vscode-extension/                 TypeScript, not a Gradle module
```

- **`glade-mod`** depends on Minecraft/Fabric API/Fabric Loader, and on
  `glade-graalpy-runtime` (which it `include()`s so GraalPy's polyglot jars
  end up nested inside the shipped mod jar). It also bundles
  `org.java-websocket:Java-WebSocket` for the VS Code bridge.
- **`glade-pathing-baritone`** compiles against `glade-mod`'s named classes
  only (`compileOnly(project(":glade-mod", configuration = "namedElements"))`)
  and has **no compile-time dependency on Baritone at all** — every Baritone
  call goes through `java.lang.reflect` (see
  `BaritonePathingEngine`/its inner `Api` class), because there's no
  resolvable Maven artifact for a 1.21.11-compatible Baritone build. This
  module produces its own separate mod jar; both the Baritone jar itself and
  this adapter jar go in `mods/` for pathing to work, on top of `glade-mod`.
- **`glade-graalpy-runtime`** is a thin `java-library` module whose only job
  is pinning the GraalPy `polyglot`/`python-community` GAVs
  (`gradle.properties`'s `graalpy_version`) so `glade-mod` doesn't have to
  reference them directly.
- **`vscode-extension`** is standalone TypeScript/npm, not part of the Gradle
  build; see `vscode-extension/README.md` and
  [`docs/vscode.md`](vscode.md).

Build everything with:
```bash
./gradlew build
```
which produces `glade-mod/build/libs/*.jar` and
`glade-pathing-baritone/build/libs/*.jar`.

## Package map (`glade-mod`, root `dev.glade`)

| Package | Responsibility |
|---|---|
| `client` | `GladeClientMod` (Fabric client entrypoint) and `GladeClient` — a tiny static service locator (`taskScheduler()`, `tickBudget()`, `humanizer()`, `pathingEngine()`). |
| `client.command` | One class per `/glade` subtree, registered via `ClientCommandRegistrationCallback` from `GladeCommands.register()`. See [`docs/commands.md`](commands.md). |
| `client.task` | The cooperative tick-budgeted task engine: `GladeTask`/`SimpleTask`/`OneTickTask`, `TaskScheduler` (insertion-ordered, mutex-keyed, capped at 1000 passes/tick with a runaway-task warning), `TickBudgetManager` (shared wall-clock budget, default 2ms/tick, clamped 1–3ms). |
| `client.scan` | `ScanTask` (chunk-by-chunk world scanning, tick-budget-aware) and `BlockStatePredicate` (Brigadier argument type wrapping vanilla block-predicate syntax). Backs `/glade find`. |
| `client.pathing` | The `PathingEngine` contract (`isAvailable`/`goTo`/`cancel`/`isPathing`), `Goal` variants (`GoalBlock`, `GoalNear`, `GoalXZ`, `GoalEntity`), `PathingEngineProvider`/`PathingEngineRegistry` (Fabric entrypoint discovery of `glade:pathing_engine`, falling back to `NoOpPathingEngine` which fails every call with `PathingUnavailableException`). |
| `client.action` | Verified action state machines: `PlaceBlockAction`, `BreakBlockAction`, `KillEntityAction` (+ `WeaponSelector`), each returning an `ActionResult` future and driven through the task engine. `AimController` composes look input with the humanizer. |
| `client.humanize` | `Humanizer` (facade), `HumanizationProfile` (`RAW`/`NATURAL`/`PARANOID` records of reaction time, rotation limits, overshoot, trajectory families), `RotationHumanizer`, `TimingHumanizer`, `MovementHumanizer`, `Distributions`, `SeededRng`. |
| `client.script` | `ScriptEngine` (session/worker/GraalPy `Context` lifecycle), `GladeNativeBridge` (the `@HostAccess.Export`-annotated capability object Python actually calls), `GameThreadExecutor` (script-worker ↔ game-thread marshaling), `EventDispatcher` (`@glade.on(...)` dispatch). |
| `client.bridge` | `GladeBridge` (start/stop facade), `GladeWebSocketServer` (loopback-only, origin-checked, token-authenticated WS server), `BridgeAuth` (per-session token file), `BridgeProtocol` (JSON message (de)serialization). |
| `client.ui` | The custom render toolkit: `ui.pipeline` (`GladeRenderPipelines`, `RoundedRectRenderState`), `ui.draw.GladeUi`, `ui.anim` (`Animation`, `EaseOutQuad`, `Linear`), `ui.theme` (`Theme`, `ThemeMode`, `ThemePalette`, `Spacing`, `ColorUtil`, `AccentGradient`), `ui.widget` (`Widget`, `Button`, `ToggleSwitch`), `ui.screen` (`GladeScreen`, `GladeTestScreen`). See [`docs/ui.md`](ui.md). |
| `client.render` | `RenderQueue`/`WireframeBox` — tick-expiring world-space highlight rendering, backing `/glade glow` and scan-result highlighting. |
| `client.config` | `GladeConfig` (plain Gson data holder) + `GladeConfigManager` (load/save `config/glade.json` atomically, apply loaded values to `Theme`/`Humanizer` at startup). |
| `mc` | Adapter interfaces (`IWorldView`, `IRenderTarget`, `IInputSink`) intended to insulate Minecraft-API touchpoints from future mapping/version churn. |

There is **no `client.blockeditor` package yet** — the native block editor
described in the project's design plan hasn't been built. See
[`docs/ui.md`](ui.md#block-editor-glade-editor--not-implemented).

## The tick-budgeted task engine

`TaskScheduler.tick()` runs once per client tick (`ClientTickEvents.START_CLIENT_TICK`,
wired in `GladeClientMod`), looping over all registered tasks in insertion
order until every task yields, up to `MAX_PASSES_PER_TICK` (1000) passes —
past that it logs a warning and stops (a production runaway-task guard).
Every scan/pathing-monitor/render/script-future-draining piece of work checks
`GladeClient.tickBudget().hasBudgetRemaining()` against **one shared**
`TickBudgetManager` (default 2ms per tick, clamped 1–3ms), rather than each
subsystem getting its own independent budget that could stack up in a single
tick. Tasks declare `Set<Object>` mutex keys (e.g.
`ScanTask.INTENSIVE_MUTEX`) so conflicting heavy work serializes instead of
fighting for the same tick.

## The humanizer

`Humanizer` is a facade over three planners — `RotationHumanizer`,
`TimingHumanizer`, `MovementHumanizer` — each driven by a
`HumanizationProfile` record (`RAW`/`NATURAL`/`PARANOID`, see
[`docs/scripting.md`](scripting.md#humanization-profiles) for the concrete
numbers) and a `SeededRng` scoped per task/script, never a shared global RNG.
`RotationHumanizer` supports multiple trajectory families (cubic Bézier,
minimum-jerk, piecewise-linear) selected per action — the design rationale
(documented in the class Javadoc and the project's plan) is that a single
fixed randomization *shape* with only randomized parameters is itself a
fingerprint; varying the family is the actual mitigation. `AimController` in
`client.action` composes humanized rotation with in-flight actions.
`BaritonePathingEngine` additionally maps the active profile onto Baritone's
own `freeLook`/`antiCheatCompatibility`/`legitMine` settings so pathing
movement and Glade's own aim-humanization don't fight each other (Baritone
drives movement input; Glade's `RotationHumanizer` owns the actual look
packets sent).

## GraalPy sandbox + threading

Covered in full in [`docs/scripting.md`](scripting.md#threading-model); the
short version for contributors:

- One shared `Engine` (`ScriptEngine.SHARED_ENGINE`), pre-warmed on a
  daemon thread at class-init time since first-context init is slow.
- One `Context` per script session, reset (`sys.modules`/globals wiped, `glade`
  API re-installed) between runs rather than recreated, to avoid the leak a
  frequent VS-Code re-run workflow would otherwise cause.
- Locked down via `HostAccess.EXPLICIT`, `allowHostClassLookup(false)`,
  `allowIO(IOAccess.NONE)`, and every other capability
  (`createProcess`/`createThread`/`nativeAccess`/`environmentAccess`/
  `polyglotAccess`) explicitly denied — Python can reach exactly the methods
  annotated `@HostAccess.Export` on `GladeNativeBridge`, nothing else.
- One dedicated single-thread `ThreadPoolExecutor` ("Glade Script Worker")
  per session owns the `Context` end-to-end (create, enter, eval, close).
  **The client tick thread never enters Python** — `GameThreadExecutor`
  marshals `glade.*` calls from the worker to the game thread and back via
  `CompletableFuture`s that the worker blocks on with `.join()`.
  `EventDispatcher` posts events from the game thread into a bounded queue
  drained by the worker, so a stalled Python handler can never block a game
  event's source.
- Stopping (`/glade script stop`, `stop` over the bridge, disconnect, or
  level unload) invalidates all in-flight world-handle futures and calls
  `Context.close(true)`, which force-terminates even a no-yield-point
  infinite loop.

## Custom UI render path

Minecraft removed JSON core shaders in 1.21.9+, so Glade's UI builds its own
`RenderPipeline`s in code (`GladeRenderPipelines`, the single choke point
that registers every pipeline the toolkit uses, insulating the rest of the
UI code from GPU-API churn). Panels are drawn as SDF rounded rectangles
(`RoundedRectRenderState`) rather than textured 9-slices, which is what lets
`ThemePalette` swap every color live without any texture/atlas rebuild.
Screens (`GladeScreen`) own plain `Widget`s (`Button`, `ToggleSwitch`)
directly rather than vanilla `Element`s, forwarding `render`/`mouseClicked`
by hand — see the class doc on `dev.glade.client.ui.widget.Widget` for the
rationale if you're adding a new widget type.

## Building and contributing

- **Toolchain:** JDK 21, Fabric Loom (`fabric-loom` plugin, version pinned in
  `gradle.properties`), Yarn mappings `1.21.11+build.6`.
- **Build:** `./gradlew build` (runs tests too — `glade-graalpy-runtime` and
  `glade-mod` both have JUnit 5 test sources under `src/test`).
- **Run a dev client:** standard Fabric Loom `./gradlew runClient` from
  `glade-mod` (the module includes Fabric API and pulls in
  `glade-graalpy-runtime` automatically).
- **Adding a `/glade` subcommand:** add a class under `client.command`
  following the existing one-class-per-subtree pattern, register it from
  `GladeCommands.registerCommands`, and update
  [`docs/commands.md`](commands.md).
- **Adding a Python API function:** add it to the appropriate file under
  `glade-mod/src/main/resources/glade_pyapi/glade/` (thin Python wrapper) and
  the corresponding `@HostAccess.Export` method on `GladeNativeBridge`
  (`client.script`) — keep the Python side to argument coercion and
  docstrings only, all real logic goes through the game-thread bridge. Update
  [`docs/scripting.md`](scripting.md) and, if the extension's stubs should
  reflect it, `vscode-extension/stubs/glade.pyi`.
- **Adding a pathing adapter:** implement `PathingEngine` +
  `PathingEngineProvider`, register it under the `glade:pathing_engine`
  Fabric entrypoint key in your module's `fabric.mod.json`. `glade-mod` must
  never gain a compile-time reference to whatever backend you're adapting
  (see `glade-pathing-baritone`'s reflective approach for why, if the backend
  is LGPL or otherwise can't be a normal Gradle dependency).
- **Licensing:** Glade itself ships MIT. Baritone is LGPL and is never
  bundled or `include()`d — it's always a separately-installed dependency
  accessed only via the reflective adapter in `glade-pathing-baritone`.
