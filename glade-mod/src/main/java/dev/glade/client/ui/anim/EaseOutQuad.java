package dev.glade.client.ui.anim;

/** Quadratic ease-out: fast start, gentle settle. The default feel for hover/open transitions. */
public final class EaseOutQuad extends Animation {
    public EaseOutQuad(long durationMillis, Direction initialDirection) {
        super(durationMillis, initialDirection);
    }

    @Override
    protected float ease(float t) {
        return 1.0f - (1.0f - t) * (1.0f - t);
    }
}
