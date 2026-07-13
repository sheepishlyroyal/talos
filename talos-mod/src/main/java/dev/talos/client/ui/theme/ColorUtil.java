package dev.talos.client.ui.theme;

/** Small ARGB int helpers shared by {@link AccentGradient} and the widget package. */
public final class ColorUtil {
    private ColorUtil() {
    }

    /** Per-channel linear interpolation between two ARGB colors. {@code t} is clamped to [0, 1]. */
    public static int lerpArgb(int from, int to, float t) {
        float clamped = Math.min(Math.max(t, 0.0f), 1.0f);
        int a = lerpChannel((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, clamped);
        int r = lerpChannel((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, clamped);
        int g = lerpChannel((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, clamped);
        int b = lerpChannel(from & 0xFF, to & 0xFF, clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Returns {@code color} with its alpha channel replaced by {@code alpha} (0..1). */
    public static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.min(Math.max(alpha, 0.0f), 1.0f) * 255.0f);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static int lerpChannel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }
}
