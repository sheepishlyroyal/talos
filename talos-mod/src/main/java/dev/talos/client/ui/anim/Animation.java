package dev.talos.client.ui.anim;

/**
 * A reversible, direction-aware animation timer measured against
 * {@link System#nanoTime()}. Every instance walks a single {@code progress} value in
 * {@code [0, 1]} — 0 is the BACKWARDS resting point, 1 is the FORWARDS resting point —
 * and {@link #getValue()} returns that progress passed through the concrete
 * subclass's {@link #ease(float)} curve.
 *
 * <p>{@link #setDirection} (and {@link #flip}) never resets the timer to an
 * endpoint: they re-base it from the animation's <em>current</em> progress and scale
 * the remaining leg's duration by the remaining distance, so an animation
 * interrupted mid-flight reverses smoothly from wherever it was — at the same
 * progress-per-second rate it was already moving at — instead of popping to 0 or 1
 * or lurching to a different speed. Because {@code progress} is what gets re-based —
 * not the eased output — and {@link #ease(float)} is applied fresh on every read, the
 * eased value stays continuous across the flip too.
 */
public abstract class Animation {
    public enum Direction {
        FORWARDS,
        BACKWARDS
    }

    private final long fullDurationNanos;
    private Direction direction;
    private float baseProgress;
    private long baseNanos;
    private long legDurationNanos;

    protected Animation(long durationMillis, Direction initialDirection) {
        this.fullDurationNanos = Math.max(durationMillis, 1L) * 1_000_000L;
        this.direction = initialDirection;
        this.baseProgress = initialDirection == Direction.FORWARDS ? 0.0f : 1.0f;
        this.baseNanos = System.nanoTime();
        this.legDurationNanos = fullDurationNanos;
    }

    public final Direction direction() {
        return direction;
    }

    /**
     * Re-bases the timer at the current progress and starts moving toward the new
     * direction's endpoint, at the same progress-per-second rate as before.
     */
    public final void setDirection(Direction newDirection) {
        if (newDirection == this.direction) {
            return;
        }
        float current = progress();
        float target = newDirection == Direction.FORWARDS ? 1.0f : 0.0f;
        this.direction = newDirection;
        this.baseProgress = current;
        this.baseNanos = System.nanoTime();
        // Scale by remaining distance so velocity (not just position) is continuous.
        this.legDurationNanos = Math.max(Math.round(fullDurationNanos * Math.abs(target - current)), 1L);
    }

    public final void flip() {
        setDirection(direction == Direction.FORWARDS ? Direction.BACKWARDS : Direction.FORWARDS);
    }

    /** Eased value in [0, 1] for the current instant. */
    public final float getValue() {
        return ease(progress());
    }

    /** True once progress has reached the current direction's resting endpoint. */
    public final boolean isSettled() {
        float p = progress();
        return direction == Direction.FORWARDS ? p >= 1.0f : p <= 0.0f;
    }

    private float progress() {
        float elapsedFraction = (System.nanoTime() - baseNanos) / (float) legDurationNanos;
        float target = direction == Direction.FORWARDS ? 1.0f : 0.0f;
        float raw = baseProgress + (target - baseProgress) * Math.min(Math.max(elapsedFraction, 0.0f), 1.0f);
        return Math.min(Math.max(raw, 0.0f), 1.0f);
    }

    /** Maps linear progress {@code t} in [0, 1] to the eased output, also in [0, 1]. */
    protected abstract float ease(float t);
}
