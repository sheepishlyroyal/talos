package dev.talos.pathing.baritone;

import dev.talos.client.pathing.PathingEngine;
import dev.talos.client.pathing.PathingEngineProvider;

public final class BaritoneEngineProvider implements PathingEngineProvider {
    @Override
    public PathingEngine create() {
        return new BaritonePathingEngine();
    }
}
