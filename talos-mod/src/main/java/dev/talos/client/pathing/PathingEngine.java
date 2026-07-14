package dev.talos.client.pathing;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.phys.Vec3;
import java.util.List;

public interface PathingEngine {
    boolean isAvailable();
    CompletableFuture<PathResult> goTo(Goal goal, PathingOptions options);
    void cancel();
    boolean isPathing();
    /** Zero restores automatic node density. */
    default void setNodeCount(int count) { }
    /**
     * Moves the ACTIVE run's goal without restarting it (moving-target follows). The
     * follower keeps its momentum; planning continues toward the fresh goal. Returns
     * false when there is no active run or the engine cannot retarget in place —
     * callers then fall back to issuing a new goto.
     */
    default boolean retarget(Goal goal) { return false; }
    default List<Vec3> getCurrentNodes() { return List.of(); }
}
