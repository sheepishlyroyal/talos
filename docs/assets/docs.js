/* Talos docs: client-side search, code copy buttons, TOC highlight. */
(function () {
  "use strict";

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
