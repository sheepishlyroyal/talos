# Contributing to Talos

Thanks for wanting to help. This page is the practical map: how the repo is laid out, how to build
each branch, what to test, and the rules that keep the project consistent.

## Repo layout

| Path | What it is |
|---|---|
| `talos-mod/` | The Fabric client mod (Java) — commands, pathfinding, rules, humanizer, bridge, ScriptEngine |
| `talos-mod/src/main/resources/talos_pyapi/talos/` | The Python API shipped inside the jar (`actions.py`, `engine.py`, `sim.py`, …) |
| `talos-graalpy-runtime/` | GraalPy embedding + its unit tests (sandbox flags, stdlib, hard-stop) |
| `talos-pathing-baritone/` | Optional Baritone adapter (separate jar; registers the `talos:pathing_engine` entrypoint) |
| `cli/talos` | The terminal CLI (stdlib-only Python 3.8+; a copy is bundled in `vscode-extension/cli/`) |
| `vscode-extension/` | VS Code extension (TypeScript) + `stubs/talos.pyi` type stubs |
| `examples/` | Reference scripts (`sheep_sim.py`, `custom_goto.py`, `checklist.py`, …) |
| `tools/docs-site/` | Docs-site generator (`build.py` + `content/*.md`) → static output committed to `docs/` |
| `skill/SKILL.md` | The LLM agent skill — the condensed authoring contract |

## Branches & toolchains

| Branch | Minecraft | Mappings | JDK | Build |
|---|---|---|---|---|
| `main` | 1.21.11 | Yarn | 21 | `./gradlew :talos-mod:compileJava` · jar via `:talos-mod:remapJar` |
| `pathing-v2` | 1.21.11 | Yarn | 21 | same as main (pathfinding development branch) |
| `port/26.1` | 26.1 | Mojmap | 26 | `./gradlew :talos-mod:compileJava` · jar via `:talos-mod:jar` (**no remapJar**) |
| `port/26.2` | 26.2 | Mojmap | 26 | same as port/26.1 |

Set `JAVA_HOME` to the matching JDK before building. Features land on `main` first and are
cherry-picked to the other branches; on the port branches expect mechanical renames
(`Text.literal` → `Component.literal`, `ClientCommandManager` → `ClientCommands`,
`networkHandler.sendChatMessage` → `connection.sendChat`).

## Hard invariants (do not break these)

1. **The game thread never enters Python.** All guest code runs on a per-session worker; the game
   thread only completes futures. Anything else deadlocks or crashes.
2. **All Python→game access goes through `@HostAccess.Export` methods on `TalosNativeBridge`**,
   using `await(game.submit(...))`. No new host-class exposure, no reflection, no IO grants —
   scripts are treated as untrusted (see the Context flags in `ScriptEngine.ensureContext()`).
3. **Bounded everywhere.** Worker and game-thread queues are bounded (256) and fail fast; keep new
   pathways bounded too.
4. **Brigadier suggestion providers never call into Python** — suggestions are captured host-side at
   registration.
5. **Humanization stays honest**: it is best-effort obfuscation, never marketed as undetectable, and
   knobs are clamped so user tuning can't produce invalid profiles.

## What to run before a PR

```bash
# Java compiles (repeat on every branch you touched)
./gradlew :talos-mod:compileJava

# GraalPy runtime tests (sandbox flags, stdlib imports, close(true) hard stop)
./gradlew :talos-graalpy-runtime:test

# Python API syntax
for f in talos-mod/src/main/resources/talos_pyapi/talos/*.py examples/*.py; do python3 -m py_compile "$f"; done

# Docs site still builds (if you touched tools/docs-site/ or its content)
python3 tools/docs-site/build.py
```

For runtime verification, run `examples/checklist.py` in-game (`talos checklist.py` from a terminal)
— it PASS/FAILs the scripting surface end to end and lists the manual checks.

## Adding to the Python API

Touch all of these together, or the surfaces drift:

1. `TalosNativeBridge.java` — the `@HostAccess.Export` method (game-thread work via `game.submit`).
2. `talos_pyapi/talos/*.py` — the friendly wrapper + docstring; update `__all__` in `__init__.py`.
   New modules must also be added to `API_FILES` in `ScriptEngine.java`.
3. `vscode-extension/stubs/talos.pyi` — the type stub.
4. Docs, all three surfaces (see next section).

## Documentation parity rule

Every feature must appear in **all three** doc surfaces:

- `README.md` (full form),
- `skill/SKILL.md` (condensed cheat-sheet form — condensing is fine, omission is not),
- the docs site (`tools/docs-site/content/*.md`, then run `build.py` and commit the regenerated
  `docs/`).

## Commits & PRs

- Small, focused commits; imperative subject line; body explains *why* when it isn't obvious.
- Match surrounding code style (the codebase favours compact, comment-light Java with Javadoc on
  public seams; Python docstrings on every exported function).
- PRs against `main`. Say which branches you verified compile.

## Responsible use

Talos automates gameplay. Contributions must keep the honest framing: automation may violate
individual server rules, humanization is best-effort, and nothing in the project bypasses
authentication or distributes Mojang assets.

## Licence

Talos is [MIT-licensed](LICENSE). By submitting a contribution you agree it is provided under the
same licence.
