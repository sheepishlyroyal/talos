package dev.talos.client;

import dev.talos.client.command.TalosCommands;
import dev.talos.client.bridge.TalosBridge;
import dev.talos.client.config.TalosConfig;
import dev.talos.client.config.TalosConfigManager;
import dev.talos.client.log.TalosLog;
import dev.talos.client.render.RenderQueue;
import dev.talos.client.script.GameThreadExecutor;
import dev.talos.client.script.ScriptEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TalosClientMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Talos");

    @Override
    public void onInitializeClient() {
        TalosLog.init();
        // Load persisted settings before anything else touches Theme/Humanizer, so
        // subsystems start in the user's last-saved state.
        TalosConfig config = TalosConfigManager.load();
        TalosConfigManager.setThemeMode(config.themeMode);
        TalosConfigManager.setActiveProfile(config.activeProfile);
        // follow-up: wire live UI toggles (theme switcher, profile picker) to call
        // TalosConfigManager.setThemeMode/setActiveProfile so in-app changes persist.

        TalosCommands.register();
        TalosBridge.start();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TalosBridge.stop());
        RenderQueue.register();
        dev.talos.client.hud.TalosHud.register();
        dev.talos.client.rules.EventRuleEngine.register();
        dev.talos.client.macro.MacroSystem.register();
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            GameThreadExecutor.instance().drain(client);
            ScriptEngine.instance().tick();
            dev.talos.client.script.ScriptGameEvents.tick(client);
            // Session-arc ages only in-world and counts pathing/actions as "working".
            if (client.player != null) {
                TalosClient.humanizer().sessionArc().tick(TalosClient.pathingEngine().isPathing());
            }
            TalosClient.tickBudget().beginTick();
            TalosClient.taskScheduler().tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ScriptEngine.instance().onDisconnect();
            TalosClient.taskScheduler().onLevelUnload();
            RenderQueue.clear();
        });
        LOGGER.info("Talos initialized");
    }
}
