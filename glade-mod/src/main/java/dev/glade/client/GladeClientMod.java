package dev.glade.client;

import dev.glade.client.command.GladeCommands;
import dev.glade.client.bridge.GladeBridge;
import dev.glade.client.render.RenderQueue;
import dev.glade.client.script.GameThreadExecutor;
import dev.glade.client.script.ScriptEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GladeClientMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Glade");

    @Override
    public void onInitializeClient() {
        GladeCommands.register();
        GladeBridge.start();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> GladeBridge.stop());
        RenderQueue.register();
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            GameThreadExecutor.instance().drain(client);
            ScriptEngine.instance().tick();
            GladeClient.tickBudget().beginTick();
            GladeClient.taskScheduler().tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ScriptEngine.instance().onDisconnect();
            GladeClient.taskScheduler().onLevelUnload();
            RenderQueue.clear();
        });
        LOGGER.info("Glade initialized");
    }
}
