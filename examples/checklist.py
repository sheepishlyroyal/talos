"""v1.1.0 verification checklist — automated part.

Run it (in a world) either way:
    terminal:  talos checklist.py          (streams PASS/FAIL into your shell)
    in-game:   put it in .minecraft/talos/scripts/ then /talos script run checklist

It prints PASS/FAIL for every automatable feature, then stays running so you can
do the 4 manual checks it lists (tab-completion etc.). Stop with /talos stop.
"""

import talos

RESULTS = []


def check(name, fn):
    try:
        fn()
        RESULTS.append((name, True, ""))
        talos.log(f"PASS  {name}")
    except BaseException as error:
        RESULTS.append((name, False, repr(error)))
        talos.warn(f"FAIL  {name} -- {error!r}")


# ---- 1. stdlib works --------------------------------------------------------
def stdlib():
    import random, json, heapq, collections, itertools, re
    n = random.randint(1, 10)
    assert 1 <= n <= 10
    heap = [3, 1, 2]; heapq.heapify(heap)
    assert heap[0] == 1
    assert json.loads(json.dumps({"a": 1})) == {"a": 1}
    assert collections.Counter("aab")["a"] == 2
    assert list(itertools.pairwise("abc")) == [("a", "b"), ("b", "c")]
    assert re.match(r"t(al)os", "talos").group(1) == "al"
check("stdlib: random/json/heapq/collections/itertools/re", stdlib)


# ---- 2. chat & commands -----------------------------------------------------
def chat():
    talos.chat("talos v1.1.0 checklist running")   # should appear in chat
check("talos.chat() sends a message (look at chat!)", chat)

def runcmd():
    talos.run_command("/talos human show")          # should print tuning in chat
check("talos.run_command('/talos human show') dispatches", runcmd)


# ---- 3. humanisation tuning -------------------------------------------------
def tuning():
    before = talos.intensity()
    talos.intensity(1.5)
    assert abs(talos.intensity() - 1.5) < 1e-9
    talos.tune(overshoot_prob=0.3)
    talos.tune(rotation_speed_max=9999)             # must clamp, not break
    knobs = talos.human_knobs()
    assert knobs["overrides"]["overshoot_prob"] == 0.3
    assert knobs["effective"]["rotation_speed_max"] <= 360
    talos.tune(families=["bezier", "min_jerk"])
    assert "bezier" in talos.human_knobs()["families"]
    try:
        talos.tune(not_a_knob=1)
        raise AssertionError("unknown knob was accepted")
    except Exception as e:
        assert "not_a_knob" in str(e) or "Unknown" in str(e)
    talos.reset_tuning()
    after = talos.human_knobs()
    assert after["overrides"] == {} and abs(after["intensity"] - 1.0) < 1e-9
    talos.intensity(before)
check("intensity / tune / clamp / human_knobs / reset_tuning", tuning)


# ---- 3b. drawing overlays -----------------------------------------------------
def drawing():
    feet = talos.player_feet()
    box_id = talos.draw_box(feet, color="aqua", seconds=20)
    talos.draw_line(feet, (feet.x + 5, feet.y + 2, feet.z), color="yellow", seconds=20, id="chk_line")
    try:
        talos.draw_box(feet, color="not_a_color")
        raise AssertionError("bad color accepted")
    except ValueError:
        pass
    talos.draw_clear(box_id)   # line stays visible for the manual look-around
check("draw_box / draw_line / draw_clear (look: yellow line at your feet)", drawing)


# ---- 4. debug switch ---------------------------------------------------------
def debug():
    was = talos.debug_mode()
    talos.debug_mode(True)
    assert talos.debug_mode() is True
    talos.debug("debug-level line (visible because debug is ON)")
    talos.debug_mode(was)
check("debug_mode toggle + talos.debug()", debug)


# ---- 5. simulations (async: needs the engine running) ------------------------
@talos.task
async def sim_checks():
    from talos import sim

    def ticking():
        s = sim.Simulation("chk_tick", hz=20)
        s.tick(lambda: s.state.__setitem__("n", s.state.get("n", 0) + 1))
        s.start()
        return s
    s = ticking()
    await talos.sleep(1.0)
    check("sim: steps run (>=10 steps in 1s @20hz)",
          lambda: (_ for _ in ()).throw(AssertionError(s.state)) if s.state.get("n", 0) < 10 else None)
    s.pause(); n1 = s.state.get("n")
    await talos.sleep(0.4)
    check("sim: pause stops stepping", lambda: None if s.state.get("n") == n1 else (_ for _ in ()).throw(AssertionError("stepped while paused")))
    s.resume(); s.stop()

    b = sim.Simulation("chk_breaker", hz=20)
    @b.tick
    def boom():
        raise ValueError("intentional")
    b.start()
    await talos.sleep(0.8)
    check("sim: circuit breaker auto-pauses after 5 errors",
          lambda: None if b.paused else (_ for _ in ()).throw(AssertionError("not paused")))
    b.stop()

    def rng():
        import zlib
        a = sim.Simulation("chk_rng_a").rng.random()
        assert 0 <= a < 1
    check("sim: per-sim seeded rng", rng)

    # ---- summary + manual phase ----
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    color = "§a" if passed == total else "§c"
    talos.hud(f"{color}checklist: {passed}/{total} automated checks passed", id="chk")
    talos.hud("§7manual: 1) type  /talos checklist_ping <TAB>  -> suggests pong", id="chk_m1")
    talos.hud("§7manual: 2) /talos human set <TAB>  -> knob names suggested", id="chk_m2")
    talos.hud("§7manual: 3) /talos example sim -> script run example_sim (sheep wanders)", id="chk_m3")
    talos.hud("§7manual: 4) /talos human intensity 3 then /talos look 90 0 (slow aim)", id="chk_m4")
    talos.log(f"checklist: {passed}/{total} automated checks passed. "
              "Manual checks are on the HUD. /talos stop when done.")
    if passed < total:
        for name, ok, err in RESULTS:
            if not ok:
                talos.error(f"FAILED: {name} -- {err}")


# ---- manual-check helper command (tests suggest= too) -------------------------
@talos.command("checklist_ping", suggest=[["pong"]])
def ping(args):
    talos.log(f"checklist_ping OK, args={args} (suggestions worked if TAB offered 'pong')")


talos.run()
