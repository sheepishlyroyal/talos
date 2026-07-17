# Using Talos with an LLM

You don't have to memorise the API. Talos ships a single **agent skill** — [`skill/SKILL.md`](https://github.com/sheepishlyroyal/talos/blob/main/skill/SKILL.md)
— that is the complete authoring contract: every command, the full Python API, the coordinate rules,
the getter/trigger catalog, canonical patterns, and the gotchas that trip models up (worker-thread
blocking, the chat echo, `^` meaning two different things). Give that file to any model and it can write
correct Talos scripts and commands for you.

There are two ways to use it: **install it** (best, for Claude Code) or **paste it** (works anywhere).

---

## Option A — Install the skill (Claude Code / Claude)

Claude Code auto-loads skills by name from `~/.claude/skills/`. Drop the file in and it's available in
every session:

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
:: PowerShell:
New-Item -ItemType Directory -Force "$env:USERPROFILE\.claude\skills\talos" | Out-Null
Invoke-WebRequest https://raw.githubusercontent.com/sheepishlyroyal/talos/main/skill/SKILL.md `
  -OutFile "$env:USERPROFILE\.claude\skills\talos\SKILL.md"
:::

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
