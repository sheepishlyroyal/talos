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

    /**
     * Additive hook from the {@code ClientReceiveMessageEvents} registrations in
     * {@link dev.talos.client.rules.EventRuleEngine}: every visible chat line (player
     * chat and non-overlay system messages). {@code sender} is the speaking player's
     * name, or {@code null} for system/server lines. Note the server echoes the local
     * player's own messages back, so scripts reacting to chat should guard against
     * replying to themselves.
     */
    public static void onChatMessage(String message, String sender) {
        ScriptEngine engine = ScriptEngine.instance();
        if (engine.hasRunningSession()) engine.postEvent("chat", message, sender);
    }

    /**
     * Additive hook from {@link dev.talos.client.rules.EventRuleEngine#onEntityStatus}:
     * an entity within tracking range flashed a damage status. Payload stays primitive
     * (type id, network entity id, position) so scripts can correlate against
     * {@code talos.entities()} snapshots by {@code id}.
     */
    public static void onEntityHurt(String typeId, int entityId, double x, double y, double z) {
        ScriptEngine engine = ScriptEngine.instance();
        if (engine.hasRunningSession()) engine.postEvent("entity_hurt", typeId, entityId, x, y, z);
    }

    /*
     * Goto lifecycle hooks from the pathing engine. Same contract as the hooks above:
     * client-thread callers, primitive payloads only, and postEvent queues the actual
     * Python dispatch onto each session's worker thread.
     */

    /** A goto run was accepted and is about to plan toward (x, y, z). */
    public static void onGotoStart(int x, int y, int z) {
        ScriptEngine engine = ScriptEngine.instance();
        if (engine.hasRunningSession()) engine.postEvent("goto_start", x, y, z);
    }

    /** The goto run finished — success covers cancellation and failure alike (false). */
    public static void onGotoDone(boolean success, String detail) {
        ScriptEngine engine = ScriptEngine.instance();
        if (engine.hasRunningSession()) engine.postEvent("goto_done", success, detail);
    }

    /** A follower segment failed mid-run; the engine is about to replan, not give up. */
    public static void onGotoStuck(String detail) {
        ScriptEngine engine = ScriptEngine.instance();
        if (engine.hasRunningSession()) engine.postEvent("goto_stuck", detail);
    }
}
