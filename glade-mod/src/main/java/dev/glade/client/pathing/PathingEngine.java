package dev.glade.client.pathing;

import java.util.concurrent.CompletableFuture;

public interface PathingEngine {
    boolean isAvailable();
    CompletableFuture<PathResult> goTo(Goal goal, PathingOptions options);
    void cancel();
    boolean isPathing();
}
