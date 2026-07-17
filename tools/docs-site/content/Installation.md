# Installation

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 · 26.1 · 26.2 |
| Fabric Loader | 0.19.3+ |
| Fabric API | matching your MC version (e.g. `0.141.4+1.21.11`) |
| Java | 21 (build + run) |
| GraalPy | 24.2.2 — **bundled into the jar**, nothing to install |

## Install a prebuilt jar (recommended)

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for your Minecraft version.
2. Download the matching **Fabric API** jar and drop it in your `.minecraft/mods/` folder.
3. Grab the Talos jar for your version from the [**Releases**](https://github.com/sheepishlyroyal/talos/releases)
   page and drop it in `mods/` too:

   | Minecraft | Jar |
   |---|---|
   | 1.21.11 | `talos-mod-1.2.0-mc1.21.11.jar` |
   | 26.1 | `talos-mod-1.2.0-mc26.1.jar` |
   | 26.2 | `talos-mod-1.2.0-mc26.2.jar` |

4. Launch. Type `/talos` in-game to confirm it loaded.

### Optional: Baritone adapter

The separately distributed `talos-pathing-baritone` adapter is picked up automatically if installed and
takes priority over the built-in pathfinder. You don't need it — Talos' own sim-based pathfinder
(`TalosPathingEngine`) is **always available** with no dependency, so `/talos goto` works out of the box.

## Build from source

```bash
git clone https://github.com/sheepishlyroyal/talos.git
cd talos
export JAVA_HOME=$(/usr/libexec/java_home -v 21)     # macOS example
./gradlew :talos-mod:remapJar
```

The output jar lands in `talos-mod/build/libs/`. Branches:

- `main` / `pathing-v2` — the Minecraft **1.21.11** source (Yarn mappings).
- `port/26.1`, `port/26.2` — the **26.1 / 26.2** ports (official Mojang mappings, Java 25).

## Data locations

| Path | Contents |
|---|---|
| `.minecraft/talos/scripts/` | Python scripts run by `/talos script run` (and `require`'d libs) |
| `~/.talos/rules.json` | Persisted event rules and schedules |
| `~/.talos/macros/` | Recorded input macros |
| `~/.talos/token` | Per-session VS Code bridge auth token (regenerated every launch) |
