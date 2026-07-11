package dev.glade.pathing.baritone;

import dev.glade.client.pathing.*;
import java.util.concurrent.CompletableFuture;

/** Phase 0 adapter shell. Baritone is intentionally not bundled (LGPL boundary). */
public final class BaritonePathingEngine implements PathingEngine {
    @Override public boolean isAvailable() { return false; /* TODO: detect Baritone reflectively. */ }
    @Override public CompletableFuture<PathResult> goTo(Goal goal, PathingOptions options) {
        return CompletableFuture.failedFuture(new PathingUnavailableException("Baritone adapter is not implemented in Phase 0"));
    }
    @Override public void cancel() { throw new PathingUnavailableException("Baritone adapter is not implemented in Phase 0"); }
    @Override public boolean isPathing() { return false; }
}
