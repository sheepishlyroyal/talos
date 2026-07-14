package dev.talos.client.script;

import net.minecraft.client.MinecraftClient;

/**
 * Client-thread watcher that turns local-player game state into script events
 * ({@code "health"}, {@code "death"}, {@code "item_pickup"}). This class never runs
 * Python: it only calls {@link ScriptEngine#postEvent}, which queues the dispatch onto
 * each session's own worker thread — the same path the existing {@code "tick"} and
 * {@code "disconnect"} events take.
 */
public final class ScriptGameEvents {
    private static float previousHealth = Float.NaN;

    private ScriptGameEvents() {}

    /** Called once per client tick (from the same hook that fires the script tick event). */
    public static void tick(MinecraftClient client) {
        if (client.player == null) {
            // World left / respawn screen gone: forget the baseline so the next join
            // doesn't fire a spurious health change.
            previousHealth = Float.NaN;
            return;
        }
        float health = client.player.getHealth();
        ScriptEngine engine = ScriptEngine.instance();
        if (!engine.hasRunningSession() || Float.isNaN(previousHealth)) {
            // No listeners (or no baseline yet): track silently, never fire.
            previousHealth = health;
            return;
        }
        if (health != previousHealth) {
            engine.postEvent("health", health);
            if (health <= 0.0f && previousHealth > 0.0f) engine.postEvent("death");
            previousHealth = health;
        }
    }

    /**
     * Additive hook from {@link dev.talos.client.rules.EventRuleEngine#onItemPickup}:
     * called on the client thread, only when the collector is the local player.
     * Payload stays primitive (item id string + stack count) so nothing host-object
     * shaped leaks into GraalPy.
     */
    public static void onLocalItemPickup(String itemId, int amount) {
        ScriptEngine engine = ScriptEngine.instance();
        if (engine.hasRunningSession()) engine.postEvent("item_pickup", itemId, amount);
    }
}
