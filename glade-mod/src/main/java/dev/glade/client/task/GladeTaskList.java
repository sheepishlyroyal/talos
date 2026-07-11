package dev.glade.client.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A sequential composite that runs one child task at a time. */
public class GladeTaskList extends GladeTask {
    private final List<GladeTask> children = new ArrayList<>();

    public GladeTaskList addTask(GladeTask task) {
        children.add(task);
        return this;
    }

    @Override
    public void initialize() {
        if (!children.isEmpty()) {
            children.getFirst().initializeIfNeeded();
        }
    }

    @Override
    public boolean condition() {
        return !children.isEmpty();
    }

    @Override
    public void increment() {
    }

    @Override
    public void body() {
        GladeTask child = children.getFirst();
        if (child.isCompleted()) {
            child.onCompleted();
            if (child.isDelayScheduled()) {
                scheduleDelay();
            }
            children.removeFirst();
            if (!children.isEmpty()) {
                children.getFirst().initializeIfNeeded();
            }
            return;
        }

        child.body();
        if (!child.isCompleted()) {
            child.increment();
        }
        if (child.isDelayScheduled()) {
            child.unscheduleDelay();
            scheduleDelay();
        }
    }

    @Override
    public boolean stopOnLevelUnload() {
        return children.stream().anyMatch(GladeTask::stopOnLevelUnload);
    }

    @Override
    public void onCompleted() {
        if (!children.isEmpty()) {
            children.getFirst().onCompleted();
        }
    }

    @Override
    public Set<Object> getMutexKeys() {
        Set<Object> union = new HashSet<>();
        for (GladeTask child : children) {
            union.addAll(child.getMutexKeys());
        }
        return Set.copyOf(union);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + children;
    }
}
