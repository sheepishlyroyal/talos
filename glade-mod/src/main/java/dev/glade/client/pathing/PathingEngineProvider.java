package dev.glade.client.pathing;

/** Fabric entrypoint implemented by optional pathing adapters. */
public interface PathingEngineProvider {
    PathingEngine create();

    /** Higher-priority available engines are selected first. */
    default int priority() {
        return 0;
    }
}
