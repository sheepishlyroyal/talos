#!/usr/bin/env python3
"""Builds the Talos docs site (static HTML) from tools/docs-site/content/*.md into docs/.

Usage: python3 tools/docs-site/build.py
Needs: pip install markdown
"""
import html as html_mod
import json
import re
import shutil
from pathlib import Path

import markdown

OS_LABELS = {"macos": "macOS", "linux": "Linux", "windows": "Windows"}


def os_tabs(md_text: str) -> str:
    """Expands ':::os-tabs' blocks into tabbed per-OS command HTML.

    :::os-tabs
    @macos
    cp cli/talos ~/.talos/bin/talos
    @windows
    copy cli\\talos %USERPROFILE%\\.talos\\bin\\talos
    :::
    """
    def expand(m):
        sections, current = {}, None
        for line in m.group(1).splitlines():
            if line.startswith("@"):
                current = line[1:].strip().lower()
                sections[current] = []
            elif current is not None:
                sections[current].append(line)
        buttons, panels = [], []
        for i, (os_key, lines) in enumerate(sections.items()):
            label = OS_LABELS.get(os_key, os_key)
            sel = " selected" if i == 0 else ""
            buttons.append(f'<button type="button" data-os="{os_key}"{sel}>{label}</button>')
            code = html_mod.escape("\n".join(lines).strip())
            panels.append(f'<div class="os-tab-panel" data-os="{os_key}"'
                          + ("" if i == 0 else " hidden")
                          + f'><pre><code>{code}</code></pre></div>')
        return ('\n<div class="os-tabs">\n<div class="os-tab-buttons" role="tablist">'
                + "".join(buttons) + "</div>\n" + "\n".join(panels) + "\n</div>\n")
    return re.sub(r":::os-tabs\n(.*?)\n:::", expand, md_text, flags=re.S)

ROOT = Path(__file__).resolve().parents[2]
SRC = Path(__file__).resolve().parent
CONTENT = SRC / "content"
OUT = ROOT / "docs"

# Swap in the real Modrinth URL when the listing is live.
MODRINTH_URL = "#"
GITHUB_URL = "https://github.com/sheepishlyroyal/talos"
RELEASES_URL = GITHUB_URL + "/releases"

# Sidebar structure: (group, [(page-stem, nav title)]).
NAV = [
    ("Getting started", [
        ("Home", "Overview"),
        ("Installation", "Installation"),
    ]),
    ("Reference", [
        ("Commands", "Command reference"),
        ("Event-Rules-and-Getters", "Event rules & getters"),
    ]),
    ("Scripting", [
        ("Scripting", "Python scripting"),
        ("Simulations-and-Custom-Pathfinding", "Simulations & pathfinding"),
        ("Humanization", "Humanization"),
        ("Examples", "Example scripts"),
    ]),
    ("Tools", [
        ("Terminal-CLI", "Terminal CLI"),
        ("VS-Code-Bridge", "VS Code bridge"),
        ("Detailed-Logging", "Detailed logging"),
    ]),
    ("Internals", [
        ("Architecture-and-Testing", "Architecture & testing"),
        ("LLM-Usage", "Using Talos with an LLM"),
    ]),
]

ORDER = [(stem, title) for _, pages in NAV for stem, title in pages]
KNOWN = {stem for stem, _ in ORDER}


def render(stem: str) -> tuple[str, str, list[tuple[int, str, str]]]:
    """Returns (title, body_html, headings [(level, id, text)])."""
    md = markdown.Markdown(extensions=["tables", "fenced_code", "toc"],
                           extension_configs={"toc": {"anchorlink": False}})
    text = (CONTENT / f"{stem}.md").read_text(encoding="utf-8")
    # House style: no emoji in site text; warning glyphs become a styled "Note:" label.
    text = text.replace("⚠️", "**Note:**").replace("⚠", "**Note:**")
    text = re.sub(r"[\U0001F300-\U0001FAFF✅❌❗⭐\U0001F44D\U0001F449]", "", text)
    text = os_tabs(text)
    body = md.convert(text)
    # Wiki-style links -> page.html (keep anchors), leave http(s) and #anchors alone.
    def fix_link(m):
        href = m.group(1)
        base, _, frag = href.partition("#")
        if base in KNOWN:
            return f'href="{base}.html' + (f"#{frag}" if frag else "") + '"'
        return m.group(0)
    body = re.sub(r'href="([^"]+)"', fix_link, body)
    title = ORDER and dict(ORDER).get(stem, stem)
    headings = []
    for tok in md.toc_tokens:
        headings.append((2, tok["id"], tok["name"]))
        for sub in tok.get("children", []):
            headings.append((3, sub["id"], sub["name"]))
    return title, body, headings


def sidebar(active: str) -> str:
    out = []
    for group, pages in NAV:
        out.append(f'<div class="nav-group"><div class="nav-group-title">{group}</div>')
        for stem, title in pages:
            cls = ' class="active"' if stem == active else ""
            out.append(f'<a href="{stem}.html"{cls}>{title}</a>')
        out.append("</div>")
    return "\n".join(out)


def toc(headings) -> str:
    if not headings:
        return ""
    items = "".join(
        f'<a class="toc-{lvl}" href="#{hid}">{name}</a>' for lvl, hid, name in headings)
    return f'<nav class="toc"><div class="toc-title">On this page</div>{items}</nav>'


def prev_next(stem: str) -> str:
    stems = [s for s, _ in ORDER]
    i = stems.index(stem)
    parts = []
    if i > 0:
        s, t = ORDER[i - 1]
        parts.append(f'<a class="pager-link prev" href="{s}.html"><span>Previous</span>{t}</a>')
    else:
        parts.append("<span></span>")
    if i < len(ORDER) - 1:
        s, t = ORDER[i + 1]
        parts.append(f'<a class="pager-link next" href="{s}.html"><span>Next</span>{t}</a>')
    return f'<div class="pager">{"".join(parts)}</div>'


def main():
    template = (SRC / "template.html").read_text(encoding="utf-8")
    OUT.mkdir(exist_ok=True)
    for old in OUT.glob("*.html"):
        old.unlink()
    assets = OUT / "assets"
    assets.mkdir(exist_ok=True)
    shutil.copy(SRC / "style.css", assets / "style.css")
    shutil.copy(SRC / "docs.js", assets / "docs.js")
    (OUT / ".nojekyll").write_text("")

    index = []
    for stem, nav_title in ORDER:
        title, body, headings = render(stem)
        page = (template
                .replace("{{TITLE}}", f"{title} · Talos docs")
                .replace("{{SIDEBAR}}", sidebar(stem))
                .replace("{{TOC}}", toc(headings))
                .replace("{{BODY}}", body)
                .replace("{{PAGER}}", prev_next(stem))
                .replace("{{MODRINTH_URL}}", MODRINTH_URL)
                .replace("{{GITHUB_URL}}", GITHUB_URL)
                .replace("{{RELEASES_URL}}", RELEASES_URL))
        (OUT / f"{stem}.html").write_text(page, encoding="utf-8")
        plain = re.sub(r"<[^>]+>", " ", body)
        plain = re.sub(r"\s+", " ", plain).strip()
        index.append({"title": title, "url": f"{stem}.html", "text": plain[:4000],
                      "headings": [{"id": h[1], "name": h[2]} for h in headings]})
    (assets / "search-index.json").write_text(json.dumps(index), encoding="utf-8")
    shutil.copy(OUT / "Home.html", OUT / "index.html")
    print(f"Built {len(ORDER)} pages -> {OUT}")


if __name__ == "__main__":
    main()
