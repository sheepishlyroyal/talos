package dev.glade.client.pathing;

/** Fabric entrypoint implemented by optional pathing adapters. */
@FunctionalInterface
public interface PathingEngineProvider {
    PathingEngine create();
}
