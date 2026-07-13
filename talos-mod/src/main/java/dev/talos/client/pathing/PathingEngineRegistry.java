package dev.talos.client.pathing;

import net.fabricmc.loader.api.FabricLoader;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects the highest-priority available pathing adapter. */
public final class PathingEngineRegistry {
    public static final String ENTRYPOINT_KEY = "talos:pathing_engine";
    private static final Logger LOGGER = LoggerFactory.getLogger(PathingEngineRegistry.class);

    private PathingEngineRegistry() {
    }

    public static PathingEngine discover() {
        var containers = FabricLoader.getInstance()
                .getEntrypointContainers(ENTRYPOINT_KEY, PathingEngineProvider.class).stream()
                .sorted(Comparator.comparingInt(
                        (net.fabricmc.loader.api.entrypoint.EntrypointContainer<PathingEngineProvider> entry) ->
                                effectivePriority(entry)).reversed())
                .toList();
        for (var container : containers) {
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

    private static int effectivePriority(
            net.fabricmc.loader.api.entrypoint.EntrypointContainer<PathingEngineProvider> entry) {
        // The separately distributed Baritone adapter is the preferred optional upgrade.
        if ("talos-pathing-baritone".equals(entry.getProvider().getMetadata().getId())) {
            return Math.max(200, entry.getEntrypoint().priority());
        }
        return entry.getEntrypoint().priority();
    }
}
