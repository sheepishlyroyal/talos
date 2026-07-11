package dev.glade.pathing.baritone;

import dev.glade.client.pathing.PathingEngine;
import dev.glade.client.pathing.PathingEngineProvider;

public final class BaritoneEngineProvider implements PathingEngineProvider {
    @Override
    public PathingEngine create() {
        return new BaritonePathingEngine();
    }
}
