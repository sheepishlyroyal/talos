package dev.glade.client.blockeditor.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/**
 * Caps the effective GUI scale Glade's own screens lay out and render against (item 9).
 *
 * <p>At the user's raw GUI scale, {@code Screen.width}/{@code height} shrink as the scale
 * factor grows (fewer logical pixels per physical pixel), so fixed-size panels and text sized
 * for scale 1-3 start overflowing or overlapping at scale 4-6, reading as broken/illegible.
 *
 * <p>The fix: whenever the real scale factor exceeds {@link #MAX_EFFECTIVE_SCALE}, lay out and
 * draw content against a larger "virtual" canvas sized as if the scale were capped at 3, then
 * shrink that virtual canvas down with a single matrix scale so it exactly fills the real
 * (smaller) logical screen. Below the cap this is a no-op (factor stays 1.0).
 */
public final class GladeGuiScale {
    public static final int MAX_EFFECTIVE_SCALE = 3;

    private GladeGuiScale() {}

    /** @param factor uniform matrix scale to apply around all rendering (<= 1.0)
     *  @param virtualWidth  layout width to compute positions against (== realWidth when factor == 1)
     *  @param virtualHeight layout height to compute positions against */
    public record Adjust(float factor, int virtualWidth, int virtualHeight) {
        /** Converts a real (post-scissor, screen-space) mouse coordinate into virtual layout space. */
        public double toVirtualX(double realX) { return factor >= 1f ? realX : realX / factor; }
        public double toVirtualY(double realY) { return factor >= 1f ? realY : realY / factor; }
    }

    public static Adjust compute(int realWidth, int realHeight) {
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client == null ? null : client.getWindow();
        int actualScale = window == null ? 1 : Math.max(1, window.getScaleFactor());
        int effectiveScale = Math.min(actualScale, MAX_EFFECTIVE_SCALE);
        float factor = (float) effectiveScale / actualScale;
        if (factor >= 1f) return new Adjust(1f, realWidth, realHeight);
        int vw = Math.max(1, Math.round(realWidth / factor));
        int vh = Math.max(1, Math.round(realHeight / factor));
        return new Adjust(factor, vw, vh);
    }
}
