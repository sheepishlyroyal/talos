package dev.glade.client.pathing.glade;

import dev.glade.client.GladeClient;
import dev.glade.client.pathing.Goal;
import dev.glade.client.pathing.GoalBlock;
import dev.glade.client.pathing.GoalEntity;
import dev.glade.client.pathing.GoalNear;
import dev.glade.client.pathing.GoalXZ;
import dev.glade.client.pathing.PathResult;
import dev.glade.client.pathing.PathingEngine;
import dev.glade.client.pathing.PathingOptions;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import dev.glade.client.render.RenderQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Always-available built-in client-side pathing engine. */
public final class GladePathingEngine implements PathingEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(GladePathingEngine.class);
    private static final int MAX_REPATH_ATTEMPTS = 20;
    private static final int MAX_STALLED_ATTEMPTS = 3;
    private static final long MAX_ROUTE_NANOS = 5L * 60L * 1_000_000_000L;
    private volatile NavigateAndActTask activeTask;
    private volatile NavigationRun activeRun;
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
        if (activeRun != null) {
            future.complete(new PathResult(false, "A native path is already active"));
            return;
        }
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
        if (++run.attempts > MAX_REPATH_ATTEMPTS
                || System.nanoTime() - run.startedNanos > MAX_ROUTE_NANOS) {
            run.future.complete(new PathResult(false, "Pathing stopped after the incremental re-path limit"));
            return;
        }

        AStarPathfinder pathfinder = new AStarPathfinder(client.world, client.player);
        AStarPathfinder.SearchResult result = pathfinder.find(
                client.player.getBlockPos(), run.snapshot.test(), run.snapshot.target());
        LOGGER.debug("Native path search attempt {}: {}", run.attempts, result.detail());
        if (result.path().isEmpty()) {
            run.future.complete(new PathResult(false, result.detail()));
            return;
        }

        double distance = squaredDistance(client.player.getBlockPos(), run.snapshot.target());
        if (distance + 0.25 < run.bestDistance) {
            run.bestDistance = distance;
            run.stalledAttempts = 0;
        } else if (run.attempts > 1) {
            run.stalledAttempts++;
        }
        if (run.stalledAttempts >= MAX_STALLED_ATTEMPTS) {
            run.future.complete(new PathResult(false,
                    "Pathing is stuck: no progress across " + run.stalledAttempts + " re-path attempts"));
            return;
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
        currentNodes = nodes.stream().map(Vec3d::ofCenter).toList();
        renderNodes(currentNodes);
        CompletableFuture<PathResult> segmentFuture = new CompletableFuture<>();
        NavigateAndActTask task = new NavigateAndActTask(client, nodes, run.snapshot.test(), null, segmentFuture);
        run.task = task;
        activeTask = task;
        segmentFuture.whenComplete((segment, error) -> {
            Runnable continuation = () -> segmentCompleted(run, task, result, segment, error);
            if (client.isOnThread()) continuation.run(); else client.execute(continuation);
        });
        try {
            GladeClient.taskScheduler().addTask("native-path-follow", task);
        } catch (RuntimeException exception) {
            activeTask = null;
            run.future.completeExceptionally(exception);
        }
    }

    private void segmentCompleted(NavigationRun run, NavigateAndActTask task,
                                  AStarPathfinder.SearchResult search, PathResult segment,
                                  Throwable error) {
        if (activeTask == task) activeTask = null;
        if (run.task == task) run.task = null;
        if (run.cancelled || run.future.isDone()) return;
        if (error != null) { run.future.completeExceptionally(error); return; }
        if (segment.successful()) { run.future.complete(segment); return; }
        if (!search.reachesGoal() && segment.detail().startsWith("Nodes ended")) {
            // The player moved to the frontier; chunks may now be available, so search again next tick.
            launchSegment(run);
            return;
        }
        run.future.complete(segment);
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public void cancel() {
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable cancel = () -> {
            NavigationRun run = activeRun;
            if (run != null) {
                run.cancelled = true;
                run.future.complete(new PathResult(false, "Pathing cancelled"));
            }
            NavigateAndActTask task = activeTask;
            if (task != null) task.cancel();
            activeTask = null;
            activeRun = null;
        };
        if (client.isOnThread()) cancel.run(); else client.execute(cancel);
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
        for (int i = 0; i < nodes.size(); i++) {
            Vec3d node = nodes.get(i);
            RenderQueue.add("glade-path-node:" + i,
                    new Box(node.x - 0.16, node.y - 0.16, node.z - 0.16,
                            node.x + 0.16, node.y + 0.16, node.z + 0.16), 0x66CCFF, 20 * 30);
        }
    }

    private static GoalSnapshot snapshotGoal(Goal goal, MinecraftClient client) {
        return switch (goal) {
            case GoalBlock block -> {
                BlockPos target = new BlockPos(block.x(), block.y(), block.z());
                yield new GoalSnapshot(target::equals, target);
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

    private record GoalSnapshot(Predicate<BlockPos> test, BlockPos target) { }

    private static final class NavigationRun {
        final MinecraftClient client;
        final GoalSnapshot snapshot;
        final PathingOptions options;
        final CompletableFuture<PathResult> future;
        final long startedNanos = System.nanoTime();
        int attempts;
        int stalledAttempts;
        double bestDistance = Double.POSITIVE_INFINITY;
        boolean cancelled;
        NavigateAndActTask task;

        NavigationRun(MinecraftClient client, GoalSnapshot snapshot, PathingOptions options,
                      CompletableFuture<PathResult> future) {
            this.client = client;
            this.snapshot = snapshot;
            this.options = options;
            this.future = future;
        }
    }
}
