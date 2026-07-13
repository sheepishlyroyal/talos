package dev.talos.client.task;

/** A fire-and-forget task that performs its work during initialization. */
public abstract class OneTickTask extends TalosTask {
    @Override
    public final void initialize() {
        run();
    }

    @Override
    public final boolean condition() {
        return false;
    }

    @Override
    public final void increment() {
    }

    @Override
    public final void body() {
    }

    protected abstract void run();
}
