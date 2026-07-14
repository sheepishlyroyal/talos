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
import dev.talos.client.task.TalosTask;
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
    private static final int SIM_NODE_CAP = 160_000;
    private static final long SIM_SEARCH_NANOS = 600_000_000L;
    // Planning gets a dedicated per-tick slice rather than the shared 1-3ms action budget:
    // a pending plan means the player is (or will be) idle, which costs far more than 15ms
    // of one 50ms tick. Worst-case plan latency is therefore ~10 ticks (0.5s), usually 1-3.
    private static final long SIM_TICK_SLICE_NANOS = 15_000_000L;
    private static final int SIM_MAX_ROLLOUT_TICKS = 20;
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
        future.whenComplete((ignored, error) -> {
            if (activeRun == run) activeRun = null;
            if (activeTask == run.task) activeTask = null;
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
        SimPathfinder.Options simOptions = new SimPathfinder.Options(
                edits, edits, edits ? SIM_NODE_CAP * 2 : SIM_NODE_CAP,
                edits ? SIM_SEARCH_NANOS * 3 : SIM_SEARCH_NANOS, SIM_MAX_ROLLOUT_TICKS);
        SimPathfinder.Search search = SimPathfinder.begin(client.world, start, profile,
                run.snapshot.target(), run.snapshot.test(), simOptions);
        PlanningTask task = new PlanningTask(run, search);
        run.planner = task;
        boolean followerActive = run.follower != null && run.follower.isActive();
        if (!followerActive) {
            run.task = task;
            activeTask = task;
            if (client.player != null) client.player.sendMessage(
                    net.minecraft.text.Text.literal("§bTalos §7» §fplanning"), true);
        }
        try {
            TalosClient.taskScheduler().addTask("native-path-plan", task);
        } catch (RuntimeException exception) {
            run.planner = null;
            if (!followerActive) activeTask = null;
            run.future.completeExceptionally(exception);
        }
    }

    private void simPlanCompleted(NavigationRun run, PlanningTask planningTask,
            PlannedRoute route) {
        if (run.planner == planningTask) run.planner = null;
        if (activeTask == planningTask) activeTask = null;
        if (run.task == planningTask) run.task = null;
        if (run.cancelled || run.future.isDone()) return;
        if (run.client.player == null || run.client.world == null) {
            run.future.complete(new PathResult(false, "World unloaded while re-pathing"));
            return;
        }
        LOGGER.debug("Simulation path search attempt {}: {}", run.attempts, route.detail());
        // A partial plan is worth explaining out loud: WHY the planner stopped short (node
        // cap, time budget, frontier exhausted) is the difference between "needs a deeper
        // search" and "that goal is genuinely unreachable".
        if (!route.reachedGoal() && !route.detail().equals(lastPartialDetail)) {
            lastPartialDetail = route.detail();
            if (run.client.player != null) run.client.player.sendMessage(
                    net.minecraft.text.Text.literal(
                            "§6Talos §7» §fpartial plan: " + route.detail()), false);
        }
        if (route.waypoints().size() <= 1) {
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
        scheduleReplan(run);
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
            case PLACE -> 0xCC66FF;             // purple: place/bridge here
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
    private final class PlanningTask extends TalosTask {
        private final NavigationRun run;
        private final SimPathfinder.Search search;
        private boolean done;

        PlanningTask(NavigationRun run, SimPathfinder.Search search) {
            this.run = run;
            this.search = search;
        }

        @Override public void initialize() { }
        @Override public boolean condition() {
            return !done && !run.cancelled && !run.future.isDone();
        }
        @Override public void increment() { }
        @Override public void body() {
            // Deliberately NOT gated on the shared action tick budget: that 1-3ms allowance
            // throttled a 150ms search to many seconds of standing still. The fixed slice
            // itself is the freeze guard (15ms of a 50ms tick).
            search.advance(SIM_TICK_SLICE_NANOS, () -> true);
            if (search.isFinished()) {
                done = true;
                PlannedRoute route = search.route();
                TalosClient.taskScheduler().addTask("native-path-plan-handoff", new OneShotTask(
                        () -> simPlanCompleted(run, this, route)));
            } else {
                scheduleDelay();
            }
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
        boolean cancelled;
        TalosTask task;
        SimFollowTask follower;
        PlanningTask planner;

        NavigationRun(MinecraftClient client, GoalSnapshot snapshot, PathingOptions options,
                      CompletableFuture<PathResult> future) {
            this.client = client;
            this.snapshot = snapshot;
            this.options = options;
            this.future = future;
        }
    }
}
