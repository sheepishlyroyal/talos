package dev.talos.client.pathing.sim;

import dev.talos.client.render.RenderQueue;

/**
 * Owner of the route checkpoint boxes (the blue/green/purple "what happens here" markers).
 *
 * <p>Checkpoints mark MODE CHANGES: a box appears wherever the movement primitive switches
 * (walk -> sprint-jump, sprint -> swim, ...), colored by what to do there, plus a sparse
 * cadence on long same-mode stretches and always the goal. The engine draws the full route
 * once at plan time; the live follower re-draws the REMAINING route on a short TTL every
 * second, so the boxes stay visible for the whole run — every run — track progress, and
 * expire on their own moments after the run ends.</p>
 */
public final class RouteRenderer {
    /** Comfortably outlives the follower's 20-tick re-render cadence, dies soon after. */
    private static final int LIFE_TICKS = 60;

    private static int lastRendered;

    private RouteRenderer() {
    }

    /** Draws the route's checkpoint boxes from {@code fromIndex} on, replacing the last set. */
    public static void render(PlannedRoute route, int fromIndex) {
        int count = route.waypoints().size();
        int drawn = 0;
        Primitive previous = null;
        for (int i = Math.max(0, fromIndex); i < count; i++) {
            PlannedRoute.Waypoint waypoint = route.waypoints().get(i);
            Primitive via = waypoint.via();
            boolean transition = i == fromIndex || via != previous;
            previous = via;
            if (i != count - 1 && !transition && i % 6 != 0) continue;
            RenderQueue.add("talos-path-node:" + drawn++,
                    MotionState.box(waypoint.pose(), waypoint.position()),
                    modeColor(via), LIFE_TICKS);
        }
        // Two overlapping plans on screen read as the pathfinder having "two ideas": always
        // drop the previous draw's leftover boxes the moment a fresh set lands.
        for (int i = drawn; i < lastRendered; i++) {
            RenderQueue.remove("talos-path-node:" + i);
        }
        lastRendered = drawn;
    }

    /** Removes every checkpoint box immediately (run finished or cancelled). */
    public static void clear() {
        for (int i = 0; i < lastRendered; i++) {
            RenderQueue.remove("talos-path-node:" + i);
        }
        lastRendered = 0;
    }

    /** One color per movement mode, so a checkpoint box says WHAT to do there at a glance. */
    static int modeColor(Primitive via) {
        if (via == null) return 0x66CCFF;
        return switch (via) {
            case WALK, DROP -> 0x66CCFF;        // cyan: plain movement
            case SPRINT -> 0x33AAFF;            // stronger blue: sprint
            case SPRINT_JUMP, STEP_UP -> 0x66FF88; // green: a jump/climb happens here
            case SWIM -> 0x3355FF;              // deep blue: swimming leg
            case CRAWL -> 0xCCCCFF;             // pale: crawl
            case MINE -> 0xFF9955;              // orange-brown: dig here
            case PLACE -> 0xCC66FF;             // purple: pillar up here
        };
    }
}
