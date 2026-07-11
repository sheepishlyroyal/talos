package dev.glade.client.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Cooperative, insertion-ordered task scheduler driven once per client tick. */
public final class TaskScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskScheduler.class);
    private static final int MAX_PASSES_PER_TICK = 1_000;

    private final LinkedHashMap<String, GladeTask> tasks = new LinkedHashMap<>();
    private long nextTaskId = 1;

    public void tick() {
        if (tasks.isEmpty()) {
            return;
        }

        var runnable = new ArrayList<>(tasks.entrySet());
        int passes = 0;
        while (!runnable.isEmpty()) {
            if (++passes > MAX_PASSES_PER_TICK) {
                LOGGER.warn("Task scheduler stopped after {} passes in one tick. Tasks: {}",
                        MAX_PASSES_PER_TICK, tasks.keySet());
                break;
            }

            Iterator<Map.Entry<String, GladeTask>> iterator = runnable.iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, GladeTask> entry = iterator.next();
                GladeTask task = entry.getValue();
                task.initializeIfNeeded();

                if (task.isCompleted()) {
                    task.onCompleted();
                    tasks.remove(entry.getKey(), task);
                    iterator.remove();
                    continue;
                }

                task.body();
                if (!task.isCompleted()) {
                    task.increment();
                }
                if (task.isDelayScheduled()) {
                    task.unscheduleDelay();
                    iterator.remove();
                }
            }
        }
    }

    public String addTask(String name, GladeTask task) {
        Objects.requireNonNull(task, "task");
        for (Map.Entry<String, GladeTask> running : tasks.entrySet()) {
            if (task.conflictsWith(running.getValue())) {
                throw new IllegalStateException("Task conflicts with running task '"
                        + running.getKey() + "'");
            }
        }
        return putWithAutoName(name, task);
    }

    /** Adds a task that the caller asserts cannot conflict. */
    public String addNonConflictingTask(String name, GladeTask task) {
        try {
            return addTask(name, task);
        } catch (IllegalStateException exception) {
            throw new AssertionError("Task was asserted non-conflicting: " + task, exception);
        }
    }

    /** Adds a task while deliberately bypassing mutex conflict checks. */
    public String forceAddTask(String name, GladeTask task) {
        return putWithAutoName(name, Objects.requireNonNull(task, "task"));
    }

    public int cancel(String nameSubstring) {
        Objects.requireNonNull(nameSubstring, "nameSubstring");
        int cancelled = 0;
        for (Map.Entry<String, GladeTask> entry : tasks.entrySet()) {
            if (entry.getKey().contains(nameSubstring)) {
                entry.getValue()._break();
                cancelled++;
            }
        }
        return cancelled;
    }

    public int cancelAll() {
        int cancelled = tasks.size();
        tasks.values().forEach(GladeTask::_break);
        return cancelled;
    }

    public void onLevelUnload() {
        Iterator<Map.Entry<String, GladeTask>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            GladeTask task = iterator.next().getValue();
            if (task.stopOnLevelUnload()) {
                iterator.remove();
                task.onCompleted();
            }
        }
    }

    /** Returns an immutable, insertion-ordered point-in-time view of running tasks. */
    public Map<String, GladeTask> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
    }

    private String putWithAutoName(String name, GladeTask task) {
        Objects.requireNonNull(name, "name");
        String actualName = nextTaskId++ + "." + name;
        tasks.put(actualName, task);
        return actualName;
    }
}
