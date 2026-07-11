package dev.glade.client.task;

/** A task whose work method is invoked at most once per client tick. */
public abstract class SimpleTask extends GladeTask {
    @Override
    public void initialize() {
    }

    @Override
    public void increment() {
    }

    @Override
    public final void body() {
        onTick();
        scheduleDelay();
    }

    protected abstract void onTick();
}
