package dev.glade.client.ui.anim;

/** No easing — constant rate. Useful for continuous/looping drivers rather than UI transitions. */
public final class Linear extends Animation {
    public Linear(long durationMillis, Direction initialDirection) {
        super(durationMillis, initialDirection);
    }

    @Override
    protected float ease(float t) {
        return t;
    }
}
