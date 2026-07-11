package dev.glade.client.script;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

/** Worker-owned Python event handler registry. */
public final class EventDispatcher {
    private static final Set<String> EVENTS = Set.of("tick", "chat", "entity_hurt", "disconnect");
    private final Map<String, List<Value>> handlers = new ConcurrentHashMap<>();
    private final Consumer<Runnable> workerEnqueue;
    private final AtomicBoolean tickPending = new AtomicBoolean();

    EventDispatcher(Consumer<Runnable> workerEnqueue) { this.workerEnqueue = workerEnqueue; }

    @HostAccess.Export
    public void register(String event, Value handler) {
        if (!EVENTS.contains(event)) throw new IllegalArgumentException("Unsupported event: " + event);
        if (handler == null || !handler.canExecute()) throw new IllegalArgumentException("Handler must be callable");
        handlers.computeIfAbsent(event, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void post(String event, Object... args) {
        if ("tick".equals(event) && !tickPending.compareAndSet(false, true)) return;
        workerEnqueue.accept(() -> {
            if ("tick".equals(event)) tickPending.set(false);
            for (Value handler : List.copyOf(handlers.getOrDefault(event, List.of()))) {
                handler.execute(args);
            }
        });
    }

    void clear() { handlers.clear(); tickPending.set(false); }
}
