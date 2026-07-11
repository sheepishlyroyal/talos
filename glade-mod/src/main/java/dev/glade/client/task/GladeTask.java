package dev.glade.client.task;

import java.util.Set;

/**
 * A cooperatively scheduled client task.
 *
 * <p>Implementations must return from {@link #body()} promptly. A task that wants to wait for
 * the next client tick calls {@link #scheduleDelay()}; otherwise the scheduler may invoke it
 * again during the current tick.</p>
 */
public abstract class GladeTask {
    private boolean initialized;
    private boolean delayScheduled;
    private boolean broken;

    public abstract void initialize();

    public abstract boolean condition();

    public abstract void increment();

    public abstract void body();

    public void onCompleted() {
    }

    public final void _break() {
        broken = true;
    }

    public final boolean isCompleted() {
        return broken || !condition();
    }

    public final void scheduleDelay() {
        delayScheduled = true;
    }

    public final void unscheduleDelay() {
        delayScheduled = false;
    }

    public final boolean isDelayScheduled() {
        return delayScheduled;
    }

    public Set<Object> getMutexKeys() {
        return Set.of();
    }

    public final boolean conflictsWith(GladeTask other) {
        return getMutexKeys().stream().anyMatch(other.getMutexKeys()::contains);
    }

    public boolean stopOnLevelUnload() {
        return true;
    }

    final void initializeIfNeeded() {
        if (!initialized) {
            initialize();
            initialized = true;
        }
    }
}
