package dev.glade.client.ui.theme;

/**
 * A slowly rotating 4-stop accent gradient (#E91E63 pink, #3574F0 blue, #9B59B6
 * purple, #F9C12B gold) sampled at 0/90/180/270 degrees of a phase that advances
 * from {@link System#nanoTime()} — never wall-clock time, so it is unaffected by
 * system clock changes and keeps animating smoothly across a paused/resumed game.
 *
 * <p>{@link #sample()} hands back one ARGB color per rect corner, ready to feed
 * straight into {@code GladeUi.roundedRect}'s 4-corner-gradient overload.
 */
public final class AccentGradient {
    private static final int[] STOPS = {
            0xFFE91E63, // pink
            0xFF3574F0, // blue
            0xFF9B59B6, // purple
            0xFFF9C12B, // gold
    };

    /** Full revolution period. Slow and ambient — this is a background detail, not the focus. */
    private static final long PERIOD_NANOS = 8_000_000_000L;

    private AccentGradient() {
    }

    /** One ARGB color per rect corner, sampled at the current phase. */
    public record Corners(int topLeft, int topRight, int bottomLeft, int bottomRight) {
    }

    public static Corners sample() {
        return sample(System.nanoTime());
    }

    /** Overload exposed for deterministic testing/animation-preview; production callers use {@link #sample()}. */
    public static Corners sample(long nanoTime) {
        float phase = phaseDegrees(nanoTime);
        return new Corners(
                colorAt(phase),
                colorAt(phase + 90.0f),
                colorAt(phase + 270.0f),
                colorAt(phase + 180.0f));
    }

    private static float phaseDegrees(long nanoTime) {
        long cycleNanos = Math.floorMod(nanoTime, PERIOD_NANOS);
        return (cycleNanos / (float) PERIOD_NANOS) * 360.0f;
    }

    private static int colorAt(float angleDegrees) {
        float normalized = ((angleDegrees % 360.0f) + 360.0f) % 360.0f;
        float slot = normalized / (360.0f / STOPS.length);
        int index = (int) Math.floor(slot) % STOPS.length;
        int nextIndex = (index + 1) % STOPS.length;
        float t = slot - (float) Math.floor(slot);
        return ColorUtil.lerpArgb(STOPS[index], STOPS[nextIndex], t);
    }
}
