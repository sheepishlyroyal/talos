package dev.talos.client.pathing.talos;

import dev.talos.client.TalosClient;
import dev.talos.client.pathing.Goal;
import dev.talos.client.pathing.GoalBlock;
import dev.talos.client.pathing.GoalEntity;
import dev.talos.client.pathing.GoalNear;
import dev.talos.client.pathing.GoalXZ;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.pathing.PathingEngine;
import dev.talos.client.pathing.PathingOptions;
import dev.talos.client.script.ScriptGameEvents;
import dev.talos.client.task.TalosTask;
import dev.talos.client.pathing.sim.CoarsePathfinder;
import dev.talos.client.pathing.sim.MovementProfile;
import dev.talos.client.pathing.sim.MotionState;
import dev.talos.client.pathing.sim.PlannedRoute;
import dev.talos.client.pathing.sim.SimFollowTask;
import dev.talos.client.pathing.sim.SimPathfinder;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import dev.talos.client.render.RenderQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Always-available built-in client-side pathing engine. */
public final class TalosPathingEngine implements PathingEngine {
    /** Runtime escape hatch for the retained AStar/NavigateAndActTask implementation. */
    public static boolean USE_SIM = true;
    private static final Logger LOGGER = LoggerFactory.getLogger(TalosPathingEngine.class);
    // Budgets are CAPS, not durations — the search returns the moment the goal is found.
    // Deep caps mean far goals get one complete plan instead of stop-and-replan stutters.
    private static final int SIM_NODE_CAP = 300_000;
    // Wall-clock budgets, deliberately time-based: a strong machine explores proportionally
    // more of the world in the same 1.5 seconds, and a weak one is never punished with
    // frozen ticks — deep searches run FULL SPEED on background planner threads against an
    // immutable SnapshotView, so the client thread pays only the per-section capture memcpy.
    private static final long SIM_SEARCH_NANOS = 1_500_000_000L;
    // Background searches advance in short chunks so keep-moving cuts and cancellation are
    // observed with low latency even mid-budget.
    private static final long PLANNER_CHUNK_NANOS = 20_000_000L;
    /** How far past the start/goal bounding box the world snapshot extends, in blocks. */
    private static final int SNAPSHOT_MARGIN = 32;
    /** Full-speed planner threads; searches are branchy CPU work, so size to the machine. */
    private static final java.util.concurrent.ExecutorService PLANNER_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors() / 2), runnable -> {
                        Thread thread = new Thread(runnable, "Talos Planner");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final int SIM_MAX_ROLLOUT_TICKS = 20;
    // Continuity beats completeness: rather than standing still while a deep search runs to
    // its full budget, the search is CUT the moment movement needs it — after ~8 slices for
    // the initial plan (start walking on the best partial), or whenever the moving follower's
    // remaining route runs short (append whatever the extension has found so far). Depth
    // still accumulates, one stitched extension at a time, while the player never stops.
    private static final long INITIAL_MOVE_NANOS = 120_000_000L;
    private static final int FOLLOWER_STARVING_WAYPOINTS = 12;
    // Hierarchical planning: goals beyond this straight-line distance first get a cheap
    // block-grid corridor (no physics rollouts), and every deep search then funnels toward
    // the corridor cell ~48 ahead instead of the far goal. Deep searches stay small — they
    // are cut early anyway — while the corridor supplies the global direction they lack.
    private static final double COARSE_TRIGGER_DISTANCE_SQ = 64.0 * 64.0;
    private static final long COARSE_TIME_BUDGET_NANOS = 400_000_000L;
    // The coarse corridor stays tick-sliced on the client thread: it needs live
    // isChunkLoaded answers, and its block-grid cells are cheap enough to slice.
    private static final long COARSE_TICK_SLICE_NANOS = 15_000_000L;
    private static final int CORRIDOR_LOOKAHEAD_CELLS = 48;
    private static final int CORRIDOR_INVALIDATE_CELLS = 16;
    // "Within 2 cells" of a corridor sub-goal counts as arrival: the coarse grid is only
    // ever approximately right, and the next extension re-anchors on the corridor anyway.
    private static final double SUB_GOAL_RADIUS_SQ = 4.0;
    private volatile TalosTask activeTask;
    private volatile NavigationRun activeRun;
    private volatile String lastPartialDetail = "";
    private volatile int nodeCount;
    private volatile List<Vec3d> currentNodes = List.of();

    @Override public boolean isAvailable() { return true; }

    @Override
    public CompletableFuture<PathResult> goTo(Goal goal, PathingOptions options) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(options, "options");
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable start = () -> startOnClientThread(client, goal, options, future);
        if (client.isOnThread()) start.run(); else client.execute(start);
        return future;
    }

    private void startOnClientThread(MinecraftClient client, Goal goal, PathingOptions options,
                                     CompletableFuture<PathResult> future) {
        if (activeRun != null && activeRun.future.isDone()) activeRun = null;
        // A new goto supersedes the active one — the player changed their mind; refusing
        // with a task-conflict error forced a manual stop first.
        if (activeRun != null) releaseActiveRun("Superseded by a new goto");
        if (client.world == null || client.player == null) {
            future.complete(new PathResult(false, "No client world or player is loaded"));
            return;
        }

        GoalSnapshot snapshot;
        try {
            snapshot = snapshotGoal(goal, client);
        } catch (IllegalArgumentException exception) {
            future.complete(new PathResult(false, exception.getMessage()));
            return;
        }

        NavigationRun run = new NavigationRun(client, snapshot, options, future);
        activeRun = run;
        ScriptGameEvents.onGotoStart(snapshot.target().getX(), snapshot.target().getY(),
                snapshot.target().getZ());
        future.whenComplete((result, error) -> {
            if (activeRun == run) activeRun = null;
            if (activeTask == run.task) activeTask = null;
            ScriptGameEvents.onGotoDone(error == null && result != null && result.successful(),
                    error != null ? String.valueOf(error.getMessage()) : result.detail());
        });
        launchSegment(run);
    }

    private void launchSegment(NavigationRun run) {
        MinecraftClient client = run.client;
        if (run.cancelled || run.future.isDone()) return;
        if (client.world == null || client.player == null) {
            run.future.complete(new PathResult(false, "World unloaded while re-pathing"));
            return;
        }
        run.attempts++;

        if (USE_SIM) {
            launchSimSegment(run);
            return;
        }

        AStarPathfinder pathfinder = new AStarPathfinder(
                client.world, client.player, run.options.allowMining());
        AStarPathfinder.SearchResult result = pathfinder.find(
                client.player.getBlockPos(), run.snapshot.test(), run.snapshot.target());
        LOGGER.debug("Native path search attempt {}: {}", run.attempts, result.detail());
        if (result.path().isEmpty()) { scheduleReplan(run); return; }

        double distance = squaredDistance(client.player.getBlockPos(), run.snapshot.target());
        if (distance + 0.25 < run.bestDistance) {
            run.bestDistance = distance;
            run.stalledAttempts = 0;
        } else if (run.attempts > 1) {
            run.stalledAttempts++;
        }

        int requestedNodes = run.options.nodeCount() > 0 ? run.options.nodeCount() : nodeCount;
        List<BlockPos> sampledNodes = createNodes(result.path(), requestedNodes);
        java.util.Set<BlockPos> mandatoryNodes = new java.util.HashSet<>();
        for (int i = 1; i < result.path().size(); i++) {
            BlockPos previous = result.path().get(i - 1), current = result.path().get(i);
            if (Math.abs(current.getX() - previous.getX()) > 1
                    || Math.abs(current.getZ() - previous.getZ()) > 1
                    || !pathfinder.isStandable(current)) mandatoryNodes.add(current);
        }
        // Mining, bridging, and parkour landing transitions are never sampled away.
        List<BlockPos> nodes = result.path().stream()
                .filter(pos -> sampledNodes.contains(pos) || mandatoryNodes.contains(pos)).toList();
        currentNodes = NavigateAndActTask.buildSteeringNodes(client, nodes);
        renderNodes(currentNodes);
        CompletableFuture<PathResult> segmentFuture = new CompletableFuture<>();
        NavigateAndActTask task = new NavigateAndActTask(client, nodes, currentNodes,
                run.snapshot.test(), null, segmentFuture, run.options.allowMining());
        run.task = task;
        activeTask = task;
        segmentFuture.whenComplete((segment, error) -> {
            // Completion occurs in task.body(), before the scheduler removes the old task.
            // A mutex-free task is absent from the scheduler's current runnable snapshot,
            // so it executes next tick after the old movement task has been removed.
            TalosClient.taskScheduler().addTask("native-path-handoff", new OneShotTask(
                    () -> segmentCompleted(run, task, result, segment, error)));
        });
        try {
            TalosClient.taskScheduler().addTask("native-path-follow", task);
        } catch (RuntimeException exception) {
            activeTask = null;
            run.future.completeExceptionally(exception);
        }
    }

    /**
     * Plan from a fresh live snapshot. While a follower is active on a partial route, this
     * runs as a background extension whose result is hot-swapped into the moving follower;
     * only with no follower (start of run, or a stuck restart) does the player wait for it.
     */
    private void launchSimSegment(NavigationRun run) {
        MinecraftClient client = run.client;
        if (run.planner != null) return; // one background search at a time
        // Far run start (and stuck restarts before a corridor exists): sketch the global
        // corridor first. The player would be waiting on the deep plan anyway; the coarse
        // task resumes this launch the moment the corridor lands.
        if ((run.follower == null || !run.follower.isActive())
                && maybeStartCoarsePlan(run, true)) {
            return;
        }
        // While a follower is moving on a partial route, the extension search starts FROM
        // THAT ROUTE'S LAST NODE and plans ahead — the result is stitched onto the current
        // route as pure appended progress. Re-planning the whole remainder from the live
        // player was long (time-budget partials) and its near-equal results caused course
        // resets. Only with no active follower does planning start from the live state.
        PlannedRoute base = run.follower != null && run.follower.isActive()
                ? run.follower.currentRoute() : null;
        MotionState start;
        if (base != null && !base.waypoints().isEmpty()) {
            PlannedRoute.Waypoint tail = base.waypoints().getLast();
            start = new MotionState(tail.position(), Vec3d.ZERO, true, tail.pose());
        } else {
            base = null;
            start = SimFollowTask.liveState(client.player);
        }
        run.extensionBase = base;
        MovementProfile profile = MovementProfile.capture(client.player);
        // Mining runs think further ahead: a partial dig plan can commit the player into a
        // cave dead end, so buy a deeper search before the first block is ever broken.
        boolean edits = run.options.allowMining();
        // Mining edges are billed at the REAL break time for the best hotbar tool, so the
        // planner weighs a dig against a detour with the cost the player will actually pay.
        java.util.List<net.minecraft.item.ItemStack> hotbar = new java.util.ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            // Copies: the planner thread reads these while the live stacks keep mutating.
            hotbar.add(client.player.getInventory().getStack(slot).copy());
        }
        // SUB-GOAL FUNNELING: with a corridor in hand, aim this search at the corridor cell
        // ~48 ahead of where it starts rather than the far goal. Reaching the run's REAL
        // goal always still counts (the predicate is an OR), so funneling can never make an
        // already-reachable goal unreachable. A short remaining corridor skips the funnel:
        // the real goal is deep-search sized at that point.
        BlockPos deepGoal = run.snapshot.target();
        Predicate<BlockPos> deepTest = run.snapshot.test();
        int subGoalIndex = -1;
        List<BlockPos> corridor = run.corridor;
        if (corridor != null) {
            Vec3d origin = start.position();
            BlockPos from = BlockPos.ofFloored(origin.x, origin.y + 1.0E-4, origin.z);
            int nearest = nearestCorridorIndex(corridor, from);
            int candidate = nearest + CORRIDOR_LOOKAHEAD_CELLS;
            // Corridor cells past the server's streamed chunks read back as pure air: a
            // deep search funneled there would plan through fiction (and then blacklist a
            // perfectly good corridor for "frontier exhausted"). Clamp the sub-goal back
            // to the last corridor cell whose chunk is actually loaded; the follower's
            // "waiting for chunks" hold buys time for the frontier to advance.
            int frontier = Math.min(candidate, corridor.size() - 1);
            while (frontier > nearest
                    && !client.world.isChunkLoaded(corridor.get(frontier))) {
                frontier--;
            }
            candidate = frontier;
            if (candidate > nearest && candidate < corridor.size() - 1) {
                BlockPos sub = corridor.get(candidate);
                Predicate<BlockPos> realTest = run.snapshot.test();
                deepGoal = sub;
                deepTest = pos -> realTest.test(pos)
                        || pos.getSquaredDistance(sub) <= SUB_GOAL_RADIUS_SQ;
                subGoalIndex = candidate;
            }
        }
        // Capture the immutable world snapshot the planner thread will search. The region
        // covers start→(sub-)goal inflated by SNAPSHOT_MARGIN, so detours stay inside it;
        // the copy is per-section memcpys — the client thread's whole cost for this plan.
        Vec3d origin = start.position();
        BlockPos snapMin = new BlockPos(
                (int) Math.floor(Math.min(origin.x, deepGoal.getX())) - SNAPSHOT_MARGIN,
                (int) Math.floor(Math.min(origin.y, deepGoal.getY())) - SNAPSHOT_MARGIN,
                (int) Math.floor(Math.min(origin.z, deepGoal.getZ())) - SNAPSHOT_MARGIN);
        BlockPos snapMax = new BlockPos(
                (int) Math.ceil(Math.max(origin.x, deepGoal.getX())) + SNAPSHOT_MARGIN,
                (int) Math.ceil(Math.max(origin.y, deepGoal.getY())) + SNAPSHOT_MARGIN,
                (int) Math.ceil(Math.max(origin.z, deepGoal.getZ())) + SNAPSHOT_MARGIN);
        dev.talos.client.pathing.sim.SnapshotView snapshot =
                dev.talos.client.pathing.sim.SnapshotView.capture(client.world, snapMin, snapMax);
        // Mining edges are billed at the REAL break time for the best hotbar tool; block
        // lookups go to the snapshot so the planner thread never touches the live world.
        java.util.function.ToIntFunction<BlockPos> breakTicks = pos ->
                dev.talos.client.pathing.sim.MiningCosts.breakTicks(
                        snapshot.getBlockState(pos), snapshot, pos, hotbar);
        SimPathfinder.Options simOptions = new SimPathfinder.Options(
                edits, edits, edits ? SIM_NODE_CAP * 2 : SIM_NODE_CAP,
                edits ? SIM_SEARCH_NANOS * 2 : SIM_SEARCH_NANOS, SIM_MAX_ROLLOUT_TICKS,
                breakTicks);
        SimPathfinder.Search search = SimPathfinder.begin(snapshot, start, profile,
                deepGoal, deepTest, simOptions);
        PlanningTask task = new PlanningTask(run, search, subGoalIndex);
        run.planner = task;
        if (run.follower == null || !run.follower.isActive()) {
            if (client.player != null) client.player.sendMessage(
                    net.minecraft.text.Text.literal("§bTalos §7» §fplanning"), true);
        }
        task.start();
    }

    private void simPlanCompleted(NavigationRun run, PlanningTask planningTask,
            PlannedRoute route) {
        if (run.planner == planningTask) run.planner = null;
        // An abandoned planner's result is dead: a recovery replan superseded it, and using
        // its stale-origin route here would spawn a second follower next to the fresh one.
        if (planningTask.abandoned) return;
        if (run.cancelled || run.future.isDone()) return;
        if (run.client.player == null || run.client.world == null) {
            run.future.complete(new PathResult(false, "World unloaded while re-pathing"));
            return;
        }
        LOGGER.debug("Simulation path search attempt {}: {}", run.attempts, route.detail());
        // A partial plan is worth explaining out loud: WHY the planner stopped short (node
        // cap, time budget, frontier exhausted) is the difference between "needs a deeper
        // search" and "that goal is genuinely unreachable". Deliberate keep-moving cuts are
        // routine and stay silent; other reasons are announced once per distinct cause (the
        // detail's expanded-node count varies every search and would spam chat otherwise).
        String cause = route.detail();
        int separator = cause.indexOf(';');
        if (separator > 0) cause = cause.substring(0, separator);
        if (!route.reachedGoal() && !cause.startsWith("cut early")
                && !cause.startsWith("stitched: cut early") && !cause.equals(lastPartialDetail)) {
            lastPartialDetail = cause;
            if (run.client.player != null) run.client.player.sendMessage(
                    net.minecraft.text.Text.literal(
                            "§6Talos §7» §fpartial plan: " + route.detail()), false);
        }
        // CORRIDOR INVALIDATION: a funneled search that exhausted its frontier — or burned
        // its whole budget without even a useful partial — means the corridor lied about
        // this segment (a chasm or wall the coarse grid cannot see). Blacklist the corridor
        // cells leading to that sub-goal and re-run the coarse search around them; deep
        // planning continues unfunneled (straight at the real goal) until the new corridor
        // lands, so the worst case is exactly the old pre-corridor behavior.
        if (planningTask.subGoalIndex >= 0 && run.corridor != null && !route.reachedGoal()
                && (route.detail().startsWith("search frontier exhausted")
                        || !planningTask.search.hasUsefulPartial())) {
            invalidateCorridor(run, planningTask.subGoalIndex);
        }
        if (route.waypoints().size() <= 1) {
            // No swap will happen, so re-arm the follower's extension-request flag — it is
            // otherwise only reset inside swapRoute, and a latched flag mutes the fast
            // starving-cadence re-requests for the rest of the route.
            if (run.follower != null && run.follower.isActive()) run.follower.extensionSettled();
            scheduleReplan(run);
            return;
        }

        double distance = squaredDistance(run.client.player.getBlockPos(), run.snapshot.target());
        if (distance + 0.25 < run.bestDistance) {
            run.bestDistance = distance;
            run.stalledAttempts = 0;
        } else if (run.attempts > 1) {
            run.stalledAttempts++;
        }
        List<Vec3d> waypoints = route.waypoints().stream()
                .map(PlannedRoute.Waypoint::position).toList();
        // Simulation routes are never sampled: steering and the blue renderer receive every
        // rollout endpoint, including long 100+ move cave routes.
        currentNodes = List.copyOf(waypoints);
        renderRouteNodes(route);

        // Pipelined extension: the follower is still moving on the previous partial route.
        // The search started from that route's last node, so STITCH — current route plus the
        // freshly planned continuation — and hand the follower one longer route. Appending
        // never resets the course, so momentum survives every extension.
        if (run.follower != null && run.follower.isActive()) {
            PlannedRoute base = run.extensionBase;
            run.extensionBase = null;
            run.follower.swapRoute(base != null ? stitch(base, route) : route);
            return;
        }
        run.extensionBase = null;

        CompletableFuture<PathResult> segmentFuture = new CompletableFuture<>();
        SimFollowTask task = new SimFollowTask(run.client, route, run.snapshot.test(), segmentFuture);
        task.setReplanRequest(() -> {
            if (!run.cancelled && !run.future.isDone()) launchSimSegment(run);
        });
        run.follower = task;
        run.task = task;
        activeTask = task;
        segmentFuture.whenComplete((segment, error) ->
                TalosClient.taskScheduler().addTask("native-path-handoff", new OneShotTask(
                        () -> simSegmentCompleted(run, task, segment, error))));
        try {
            TalosClient.taskScheduler().addTask("native-path-follow", task);
        } catch (RuntimeException exception) {
            activeTask = null;
            run.follower = null;
            run.future.completeExceptionally(exception);
        }
    }

    private void simSegmentCompleted(NavigationRun run, SimFollowTask task, PathResult segment,
            Throwable error) {
        if (run.follower == task) run.follower = null;
        if (activeTask == task) activeTask = null;
        if (run.task == task) run.task = null;
        if (run.cancelled || run.future.isDone()) return;
        if (error != null) { run.future.completeExceptionally(error); return; }
        if (segment.successful()) { run.future.complete(segment); return; }
        ScriptGameEvents.onGotoStuck(segment.detail());
        // The follower is gone; an extension search still in flight was planned from the
        // DEAD route's tail. Abandon it, or launchSimSegment's one-planner guard silently
        // swallows the recovery replan and nobody owns the player (frozen until that stale
        // search finishes — and its route would then start from the wrong place anyway).
        if (run.planner != null) {
            run.planner.abandon();
            run.planner = null;
        }
        // Every non-terminal follower outcome gets a fresh live-state plan indefinitely.
        scheduleReplan(run);
    }

    private void segmentCompleted(NavigationRun run, NavigateAndActTask task,
                                  AStarPathfinder.SearchResult search, PathResult segment,
                                  Throwable error) {
        if (activeTask == task) activeTask = null;
        if (run.task == task) run.task = null;
        if (run.cancelled || run.future.isDone()) return;
        if (error != null) { run.future.completeExceptionally(error); return; }
        if (segment.successful()) { run.future.complete(segment); return; }
        ScriptGameEvents.onGotoStuck(segment.detail());
        scheduleReplan(run);
    }

    /**
     * Starts (or re-flags) the coarse corridor search. Returns true when a search is now in
     * flight AND the caller was a blocked deep-plan launch — the coarse task then owns
     * resuming that launch. A corridor already in hand, a previous coarse failure, or a
     * goal within deep-search range all return false: plan straight at the goal as before.
     */
    private boolean maybeStartCoarsePlan(NavigationRun run, boolean resumeDeepPlan) {
        CoarseTask inFlight = run.coarsePlanner;
        if (inFlight != null) {
            // An invalidation recompute may already be running when a stuck restart needs a
            // plan; promote it to resume the deep launch so the run cannot go ownerless.
            inFlight.resumeDeepPlan |= resumeDeepPlan;
            return resumeDeepPlan;
        }
        if (run.coarseFailed || run.corridor != null) return false;
        MinecraftClient client = run.client;
        if (client.player == null || client.world == null) return false;
        BlockPos from = client.player.getBlockPos();
        BlockPos target = run.snapshot.target();
        if (squaredDistance(from, target) < COARSE_TRIGGER_DISTANCE_SQ) return false;
        // The exact goal cell may be unoccupiable (interaction targets, mid-air pillars);
        // steering the corridor to "beside it" is enough — the deep planner finishes the job.
        Predicate<BlockPos> realTest = run.snapshot.test();
        // The loaded predicate keeps the corridor off unloaded chunks EXPLICITLY (they read
        // as air, which walkability already rejects, but air over a real plain and air over
        // a void look identical — refusing unloaded cells outright is the honest contract).
        CoarsePathfinder.Search search = CoarsePathfinder.begin(client.world, from, target,
                pos -> realTest.test(pos) || pos.getSquaredDistance(target) <= SUB_GOAL_RADIUS_SQ,
                run.corridorBlacklist, COARSE_TIME_BUDGET_NANOS, client.world::isChunkLoaded);
        CoarseTask task = new CoarseTask(run, search, resumeDeepPlan);
        run.coarsePlanner = task;
        if (resumeDeepPlan && client.player != null) client.player.sendMessage(
                net.minecraft.text.Text.literal("§bTalos §7» §fplanning"), true);
        try {
            TalosClient.taskScheduler().addTask("native-path-coarse", task);
        } catch (RuntimeException exception) {
            run.coarsePlanner = null;
            run.future.completeExceptionally(exception);
        }
        return true;
    }

    private void coarsePlanCompleted(NavigationRun run, CoarseTask task,
            CoarsePathfinder.Result result) {
        if (run.coarsePlanner == task) run.coarsePlanner = null;
        if (run.cancelled || run.future.isDone()) return;
        LOGGER.debug("Coarse corridor search: {}", result.detail());
        if (result.reachedGoal() && result.corridor().size() > 1) {
            run.corridor = result.corridor();
            renderCorridorMarkers(run.corridor);
        } else {
            // A partial corridor is worse than none: funneling toward its dead end would
            // ACTIVELY steer away from workable routes. Fall back to plain deep planning.
            run.coarseFailed = true;
        }
        if (task.resumeDeepPlan) launchSimSegment(run);
    }

    /** Cuts the failed segment out of the corridor and recomputes around the blacklist. */
    private void invalidateCorridor(NavigationRun run, int subGoalIndex) {
        List<BlockPos> corridor = run.corridor;
        run.corridor = null;
        clearCorridorMarkers();
        int last = Math.min(subGoalIndex, corridor.size() - 1);
        for (int i = Math.max(0, subGoalIndex - CORRIDOR_INVALIDATE_CELLS); i <= last; i++) {
            run.corridorBlacklist.add(corridor.get(i));
        }
        maybeStartCoarsePlan(run, false);
    }

    /** Index of the corridor cell nearest to the deep search's start position. */
    private static int nearestCorridorIndex(List<BlockPos> corridor, BlockPos from) {
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < corridor.size(); i++) {
            double distance = corridor.get(i).getSquaredDistance(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private void scheduleReplan(NavigationRun run) {
        if (run.cancelled || run.future.isDone()) return;
        TalosClient.taskScheduler().addTask("native-path-replan", new OneShotTask(
                () -> launchSegment(run)));
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public void cancel() {
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable cancel = () -> releaseActiveRun("Pathing cancelled");
        if (client.isOnThread()) cancel.run(); else client.execute(cancel);
    }

    /** Client-thread only: stop the active run/tasks and release their movement keys. */
    private void releaseActiveRun(String reason) {
        NavigationRun run = activeRun;
        // Capture the task before completing the run: CompletableFuture callbacks execute
        // synchronously and clear activeTask.
        TalosTask task = activeTask;
        if (run != null) {
            run.cancelled = true;
            run.future.complete(new PathResult(false, reason));
        }
        if (task instanceof NavigateAndActTask oldTask) oldTask.cancel();
        if (task instanceof SimFollowTask simTask) simTask.cancel();
        // A pipelined follower may not be the captured activeTask; release its keys too.
        if (run != null && run.follower != null) run.follower.cancel();
        activeTask = null;
        activeRun = null;
    }

    @Override public boolean isPathing() { return activeRun != null && !activeRun.future.isDone(); }

    @Override public void setNodeCount(int count) {
        if (count < 0 || count > 4096) throw new IllegalArgumentException("node count must be 0..4096");
        nodeCount = count;
    }

    @Override public List<Vec3d> getCurrentNodes() { return currentNodes; }

    public static List<BlockPos> createNodes(List<BlockPos> path, int requestedCount) {
        if (path.size() <= 2) return List.copyOf(path);
        // Default (requestedCount <= 0): keep EVERY path cell — movement then follows the
        // route turn-for-turn (no straight-line "hold W" between sparse waypoints) and the
        // whole path is visualised. A positive count thins the route to that many waypoints.
        if (requestedCount <= 0 || requestedCount >= path.size()) return List.copyOf(path);
        int desired = Math.min(requestedCount, path.size());
        java.util.LinkedHashSet<BlockPos> selected = new java.util.LinkedHashSet<>();
        selected.add(path.getFirst());
        for (int i = 1; i + 1 < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i), c = path.get(i + 1);
            if (b.getX() - a.getX() != c.getX() - b.getX()
                    || b.getY() - a.getY() != c.getY() - b.getY()
                    || b.getZ() - a.getZ() != c.getZ() - b.getZ()) selected.add(b);
        }
        for (int i = 1; i < desired - 1; i++) {
            selected.add(path.get((int) Math.round(i * (path.size() - 1.0) / (desired - 1.0))));
        }
        selected.add(path.getLast());
        return path.stream().filter(selected::contains).toList();
    }

    private static void renderNodes(List<Vec3d> nodes) {
        final int waypointColor = 0x66CCFF; // Cyan; live SimFollowTask predictions are orange.
        for (int i = 0; i < nodes.size(); i++) {
            Vec3d node = nodes.get(i);
            RenderQueue.add("talos-path-node:" + i,
                    MotionState.box(MotionState.Pose.STAND, node), waypointColor, 20 * 30);
        }
    }

    private static int lastRenderedRouteNodes;

    /**
     * Checkpoint boxes mark MODE CHANGES: a box appears wherever the movement primitive
     * switches (walk -> sprint-jump, sprint -> swim, ...), colored by what to do there,
     * plus a sparse cadence on long same-mode stretches and always the goal. Steering
     * still consumes the full-resolution route.
     */
    private static void renderRouteNodes(PlannedRoute route) {
        int count = route.waypoints().size();
        int drawn = 0;
        dev.talos.client.pathing.sim.Primitive previous = null;
        for (int i = 0; i < count; i++) {
            PlannedRoute.Waypoint waypoint = route.waypoints().get(i);
            var via = waypoint.via();
            boolean transition = i == 0 || via != previous;
            previous = via;
            if (i != count - 1 && !transition && i % 6 != 0) continue;
            RenderQueue.add("talos-path-node:" + drawn++,
                    MotionState.box(waypoint.pose(), waypoint.position()),
                    modeColor(via), 20 * 30);
        }
        count = drawn;
        // Two overlapping plans on screen read as the pathfinder having "two ideas": always
        // drop the previous route's leftover boxes the moment a fresh plan is drawn.
        for (int i = count; i < lastRenderedRouteNodes; i++) {
            RenderQueue.remove("talos-path-node:" + i);
        }
        lastRenderedRouteNodes = count;
    }

    private static int lastRenderedCorridorMarkers;

    /**
     * Sparse dim markers sketch the global corridor; the blue/cyan route boxes remain the
     * only rendering of the executable plan, so the two read as "direction" vs "route".
     */
    private static void renderCorridorMarkers(List<BlockPos> corridor) {
        final int corridorColor = 0x445566; // dim slate, deliberately quieter than any route
        int drawn = 0;
        for (int i = 0; i < corridor.size(); i += 8) {
            BlockPos cell = corridor.get(i);
            RenderQueue.add("talos-corridor:" + drawn++,
                    MotionState.box(MotionState.Pose.STAND,
                            new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5)),
                    corridorColor, 20 * 30);
        }
        for (int i = drawn; i < lastRenderedCorridorMarkers; i++) {
            RenderQueue.remove("talos-corridor:" + i);
        }
        lastRenderedCorridorMarkers = drawn;
    }

    private static void clearCorridorMarkers() {
        for (int i = 0; i < lastRenderedCorridorMarkers; i++) {
            RenderQueue.remove("talos-corridor:" + i);
        }
        lastRenderedCorridorMarkers = 0;
    }

    /** Current route + continuation planned from its last node, as one longer route. */
    private static PlannedRoute stitch(PlannedRoute base, PlannedRoute extension) {
        java.util.List<PlannedRoute.Waypoint> combined =
                new java.util.ArrayList<>(base.waypoints());
        java.util.List<PlannedRoute.Waypoint> added = extension.waypoints();
        // The extension's first waypoint IS the base's last node (its search start).
        for (int i = added.isEmpty() ? 0 : 1; i < added.size(); i++) combined.add(added.get(i));
        return new PlannedRoute(combined, extension.reachedGoal(),
                "stitched: " + extension.detail());
    }

    /** One color per movement mode, so a checkpoint box says WHAT to do there at a glance. */
    private static int modeColor(dev.talos.client.pathing.sim.Primitive via) {
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

    private static GoalSnapshot snapshotGoal(Goal goal, MinecraftClient client) {
        return switch (goal) {
            case GoalBlock block -> {
                BlockPos target = new BlockPos(block.x(), block.y(), block.z());
                // A cell the player can never OCCUPY (solid ore/log, or head blocked) is an
                // interaction destination: arriving means standing beside it within touch
                // range — demanding the exact cell made goto(log) brake at the trunk forever.
                // Floorless AIR cells stay exact: they are pillar/build destinations
                // (goto ~ ~10 ~ must nerdpole straight up, not bridge to "nearby").
                if (isOccupiable(client, target)) yield new GoalSnapshot(target::equals, target);
                yield new GoalSnapshot(pos -> pos.getSquaredDistance(target) <= 4.0, target);
            }
            case GoalNear near -> {
                BlockPos target = new BlockPos(near.x(), near.y(), near.z());
                long radiusSquared = (long) near.radius() * near.radius();
                yield new GoalSnapshot(pos -> pos.getSquaredDistance(target) <= radiusSquared, target);
            }
            case GoalXZ xz -> {
                int targetY = client.player == null ? 0 : client.player.getBlockY();
                BlockPos target = new BlockPos(xz.x(), targetY, xz.z());
                yield new GoalSnapshot(pos -> pos.getX() == xz.x() && pos.getZ() == xz.z(), target);
            }
            case GoalEntity entityGoal -> {
                Entity entity = client.world == null ? null : client.world.getEntity(entityGoal.entityId());
                if (entity == null) throw new IllegalArgumentException(
                        "Entity is not loaded: " + entityGoal.entityId());
                BlockPos target = entity.getBlockPos().toImmutable();
                yield new GoalSnapshot(pos -> pos.getSquaredDistance(target) <= 1, target);
            }
        };
    }

    /** Feet and head cells passable — the player could occupy this cell (support or not). */
    private static boolean isOccupiable(MinecraftClient client, BlockPos pos) {
        var world = client.world;
        if (world == null) return true;
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty();
    }

    private record GoalSnapshot(Predicate<BlockPos> test, BlockPos target) { }

    private static final class OneShotTask extends TalosTask {
        private final Runnable action;
        private boolean done;

        OneShotTask(Runnable action) { this.action = action; }
        @Override public void initialize() { }
        @Override public boolean condition() { return !done; }
        @Override public void increment() { }
        @Override public void body() { done = true; action.run(); }
    }

    /** Owns persistent A* state and advances it a fixed dedicated slice per client tick. */
    /**
     * A deep search running FULL SPEED on a planner thread against an immutable
     * {@link SnapshotView}. The search advances in short chunks so keep-moving cuts,
     * abandonment, and run cancellation are observed with ~20ms latency; the finished
     * route is handed back to the client thread. The Search object itself is only ever
     * touched by the one planner thread — the client sees just the volatile flags.
     */
    private final class PlanningTask {
        private final NavigationRun run;
        private final SimPathfinder.Search search;
        /** Corridor index this search funnels toward; -1 when aimed at the real goal. */
        private final int subGoalIndex;
        private volatile boolean abandoned;

        PlanningTask(NavigationRun run, SimPathfinder.Search search, int subGoalIndex) {
            this.run = run;
            this.search = search;
            this.subGoalIndex = subGoalIndex;
        }

        /** Supersede this search: it stops expanding and its result is discarded. */
        void abandon() {
            abandoned = true;
        }

        void start() {
            PLANNER_POOL.execute(() -> {
                while (!search.isFinished()) {
                    if (abandoned || run.cancelled || run.future.isDone()) return;
                    search.advance(PLANNER_CHUNK_NANOS, () -> true);
                    if (!search.isFinished() && search.hasUsefulPartial()
                            && movementNeedsRouteNow()) {
                        search.cut("cut early to keep moving");
                    }
                }
                if (abandoned) return;
                PlannedRoute route = search.route();
                run.client.execute(() -> {
                    if (!abandoned) simPlanCompleted(run, this, route);
                });
            });
        }

        /**
         * True when waiting any longer would visibly stall the player. Reads follower
         * state cross-thread; both fields are single-word reads whose staleness only
         * shifts a cut by one 20ms chunk.
         */
        private boolean movementNeedsRouteNow() {
            SimFollowTask follower = run.follower;
            if (follower != null && follower.isActive()) {
                return follower.remainingWaypoints() <= FOLLOWER_STARVING_WAYPOINTS;
            }
            // No follower: the player is standing still RIGHT NOW waiting on this plan.
            return search.elapsedNanos() >= INITIAL_MOVE_NANOS;
        }
    }

    /** Advances the coarse corridor A* under the same 15ms slice discipline as deep plans. */
    private final class CoarseTask extends TalosTask {
        private final NavigationRun run;
        private final CoarsePathfinder.Search search;
        /** True when a deep-plan launch is parked behind this search and must be resumed. */
        boolean resumeDeepPlan;
        private boolean done;

        CoarseTask(NavigationRun run, CoarsePathfinder.Search search, boolean resumeDeepPlan) {
            this.run = run;
            this.search = search;
            this.resumeDeepPlan = resumeDeepPlan;
        }

        @Override public void initialize() { }
        @Override public boolean condition() {
            return !done && !run.cancelled && !run.future.isDone();
        }
        @Override public void increment() { }
        @Override public void body() {
            search.advance(COARSE_TICK_SLICE_NANOS);
            if (!search.isFinished()) return;
            done = true;
            CoarsePathfinder.Result result = search.result();
            TalosClient.taskScheduler().addTask("native-path-coarse-handoff", new OneShotTask(
                    () -> coarsePlanCompleted(run, this, result)));
        }
    }

    private static final class NavigationRun {
        final MinecraftClient client;
        final GoalSnapshot snapshot;
        final PathingOptions options;
        final CompletableFuture<PathResult> future;
        long attempts;
        int stalledAttempts;
        double bestDistance = Double.POSITIVE_INFINITY;
        /** Route whose last node the in-flight extension search planned from (stitch base). */
        PlannedRoute extensionBase;
        /** Coarse global corridor for far goals; null means deep searches aim at the goal. */
        List<BlockPos> corridor;
        /** Corridor cells proven wrong by a failed funneled search; recomputes avoid them. */
        final java.util.Set<BlockPos> corridorBlacklist = new java.util.HashSet<>();
        /** The coarse grid could not reach the goal at all: never funnel for this run. */
        boolean coarseFailed;
        // cancelled and follower are read by the background planner thread (loop exit and
        // keep-moving cuts); volatile makes the client thread's writes visible promptly.
        volatile boolean cancelled;
        TalosTask task;
        volatile SimFollowTask follower;
        PlanningTask planner;
        CoarseTask coarsePlanner;

        NavigationRun(MinecraftClient client, GoalSnapshot snapshot, PathingOptions options,
                      CompletableFuture<PathResult> future) {
            this.client = client;
            this.snapshot = snapshot;
            this.options = options;
            this.future = future;
        }
    }
}
