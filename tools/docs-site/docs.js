/* Talos docs: client-side search, code copy buttons, TOC highlight,
   OS command tabs, blur-in text, sidebar hover indicator. */
(function () {
  "use strict";

  var reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---- OS command tabs (synced + persisted) ---- */
  (function () {
    var groups = document.querySelectorAll(".os-tabs");
    if (!groups.length) return;
    var stored = null;
    try { stored = localStorage.getItem("talos-docs-os"); } catch (e) {}
    if (!stored) {
      var p = (navigator.platform || "").toLowerCase();
      stored = p.indexOf("win") >= 0 ? "windows" : p.indexOf("linux") >= 0 ? "linux" : "macos";
    }
    function apply(os) {
      groups.forEach(function (g) {
        var has = g.querySelector('button[data-os="' + os + '"]');
        var target = has ? os : g.querySelector(".os-tab-buttons button").dataset.os;
        g.querySelectorAll(".os-tab-buttons button").forEach(function (b) {
          if (b.dataset.os === target) b.setAttribute("selected", "");
          else b.removeAttribute("selected");
        });
        g.querySelectorAll(".os-tab-panel").forEach(function (panel) {
          panel.hidden = panel.dataset.os !== target;
        });
      });
    }
    groups.forEach(function (g) {
      g.querySelectorAll(".os-tab-buttons button").forEach(function (b) {
        b.addEventListener("click", function () {
          try { localStorage.setItem("talos-docs-os", b.dataset.os); } catch (e) {}
          apply(b.dataset.os);
        });
      });
    });
    apply(stored);
  })();

  /* ---- blur-in text ---- */
  (function () {
    if (reducedMotion) return;

    // Split an element's text into word spans, preserving inline children.
    function splitWords(el) {
      var nodes = Array.prototype.slice.call(el.childNodes);
      var spans = [];
      nodes.forEach(function (node) {
        if (node.nodeType === Node.TEXT_NODE) {
          var frag = document.createDocumentFragment();
          node.textContent.split(/(\s+)/).forEach(function (part) {
            if (!part) return;
            if (/^\s+$/.test(part)) { frag.appendChild(document.createTextNode(part)); return; }
            var s = document.createElement("span");
            s.className = "bt-word";
            s.textContent = part;
            frag.appendChild(s);
            spans.push(s);
          });
          el.replaceChild(frag, node);
        } else if (node.nodeType === Node.ELEMENT_NODE) {
          var w = document.createElement("span");
          w.className = "bt-word";
          el.replaceChild(w, node);
          w.appendChild(node);
          spans.push(w);
        }
      });
      return spans;
    }

    function reveal(spans, step) {
      spans.forEach(function (s, i) {
        s.style.transitionDelay = (i * step) + "ms";
        requestAnimationFrame(function () { s.classList.add("bt-in"); });
      });
    }
    function resetSpans(spans) {
      spans.forEach(function (s) {
        s.style.transitionDelay = "";
        s.classList.remove("bt-in", "bt-static");
      });
    }

    // Sidebar: word-level, but only once per browser session — navigating
    // between pages must not replay it.
    var sideDone = false;
    try { sideDone = sessionStorage.getItem("talos-docs-side-anim") === "1"; } catch (e) {}
    if (!sideDone) {
      var sideEls = document.querySelectorAll(".nav-group-title, .nav-group a");
      var sideSpans = [];
      sideEls.forEach(function (el) { sideSpans = sideSpans.concat(splitWords(el)); });
      reveal(sideSpans, 32);
      try { sessionStorage.setItem("talos-docs-side-anim", "1"); } catch (e) {}
    }

    // Main content. Rules:
    //  - anything visible at page load shows instantly (no replay on navigation),
    //  - anything scrolled INTO view animates — on the way down and the way up,
    //  - after an element fully leaves the viewport it re-arms, so it animates
    //    again the next time it enters.
    var wordTargets = document.querySelectorAll(".prose h1, .prose h2, .prose h3");
    var blockTargets = document.querySelectorAll(
      ".prose p, .prose ul, .prose ol, .prose table, .prose pre, .prose blockquote, .os-tabs, .pager");
    var pending = new Map();
    var shown = new WeakSet();

    function initiallyVisible(el) {
      var r = el.getBoundingClientRect();
      return r.top < window.innerHeight && r.bottom > 0;
    }

    wordTargets.forEach(function (el) {
      var spans = splitWords(el);
      pending.set(el, spans);
      if (initiallyVisible(el)) {
        spans.forEach(function (s) { s.classList.add("bt-static"); });
        shown.add(el);
      }
    });
    blockTargets.forEach(function (el) {
      el.classList.add("bl-el");
      if (initiallyVisible(el)) {
        el.classList.add("bl-static");
        shown.add(el);
      }
    });

    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        var el = entry.target;
        var spans = pending.get(el);
        if (entry.isIntersecting && entry.intersectionRatio >= 0.1) {
          if (shown.has(el)) return;
          shown.add(el);
          if (spans) reveal(spans, 55);
          else requestAnimationFrame(function () { el.classList.add("bl-in"); });
        } else if (!entry.isIntersecting && entry.intersectionRatio === 0) {
          // Fully out of view: re-arm so the next entry animates again.
          if (!shown.has(el)) return;
          shown.delete(el);
          if (spans) resetSpans(spans);
          else el.classList.remove("bl-in", "bl-static");
        }
      });
    }, { threshold: [0, 0.1] });
    wordTargets.forEach(function (el) { io.observe(el); });
    blockTargets.forEach(function (el) { io.observe(el); });
  })();

  /* ---- sidebar indicator: follows the mouse, sticky selection ---- */
  (function () {
    var sidebar = document.getElementById("sidebar");
    if (!sidebar) return;
    var links = Array.prototype.slice.call(sidebar.querySelectorAll(".nav-group a"));
    if (!links.length) return;
    var indicator = document.createElement("div");
    indicator.id = "nav-indicator";
    sidebar.appendChild(indicator);

    var current = location.pathname.split("/").pop() || "index.html";
    if (current === "index.html") current = "Home.html";
    var activeLink = links.filter(function (a) {
      return a.getAttribute("href") === current;
    })[0] || null;

    var sticky = null;
    try { sticky = localStorage.getItem("talos-docs-sticky"); } catch (e) {}
    var stickyOn = !!(sticky && activeLink && sticky === current);
    function paintSticky() { document.body.classList.toggle("nav-sticky", stickyOn); }
    paintSticky();

    function moveTo(link) {
      if (!link) { indicator.style.opacity = "0"; return; }
      indicator.style.opacity = "1";
      indicator.style.height = link.offsetHeight + "px";
      indicator.style.transform = "translateY(" + link.offsetTop + "px)";
    }
    function rest() { moveTo(activeLink); }
    rest();

    var hovered = null;
    links.forEach(function (link) {
      link.addEventListener("mouseenter", function () {
        if (hovered) hovered.classList.remove("hovered");
        hovered = link;
        link.classList.add("hovered");
        moveTo(link); // the green line always follows the mouse
      });
    });
    sidebar.addEventListener("mouseenter", function () {
      document.body.classList.add("nav-hovering");
    });
    sidebar.addEventListener("mouseleave", function () {
      document.body.classList.remove("nav-hovering");
      if (hovered) hovered.classList.remove("hovered");
      hovered = null;
      rest();
    });

    links.forEach(function (link) {
      link.addEventListener("click", function (e) {
        var href = link.getAttribute("href");
        if (href === current) {
          // Clicking the current page toggles stickiness instead of reloading.
          e.preventDefault();
          stickyOn = !stickyOn;
          try {
            if (stickyOn) localStorage.setItem("talos-docs-sticky", current);
            else localStorage.removeItem("talos-docs-sticky");
          } catch (err) {}
          paintSticky();
        } else {
          // A new selection takes priority and becomes the sticky one.
          try { localStorage.setItem("talos-docs-sticky", href); } catch (err) {}
        }
      });
    });
  })();

  /* ---- code copy buttons ---- */
  document.querySelectorAll(".prose pre").forEach(function (pre) {
    var btn = document.createElement("button");
    btn.className = "copy-btn";
    btn.type = "button";
    btn.textContent = "COPY";
    btn.addEventListener("click", function () {
      var code = pre.querySelector("code");
      navigator.clipboard.writeText(code ? code.innerText : pre.innerText).then(function () {
        btn.textContent = "COPIED";
        btn.classList.add("copied");
        setTimeout(function () {
          btn.textContent = "COPY";
          btn.classList.remove("copied");
        }, 1200);
      });
    });
    pre.appendChild(btn);
  });

  /* ---- TOC current-section highlight ---- */
  var tocLinks = Array.prototype.slice.call(document.querySelectorAll(".toc a"));
  if (tocLinks.length) {
    var map = {};
    tocLinks.forEach(function (a) { map[a.getAttribute("href").slice(1)] = a; });
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          tocLinks.forEach(function (a) { a.classList.remove("current"); });
          var link = map[entry.target.id];
          if (link) link.classList.add("current");
        }
      });
    }, { rootMargin: "-72px 0px -70% 0px" });
    tocLinks.forEach(function (a) {
      var el = document.getElementById(a.getAttribute("href").slice(1));
      if (el) observer.observe(el);
    });
  }

  /* ---- search ---- */
  var input = document.getElementById("search");
  var results = document.getElementById("search-results");
  if (!input || !results) return;
  var index = null;
  var selected = -1;

  function load() {
    if (index) return Promise.resolve(index);
    return fetch("assets/search-index.json")
      .then(function (r) { return r.json(); })
      .then(function (data) { index = data; return data; });
  }

  function escapeHtml(s) {
    return s.replace(/[&<>"]/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
  }

  function snippet(text, q) {
    var i = text.toLowerCase().indexOf(q);
    if (i < 0) return "";
    var start = Math.max(0, i - 40);
    var s = (start > 0 ? "…" : "") + text.slice(start, i + q.length + 60) + "…";
    var esc = escapeHtml(s);
    var re = new RegExp(q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "ig");
    return esc.replace(re, function (m) { return "<mark>" + m + "</mark>"; });
  }

  function run(q) {
    q = q.trim().toLowerCase();
    selected = -1;
    if (q.length < 2) { results.hidden = true; results.innerHTML = ""; return; }
    load().then(function (pages) {
      var hits = [];
      pages.forEach(function (p) {
        var titleHit = p.title.toLowerCase().indexOf(q) >= 0;
        var headingHit = null;
        (p.headings || []).some(function (h) {
          if (h.name.toLowerCase().indexOf(q) >= 0) { headingHit = h; return true; }
          return false;
        });
        var textHit = p.text.toLowerCase().indexOf(q) >= 0;
        if (titleHit || headingHit || textHit) {
          hits.push({
            page: p,
            url: headingHit ? p.url + "#" + headingHit.id : p.url,
            label: headingHit ? headingHit.name : p.title,
            rank: titleHit ? 0 : headingHit ? 1 : 2,
            snip: textHit ? snippet(p.text, q) : ""
          });
        }
      });
      hits.sort(function (a, b) { return a.rank - b.rank; });
      hits = hits.slice(0, 10);
      if (!hits.length) {
        results.innerHTML = '<a tabindex="-1"><span class="hit-page">No results</span></a>';
        results.hidden = false;
        return;
      }
      results.innerHTML = hits.map(function (h) {
        return '<a href="' + h.url + '"><strong>' + escapeHtml(h.label) + "</strong>" +
          '<span class="hit-page">' + escapeHtml(h.page.title) +
          (h.snip ? " — " + h.snip : "") + "</span></a>";
      }).join("");
      results.hidden = false;
    });
  }

  input.addEventListener("input", function () { run(input.value); });
  input.addEventListener("focus", function () { if (input.value) run(input.value); });
  input.addEventListener("keydown", function (e) {
    var links = results.querySelectorAll("a[href]");
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!links.length) return;
      selected = e.key === "ArrowDown"
        ? Math.min(selected + 1, links.length - 1)
        : Math.max(selected - 1, 0);
      links.forEach(function (a, i) { a.classList.toggle("selected", i === selected); });
    } else if (e.key === "Enter" && selected >= 0 && links[selected]) {
      window.location.href = links[selected].getAttribute("href");
    } else if (e.key === "Escape") {
      results.hidden = true;
      input.blur();
    }
  });
  document.addEventListener("click", function (e) {
    if (!e.target.closest(".search-wrap")) results.hidden = true;
  });
  document.addEventListener("keydown", function (e) {
    if (e.key === "/" && document.activeElement !== input) {
      e.preventDefault();
      input.focus();
    }
  });
})();
