package dev.talos.client.pathing;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import net.minecraft.util.math.Vec3d;

public interface PathingEngine {
    boolean isAvailable();
    CompletableFuture<PathResult> goTo(Goal goal, PathingOptions options);
    void cancel();
    boolean isPathing();
    /** Zero restores automatic node density. */
    default void setNodeCount(int count) { }
    default List<Vec3d> getCurrentNodes() { return List.of(); }
}
