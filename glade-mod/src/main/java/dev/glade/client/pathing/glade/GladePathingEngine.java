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
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Always-available built-in client-side pathing engine. */
public final class GladePathingEngine implements PathingEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(GladePathingEngine.class);
    private volatile PathFollowTask activeTask;

    @Override public boolean isAvailable() { return true; }

    @Override
    public CompletableFuture<PathResult> goTo(Goal goal, PathingOptions options) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(options, "options");
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable start = () -> startOnClientThread(client, goal, future);
        if (client.isOnThread()) start.run(); else client.execute(start);
        return future;
    }

    private void startOnClientThread(MinecraftClient client, Goal goal,
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

        AStarPathfinder pathfinder = new AStarPathfinder(client.world);
        AStarPathfinder.SearchResult result = pathfinder.find(
                client.player.getBlockPos(), snapshot.test(), snapshot.target());
        LOGGER.debug("Native path search: {}", result.detail());
        if (result.path().isEmpty()) {
            future.complete(new PathResult(false, result.detail()));
            return;
        }

        PathFollowTask task = new PathFollowTask(client, result.path(), result.reachesGoal(),
                result.detail(), snapshot.test(), start -> pathfinder.find(
                        start, snapshot.test(), snapshot.target()), future);
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
            PathFollowTask task = activeTask;
            if (task != null) task.cancel();
            activeTask = null;
        };
        if (client.isOnThread()) cancel.run(); else client.execute(cancel);
    }

    @Override public boolean isPathing() { return activeTask != null && activeTask.condition(); }

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
