package dev.glade.client;

import dev.glade.client.command.GladeCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GladeClientMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Glade");

    @Override
    public void onInitializeClient() {
        GladeCommands.register();
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            GladeClient.tickBudget().beginTick();
            GladeClient.taskScheduler().tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                GladeClient.taskScheduler().onLevelUnload());
        LOGGER.info("Glade initialized");
    }
}
