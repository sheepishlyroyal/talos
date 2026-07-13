package dev.talos.client.pathing.talos;

import dev.talos.client.pathing.PathingEngine;
import dev.talos.client.pathing.PathingEngineProvider;

/** Fabric entrypoint for Talos's always-available built-in pathfinder. */
public final class TalosPathingEngineProvider implements PathingEngineProvider {
    @Override
    public PathingEngine create() {
        return new TalosPathingEngine();
    }

    @Override
    public int priority() {
        return 100;
    }
}
