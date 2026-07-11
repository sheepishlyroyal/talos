package dev.glade.client.pathing;

import java.util.concurrent.CompletableFuture;

public final class NoOpPathingEngine implements PathingEngine {
    @Override public boolean isAvailable() { return false; }
    @Override public CompletableFuture<PathResult> goTo(Goal goal, PathingOptions options) {
        return CompletableFuture.failedFuture(new PathingUnavailableException("No pathing engine is available"));
    }
    @Override public void cancel() { throw new PathingUnavailableException("No pathing engine is available"); }
    @Override public boolean isPathing() { return false; }
}
