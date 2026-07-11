# In-game UI

`/glade ui` opens Glade's "liquid-glass" toolkit screen — a frosted,
gradient-bordered rounded panel drawn entirely through Glade's own render
pipeline (`GladeUi`/`GladeRenderPipelines`), not vanilla `DrawContext` widget
calls. This is a v1 surface: functional, but intentionally small in scope so
far.

## Opening it

```
/glade ui
```

This opens `GladeScreen`: a single panel that scale-fades in over ~220ms
(`EaseOutQuad`), currently hosting:

- A **theme toggle** (`ToggleSwitch`) labeled "Light theme" — flips between
  the Dark and Light palettes.
- A **Close** button.

## Themes

`ThemeMode` has three values: `DARK`, `LIGHT`, and `SYSTEM` (which currently
resolves to `DARK` — there's no OS-appearance query wired up yet, so `SYSTEM`
and `DARK` are equivalent today). The toggle in `GladeScreen` only exposes
Dark/Light.

Under the hood there are actually **four** color palettes defined in
`ThemePalette`:

| Palette | Character |
|---|---|
| `DARK` | cool near-black background, blue accent |
| `LIGHT` | cool near-white background, same blue accent |
| `SOFT_DARK` | warm greige-toned dark background, yellow accent |
| `SOFT_LIGHT` | warm greige-toned light background, magenta accent |

The two `SOFT_*` "warm" variants exist and are fully defined in code, but
**no `ThemeMode` value selects them yet** — they're not reachable from
`/glade ui` or `config/glade.json` (`GladeConfig.themeMode` only round-trips
through `ThemeMode.valueOf`, which has no `SOFT` case). Treat them as staged
for a future release rather than a currently-usable option.

Whichever palette is active drives every color Glade's UI draws — panels,
text, outlines, and the accent gradient — via `Theme.palette()`, so switching
modes re-themes every open screen live, with nothing caching a stale palette
reference across frames.

## Persistence — current gap

`GladeConfig` (`config/glade.json`) has a `themeMode` field and an
`activeProfile` field (the default humanization profile), and both are
**loaded and applied** at startup (`GladeClientMod.onInitializeClient` calls
`GladeConfigManager.setThemeMode`/`setActiveProfile`). However, toggling the
theme from `/glade ui` right now only calls `Theme.setMode(...)` directly —
it does **not** call back into `GladeConfigManager` to persist the change, so
a theme flipped in-game reverts to whatever `config/glade.json` says on next
launch. This is a known, explicitly-flagged gap in the code
(`GladeClientMod`'s init comment: "follow-up: wire live UI toggles ... to
call GladeConfigManager.setThemeMode/setActiveProfile so in-app changes
persist"). To persist a theme change today, edit `config/glade.json` by hand.

## Block editor (`/glade editor`) — not implemented

The project's design plan describes a native, drag-and-drop block editor
(Blockly-style: event hats, action blocks, control C-blocks, value
reporters) that compiles down to the exact same Python the hand-written
`glade` API produces, with a source map so a runtime error highlights the
originating block. **This does not exist in the codebase yet** — there is no
`dev.glade.client.blockeditor` package and no `/glade editor` command. When
it ships, the intent is that block-authored and hand-authored scripts are
textually/semantically interchangeable — the block editor is just another
way to produce a `.py` file that runs through the exact same
`ScriptEngine`/GraalPy path described in
[`docs/scripting.md`](scripting.md). Until then, all scripting is
hand-written Python (optionally edited with the VS Code extension — see
[`docs/vscode.md`](vscode.md)).

## Rendering notes (for context, not user-facing)

Glass panels are drawn through a custom `RenderPipeline` (not JSON core
shaders, which were removed from Minecraft in 1.21.9+) registered once in
`GladeRenderPipelines`; shapes are SDF rounded rects (`SdfBatch`,
`RoundedRectRenderState`) with per-corner radii and an animated multi-stop
accent gradient. See [`docs/architecture.md`](architecture.md) for the wider
render/UI module layout if you're contributing.
