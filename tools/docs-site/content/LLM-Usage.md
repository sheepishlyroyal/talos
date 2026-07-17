# Using Talos with an LLM

You don't have to memorise the API. Talos ships a single **agent skill** — [`skill/SKILL.md`](https://github.com/sheepishlyroyal/talos/blob/main/skill/SKILL.md)
— that is the complete authoring contract: every command, the full Python API, the coordinate rules,
the getter/trigger catalog, canonical patterns, and the gotchas that trip models up (worker-thread
blocking, the chat echo, `^` meaning two different things). Give that file to any model and it can write
correct Talos scripts and commands for you.

There are two ways to use it: **install it** (best, for Claude Code) or **paste it** (works anywhere).

> **Best setup:** Talos + an LLM + the [terminal CLI](Terminal-CLI). The LLM writes the script from
> this skill, then runs it itself with `talos run script.py` and reads the streamed logs to iterate —
> a fully closed loop: you describe the goal, the model writes, runs, watches and fixes.

---

## Option A — Install everything (recommended)

**This block installs three things at once:** the **Talos mod jar** into your Minecraft `mods/`
folder, the **`talos` terminal CLI** into `~/.talos/bin/`, and the **Claude skill** into
`~/.claude/skills/talos/` — the complete LLM + CLI setup in one paste. Set `MC` to your Minecraft
version (`1.21.11`, `26.1` or `26.2`) first.

<div class="install-primary">
<span class="install-label">Installs Talos + CLI + skill</span>

:::os-tabs
@macos
MC=1.21.11   # or 26.1 / 26.2 — match your Minecraft
MODS="$HOME/Library/Application Support/minecraft/mods"
mkdir -p "$MODS" ~/.talos/bin ~/.claude/skills/talos
curl -fsSL -o "$MODS/talos-mod-1.1.0-mc$MC.jar" \
  "https://github.com/sheepishlyroyal/talos/releases/download/v1.1.0/talos-mod-1.1.0-mc$MC.jar"
curl -fsSL -o ~/.talos/bin/talos \
  "https://github.com/sheepishlyroyal/talos/releases/download/v1.1.0/talos-cli"
chmod +x ~/.talos/bin/talos
curl -fsSL -o ~/.claude/skills/talos/SKILL.md \
  "https://raw.githubusercontent.com/sheepishlyroyal/talos/main/skill/SKILL.md"
@linux
MC=1.21.11   # or 26.1 / 26.2 — match your Minecraft
MODS="$HOME/.minecraft/mods"
mkdir -p "$MODS" ~/.talos/bin ~/.claude/skills/talos
curl -fsSL -o "$MODS/talos-mod-1.1.0-mc$MC.jar" \
  "https://github.com/sheepishlyroyal/talos/releases/download/v1.1.0/talos-mod-1.1.0-mc$MC.jar"
curl -fsSL -o ~/.talos/bin/talos \
  "https://github.com/sheepishlyroyal/talos/releases/download/v1.1.0/talos-cli"
chmod +x ~/.talos/bin/talos
curl -fsSL -o ~/.claude/skills/talos/SKILL.md \
  "https://raw.githubusercontent.com/sheepishlyroyal/talos/main/skill/SKILL.md"
@windows
# PowerShell
$MC = "1.21.11"   # or 26.1 / 26.2 — match your Minecraft
$Mods = "$env:APPDATA\.minecraft\mods"
New-Item -ItemType Directory -Force $Mods, "$env:USERPROFILE\.talos\bin", "$env:USERPROFILE\.claude\skills\talos" | Out-Null
Invoke-WebRequest "https://github.com/sheepishlyroyal/talos/releases/download/v1.1.0/talos-mod-1.1.0-mc$MC.jar" -OutFile "$Mods\talos-mod-1.1.0-mc$MC.jar"
Invoke-WebRequest "https://github.com/sheepishlyroyal/talos/releases/download/v1.1.0/talos-cli" -OutFile "$env:USERPROFILE\.talos\bin\talos"
Invoke-WebRequest "https://raw.githubusercontent.com/sheepishlyroyal/talos/main/skill/SKILL.md" -OutFile "$env:USERPROFILE\.claude\skills\talos\SKILL.md"
:::

