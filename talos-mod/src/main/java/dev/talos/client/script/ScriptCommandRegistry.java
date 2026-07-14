package dev.talos.client.script;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global name → session lookup for Python-registered {@code /talos} command handlers.
 *
 * <p>Registration happens on a script worker thread (via
 * {@link TalosNativeBridge#registerCommand}); dispatch happens on the client tick thread
 * (via {@code dev.talos.client.command.TalosCommands}). Dispatch never calls into Python:
 * it only queues the raw argument string on the owning bridge, and the Python engine
 * drains that queue on its own worker through the regular tick-event path — the GraalPy
 * Context stays single-threaded. Entries are removed when the session is invalidated
 * ({@link TalosNativeBridge#invalidate()}) and when a script re-runs, so a stale handler
 * can never fire into a closed Context.
 */
public final class ScriptCommandRegistry {
    private static final Map<String, TalosNativeBridge> COMMANDS = new ConcurrentHashMap<>();

    private ScriptCommandRegistry() {}

    static void register(String name, TalosNativeBridge owner) {
        COMMANDS.put(name, owner);
    }

    static void unregisterAll(TalosNativeBridge owner) {
        COMMANDS.values().removeIf(bridge -> bridge == owner);
    }

    /** Whether a running script session currently claims {@code /talos <name>}. */
    public static boolean has(String name) {
        return COMMANDS.containsKey(name);
    }

    /** Queues one invocation onto the owning session; false when no live handler exists. */
    public static boolean dispatch(String name, String rawArgs) {
        TalosNativeBridge owner = COMMANDS.get(name);
        return owner != null && owner.enqueueCommand(name, rawArgs);
    }
}
