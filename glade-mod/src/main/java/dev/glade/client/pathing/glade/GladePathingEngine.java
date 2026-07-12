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
    private volatile NavigateAndActTask activeTask;
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
        if (activeTask != null && !activeTask.condition()) activeTask = null;
        if (activeTask != null) {
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

        AStarPathfinder pathfinder = new AStarPathfinder(client.world, client.player);
        AStarPathfinder.SearchResult result = pathfinder.find(
                client.player.getBlockPos(), snapshot.test(), snapshot.target());
        LOGGER.debug("Native path search: {}", result.detail());
        if (result.path().isEmpty()) {
            future.complete(new PathResult(false, result.detail()));
            return;
        }

        int requestedNodes = options.nodeCount() > 0 ? options.nodeCount() : nodeCount;
        List<BlockPos> sampledNodes = createNodes(result.path(), requestedNodes);
        // Mining/bridging transitions are never sampled away: they are mandatory action nodes.
        List<BlockPos> nodes = result.path().stream()
                .filter(pos -> sampledNodes.contains(pos) || !pathfinder.isStandable(pos)).toList();
        currentNodes = nodes.stream().map(Vec3d::ofCenter).toList();
        renderNodes(currentNodes);
        NavigateAndActTask task = new NavigateAndActTask(client, nodes, snapshot.test(), null, future);
        activeTask = task;
        future.whenComplete((ignored, error) -> {
            if (activeTask == task) activeTask = null;
        });
        try {
            GladeClient.taskScheduler().addTask("native-path-follow", task);
        } catch (RuntimeException exception) {
            activeTask = null;
            future.completeExceptionally(exception);
        }
    }

    @Override
    public void cancel() {
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable cancel = () -> {
            NavigateAndActTask task = activeTask;
            if (task != null) task.cancel();
            activeTask = null;
        };
        if (client.isOnThread()) cancel.run(); else client.execute(cancel);
    }

    @Override public boolean isPathing() { return activeTask != null && activeTask.condition(); }

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
}