</div>

Still needed on top: **Fabric Loader + Fabric API** (see [Installation](Installation)), and put the
CLI on your PATH (see [Terminal CLI](Terminal-CLI)). One-time in-game: `/talos bridge allow`.

### Skill only

Already have the mod and CLI? This just drops `SKILL.md` into `~/.claude/skills/` (Claude Code
auto-loads it by name):

<div class="install-secondary">

:::os-tabs
@macos
mkdir -p ~/.claude/skills/talos
curl -fsSL https://raw.githubusercontent.com/sheepishlyroyal/talos/main/skill/SKILL.md \
  -o ~/.claude/skills/talos/SKILL.md
@linux
mkdir -p ~/.claude/skills/talos
curl -fsSL https://raw.githubusercontent.com/sheepishlyroyal/talos/main/skill/SKILL.md \
  -o ~/.claude/skills/talos/SKILL.md
@windows
# PowerShell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.claude\skills\talos" | Out-Null
Invoke-WebRequest https://raw.githubusercontent.com/sheepishlyroyal/talos/main/skill/SKILL.md `
  -OutFile "$env:USERPROFILE\.claude\skills\talos\SKILL.md"
:::

</div>

Or, per-project, copy it into your repo's `.claude/skills/talos/SKILL.md`. Then just ask:

> "Write a Talos script that strip-mines at Y=-59 and drops off loot in a chest when the inventory
> is nearly full."

Claude will pull in the `talos` skill automatically and write against the real API.

**Cursor / Windsurf / other agentic editors:** add the contents of `SKILL.md` to your project rules
(`.cursorrules`, `AGENTS.md`, a project instruction file, or the editor's "rules for AI" setting).

---

## Option B — Paste the skill (ChatGPT, Gemini, local models, any chat)

For any model without a skill system, paste the skill into the context once, then ask for what you want.
A reliable framing:

```
You are helping me write automation for Talos, a client-side Minecraft Fabric mod with an
embedded Python runtime (`import talos`). The following is the complete Talos authoring
contract. Follow it exactly — only use the documented API; do not invent functions, and do
not import anything except `talos`.

<PASTE THE ENTIRE CONTENTS OF skill/SKILL.md HERE>

Task: <describe what you want the script to do>
```

Get the raw file to paste from:
<https://raw.githubusercontent.com/sheepishlyroyal/talos/main/skill/SKILL.md>

---

## Getting good results

The skill already tells the model the important rules, but you'll get better scripts if your request
includes:

- **The trigger** — "on a timer", "when my health drops below 6", "when I pick up cobblestone", "as a
  `/talos` command I can run".
- **The goal and stop condition** — "mine 3 stacks then stop", "run until I `/talos script stop`".
- **Any items/coords/selectors** — real block ids (`minecraft:diamond_ore`), coordinates, entity
  selectors (`@e[type=zombie,distance=..16]`).

Then verify the output against the skill's **Gotchas checklist** — the model should have:

- imported only `talos`,
- ended the script with `talos.run()` if it registers any `@task` / `@on` / `@every` / `@command`,
- used `await talos.aio.<action>()` (not the blocking form) when things run concurrently,
- guarded `chat` handlers against your own echoed messages.

## Try it in-game without an LLM

Every pattern the model will produce is also available as a bundled reference script you can read and run
directly:

```
/talos example                 # list bundled examples
/talos example sensors         # writes talos/scripts/example_sensors.py
/talos script run example_sensors
```

See [Example scripts](Examples) for the full source of each.

## What the model should *not* do

- Import `os`, `sys`, `requests`, `socket`, open files, or call `pip` — none of that exists in the
  sandbox. Only `import talos`.
- Promise undetectability. Talos models motor-level imperfection only; it is best-effort obfuscation and
  automation may still break a server's rules.
- Invent getter/trigger names. The catalog is fixed at 206 — `/talos get list` dumps every valid name,
  and the skill lists the families.
