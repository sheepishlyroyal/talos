package dev.talos.client.pathing.talos;

import dev.talos.client.TalosClient;
import dev.talos.client.action.AimController;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.task.TalosTask;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Legitimate, keybinding-driven follower for a native A* path. */
public final class PathFollowTask extends TalosTask {
    private static final int TIMEOUT_TICKS = 60 * 20;
    private static final int STUCK_TICKS = 40;
    private static final double NODE_DISTANCE_SQUARED = 0.6 * 0.6;

    private final Minecraft client;
    private final CompletableFuture<PathResult> future;
    private final Predicate<BlockPos> isGoal;
    private final Function<BlockPos, AStarPathfinder.SearchResult> recompute;
    private final AimController aim;
    private List<BlockPos> path;
    private boolean pathReachesGoal;
    private String pathDetail;
    private int index;
    private int ticks;
    private int stillTicks;
    private boolean recomputed;
    private Vec3 lastPosition;

    public PathFollowTask(Minecraft client, List<BlockPos> path, boolean pathReachesGoal,
                          String pathDetail, Predicate<BlockPos> isGoal,
                          Function<BlockPos, AStarPathfinder.SearchResult> recompute,
                          CompletableFuture<PathResult> future) {
        this.client = client;
        this.path = path;
        this.pathReachesGoal = pathReachesGoal;
        this.pathDetail = pathDetail;
        this.isGoal = isGoal;
        this.recompute = recompute;
        this.future = future;
        this.aim = new AimController(client, TalosClient.humanizer().rotation(),
                TalosClient.humanizer().defaultProfile(), System.nanoTime());
        this.index = path.size() > 1 ? 1 : path.size();
    }

    @Override
    public void initialize() {
        if (client.player != null) lastPosition = positionOf(client.player);
    }

    @Override public boolean condition() { return !future.isDone(); }
    @Override public void increment() { ticks++; }

    @Override
    public void body() {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            finish(false, "World unloaded while following path");
            return;
        }
        if (isGoal.test(player.blockPosition())) {
            finish(true, "Arrived");
            return;
        }
        if (ticks >= TIMEOUT_TICKS) {
            finish(false, "Path following timed out after 60 seconds");
            return;
        }

        updateStuckState(player);
        if (stillTicks >= STUCK_TICKS) {
            if (!recomputed) {
                AStarPathfinder.SearchResult replacement = recompute.apply(player.blockPosition());
                recomputed = true;
                stillTicks = 0;
                if (!replacement.path().isEmpty()) {
                    path = replacement.path();
                    pathReachesGoal = replacement.reachesGoal();
                    pathDetail = replacement.detail();
                    index = path.size() > 1 ? 1 : path.size();
                } else {
                    finish(false, "Stuck; recompute failed: " + replacement.detail());
                    return;
                }
            } else {
                finish(false, "Stuck after path recompute");
                return;
            }
        }

        advanceReachedNodes(player);
        if (index >= path.size()) {
            finish(false, pathReachesGoal ? "Path ended before goal was reached" : pathDetail);
            return;
        }

        BlockPos node = path.get(index);
        aim.aimAt(new Vec3(node.getX() + 0.5, player.getEyeY(), node.getZ() + 0.5));
        aim.tick();
        pressMovement(player, node);
        scheduleDelay();
    }

    private void updateStuckState(LocalPlayer player) {
        Vec3 position = positionOf(player);
        if (lastPosition != null && position.distanceToSqr(lastPosition) < 0.01 * 0.01) {
            stillTicks++;
        } else {
            stillTicks = 0;
            lastPosition = position;
        }
    }

    private void advanceReachedNodes(LocalPlayer player) {
        while (index < path.size()) {
            BlockPos node = path.get(index);
            double dx = player.getX() - (node.getX() + 0.5);
            double dz = player.getZ() - (node.getZ() + 0.5);
            if (dx * dx + dz * dz >= NODE_DISTANCE_SQUARED
                    || Math.abs(player.getY() - node.getY()) > 0.75) {
                return;
            }
            index++;
        }
    }

    private void pressMovement(LocalPlayer player, BlockPos node) {
        releaseInputs();
        client.options.keyUp.setDown(true);
        int rise = node.getY() - player.getBlockY();
        if (rise > 0 && player.onGround()) client.options.keyJump.setDown(true);
        if (isLongFlatStraight()) client.options.keySprint.setDown(true);
    }

    private boolean isLongFlatStraight() {
        if (index + 2 >= path.size()) return false;
        BlockPos a = path.get(index);
        BlockPos b = path.get(index + 1);
        BlockPos c = path.get(index + 2);
        return a.getY() == b.getY() && b.getY() == c.getY()
                && b.getX() - a.getX() == c.getX() - b.getX()
                && b.getZ() - a.getZ() == c.getZ() - b.getZ();
    }

    private static Vec3 positionOf(LocalPlayer player) {
        return new Vec3(player.getX(), player.getY(), player.getZ());
    }

    public void cancel() {
        finish(false, "Pathing cancelled");
        _break();
    }

    private void finish(boolean success, String detail) {
        releaseInputs();
        future.complete(new PathResult(success, detail));
    }

    private void releaseInputs() {
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyShift.setDown(false);
    }

    @Override
    public void onCompleted() {
        releaseInputs();
        if (!future.isDone()) future.complete(new PathResult(false, "Pathing was interrupted"));
    }

    @Override public Set<Object> getMutexKeys() { return Set.of("talos-player-movement"); }

    // TODO v1: ledge sneaking and upward swimming; doors are intentionally not opened.
}
