package dev.glade.client.pathing;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects the first available optional pathing adapter. */
public final class PathingEngineRegistry {
    public static final String ENTRYPOINT_KEY = "glade:pathing_engine";
    private static final Logger LOGGER = LoggerFactory.getLogger(PathingEngineRegistry.class);

    private PathingEngineRegistry() {
    }

    public static PathingEngine discover() {
        for (var container : FabricLoader.getInstance()
                .getEntrypointContainers(ENTRYPOINT_KEY, PathingEngineProvider.class)) {
            try {
                PathingEngine engine = container.getEntrypoint().create();
                if (engine.isAvailable()) {
                    LOGGER.info("Using pathing engine from {}", container.getProvider().getMetadata().getId());
                    return engine;
                }
            } catch (Throwable error) {
                LOGGER.warn("Could not initialize pathing engine from {}",
                        container.getProvider().getMetadata().getId(), error);
            }
        }
        return new NoOpPathingEngine();
    }
}
