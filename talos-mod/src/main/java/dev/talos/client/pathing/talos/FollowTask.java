package dev.talos.client.pathing.talos;

import dev.talos.client.TalosClient;
import dev.talos.client.pathing.GoalNear;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.pathing.PathingOptions;
import dev.talos.client.task.TalosTask;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

/**
 * Moving-goal navigation: continuously re-paths toward a (possibly moving) entity,
 * keeping {@code keepDistance} blocks. Runs as a scheduler task on the client thread and
 * drives the ordinary pathing engine by issuing/superseding gotos, so it inherits every
 * planner feature (corridors, watchdog, extensions) and every stop path for free:
 * {@code /talos stop} breaks the task, and a goto issued by anyone else supersedes ours,
 * which we treat as "the user changed their mind" and end the follow.
 *
 * <p>The future completes only when following ENDS (stopped, superseded, or the target
 * is gone) — a successfully followed target keeps the task alive indefinitely.</p>
 */
public final class FollowTask extends TalosTask {
    /** Ticks a vanished target may stay unloaded before the follow gives up (15s). */
    private static final int TARGET_LOST_GRACE_TICKS = 300;
    /** Re-issue the goto when the target strays this far (squared) from the last goal. */
    private static final double REISSUE_MOVE_SQ = 3.0 * 3.0;
    /** Minimum ticks between issued gotos, so replans don't thrash the planner. */
    private static final int ISSUE_COOLDOWN_TICKS = 10;
    /** Extra ticks of idling after a FAILED goto before retrying a stationary target. */
    private static final int FAILURE_BACKOFF_TICKS = 40;

    private final MinecraftClient client;
    private final int entityId;
    private final double keepDistance;
    private final String description;
    private final CompletableFuture<PathResult> outcome = new CompletableFuture<>();
    private CompletableFuture<PathResult> activeGoto;
    private BlockPos lastGoal;
    private int lostTicks;
    private int cooldownTicks;

    private FollowTask(MinecraftClient client, int entityId, double keepDistance, String description) {
        this.client = client;
        this.entityId = entityId;
        this.keepDistance = keepDistance;
        this.description = description;
    }

    /** Starts following {@code target}; call on the client thread. */
    public static CompletableFuture<PathResult> start(MinecraftClient client, Entity target,
                                                      double keepDistance) {
        if (target == client.player)
            return CompletableFuture.completedFuture(new PathResult(false, "Cannot follow yourself"));
        FollowTask task = new FollowTask(client, target.getId(),
                Math.max(1.0, keepDistance), target.getName().getString());
        TalosClient.taskScheduler().forceAddTask("talos-follow", task);
        return task.outcome;
    }

    public CompletableFuture<PathResult> future() { return outcome; }

    @Override public void initialize() {}
    @Override public boolean condition() { return !outcome.isDone(); }
    @Override public void increment() {}

    @Override
    public void body() {
        scheduleDelay();
        if (client.world == null || client.player == null) {
            finish(false, "World unloaded while following");
            return;
        }
        Entity target = client.world.getEntityById(entityId);
        if (target == null || target.isRemoved()) {
            // Unloaded ≠ gone: chunk-edge flicker and dimension lag both look like this,
            // so hold position for a grace window before declaring the target lost.
            if (++lostTicks > TARGET_LOST_GRACE_TICKS)
                finish(false, "Lost " + description + " (unloaded for 15s)");
            return;
        }
        lostTicks = 0;
        if (cooldownTicks > 0) cooldownTicks--;

        double distanceSq = client.player.squaredDistanceTo(target);
        double closeEnough = keepDistance + 0.75;
        BlockPos targetPos = target.getBlockPos().toImmutable();
        boolean gotoLive = activeGoto != null && !activeGoto.isDone();

        // LIVE TRACKING: while our goto runs, feed it the target's fresh cell every tick
        // it moves. retarget() swaps the goal in place — momentum survives, the follower's
        // arrival test updates, and planning continues toward where the mob IS, not where
        // it stood when the goto was issued (the old snapshot-chasing looked exactly like
        // pretending the mob was standing still).
        if (gotoLive) {
            if (targetPos.equals(lastGoal)) return;
            if (TalosClient.pathingEngine().retarget(
                    new GoalNear(targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                            (int) Math.floor(keepDistance)))) {
                lastGoal = targetPos;
                return;
            }
            // Engine can't retarget in place (no-op engine, or the run just ended): only
            // a real stray re-issues, exactly like the pre-retarget behavior.
            if (lastGoal != null && targetPos.getSquaredDistance(lastGoal) < REISSUE_MOVE_SQ) {
                return;
            }
        }

        if (distanceSq <= closeEnough * closeEnough) return; // near enough: idle
        boolean targetMoved = lastGoal == null
                || targetPos.getSquaredDistance(lastGoal) >= REISSUE_MOVE_SQ;
        if (cooldownTicks > 0) return;
        if (!targetMoved && distanceSq <= (keepDistance + 2) * (keepDistance + 2)) return;

        lastGoal = targetPos;
        cooldownTicks = ISSUE_COOLDOWN_TICKS;
        CompletableFuture<PathResult> issued = TalosClient.pathingEngine().goTo(
                new GoalNear(targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                        (int) Math.floor(keepDistance)),
                PathingOptions.DEFAULT);
        activeGoto = issued;
        issued.whenComplete((result, error) -> client.execute(() -> {
            // Stale completions (a goto we already superseded ourselves) are not ours to
            // interpret — only the CURRENT goto's fate steers the follow.
            if (outcome.isDone() || activeGoto != issued) return;
            if (error != null) { finish(false, "Follow failed: " + error.getMessage()); return; }
            if (result.successful()) return; // arrived; keep watching the target
            String detail = String.valueOf(result.detail());
            if (detail.contains("Superseded")) {
                finish(false, "Follow ended: another goto took over");
                return;
            }
            // Unreachable right now — back off, then retry (the target may move somewhere
            // reachable). Backoff clears instantly if the target strays REISSUE_MOVE_SQ.
            cooldownTicks = Math.max(cooldownTicks, FAILURE_BACKOFF_TICKS);
        }));
    }

    @Override
    public void onCompleted() {
        // Scheduler-driven exits (level unload, /talos stop's cancelAll) land here without
        // finish() having run; make sure callers awaiting the future always hear the end.
        if (!outcome.isDone()) outcome.complete(new PathResult(false, "Follow stopped"));
    }

    private void finish(boolean success, String detail) {
        _break();
        if (activeGoto != null && !activeGoto.isDone()) TalosClient.pathingEngine().cancel();
        outcome.complete(new PathResult(success, detail));
    }
}
