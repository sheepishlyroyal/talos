package dev.talos.client.script;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wall-time profiler for Python event dispatch, toggled by {@code /talos script profile}.
 *
 * <p>The measured boundary is {@code Session.enqueueEvent} in {@link ScriptEngine}: every
 * named event a session posts (the tick pump, chat/health/goto events, disconnect — and,
 * transitively, script-command invocations, which the bridge drains on the tick path) runs
 * through it on the worker. {@link #withEvent} stamps the event name on the posting thread
 * so the synchronous enqueue can pick it up; the worker then times the whole Python
 * dispatch for that event. When profiling is off the only cost is one volatile read per
 * posted event.
 */
public final class ScriptProfiler {
    private static volatile boolean enabled;
    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_EVENT = new ThreadLocal<>();

    private ScriptProfiler() {}

    public static boolean enabled() { return enabled; }

    /** Flips profiling; stats reset on enable so each session of profiling starts clean. */
    public static boolean toggle() {
        enabled = !enabled;
        if (enabled) STATS.clear();
        return enabled;
    }

    /** Runs {@code post} with the event name visible to the synchronous enqueue hop. */
    static void withEvent(String event, Runnable post) {
        if (!enabled) { post.run(); return; }
        CURRENT_EVENT.set(event);
        try { post.run(); } finally { CURRENT_EVENT.remove(); }
    }

    /** The event name stamped by {@link #withEvent} on this thread, or null. */
    static String currentEventName() {
        return enabled ? CURRENT_EVENT.get() : null;
    }

    static void record(String event, long nanos) {
        STATS.computeIfAbsent(event, ignored -> new Stat()).add(nanos);
    }

    /** Report lines sorted by total time descending; empty when nothing was recorded. */
    public static List<String> report() {
        List<Map.Entry<String, Stat>> entries = new ArrayList<>(STATS.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Stat> e) ->
                e.getValue().totalNanos()).reversed());
        List<String> lines = new ArrayList<>(entries.size());
        for (Map.Entry<String, Stat> entry : entries) {
            Stat stat = entry.getValue();
            lines.add(String.format(java.util.Locale.ROOT,
                    "%s: %d calls, %.2f ms total, %.3f ms avg, %.3f ms max",
                    entry.getKey(), stat.calls(), stat.totalNanos() / 1e6,
                    stat.totalNanos() / 1e6 / Math.max(1, stat.calls()),
                    stat.maxNanos() / 1e6));
        }
        return lines;
    }

    private static final class Stat {
        private long calls;
        private long totalNanos;
        private long maxNanos;

        synchronized void add(long nanos) {
            calls++;
            totalNanos += nanos;
            if (nanos > maxNanos) maxNanos = nanos;
        }

        synchronized long calls() { return calls; }
        synchronized long totalNanos() { return totalNanos; }
        synchronized long maxNanos() { return maxNanos; }
    }
}
