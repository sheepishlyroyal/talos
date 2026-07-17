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
    private record Entry(TalosNativeBridge owner, java.util.List<java.util.List<String>> suggestions) {}

    private static final Map<String, Entry> COMMANDS = new ConcurrentHashMap<>();

    private ScriptCommandRegistry() {}

    static void register(String name, TalosNativeBridge owner) {
        register(name, owner, java.util.List.of());
    }

    /** Registers with per-argument-position tab suggestions (outer list = position). */
    static void register(String name, TalosNativeBridge owner,
                         java.util.List<java.util.List<String>> suggestions) {
        COMMANDS.put(name, new Entry(owner, suggestions));
    }

    static void unregisterAll(TalosNativeBridge owner) {
        COMMANDS.values().removeIf(entry -> entry.owner == owner);
    }

    /** Whether a running script session currently claims {@code /talos <name>}. */
    public static boolean has(String name) {
        return COMMANDS.containsKey(name);
    }

    /** Immutable snapshot of every currently registered script-command name. */
    public static java.util.Set<String> names() {
        return java.util.Set.copyOf(COMMANDS.keySet());
    }

    /** Registration-time suggestions for argument position {@code index} (0-based); empty when none. */
    public static java.util.List<String> suggestionsFor(String name, int index) {
        Entry entry = COMMANDS.get(name);
        if (entry == null || index < 0 || index >= entry.suggestions.size()) return java.util.List.of();
        return entry.suggestions.get(index);
    }

    /** Queues one invocation onto the owning session; false when no live handler exists. */
    public static boolean dispatch(String name, String rawArgs) {
        Entry entry = COMMANDS.get(name);
        return entry != null && entry.owner.enqueueCommand(name, rawArgs);
    }
}
