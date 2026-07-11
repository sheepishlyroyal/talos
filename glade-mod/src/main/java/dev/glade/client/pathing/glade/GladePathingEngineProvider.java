package dev.glade.client.pathing.glade;

import dev.glade.client.pathing.PathingEngine;
import dev.glade.client.pathing.PathingEngineProvider;

/** Fabric entrypoint for Glade's always-available built-in pathfinder. */
public final class GladePathingEngineProvider implements PathingEngineProvider {
    @Override
    public PathingEngine create() {
        return new GladePathingEngine();
    }

    @Override
    public int priority() {
        return 100;
    }
}
