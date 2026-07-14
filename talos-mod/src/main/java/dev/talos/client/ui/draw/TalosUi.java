package dev.talos.client.ui.draw;

import dev.talos.client.ui.pipeline.RoundedRectRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Reusable immediate-mode drawing facade for Talos's "liquid glass" UI toolkit.
 * Every method here is a thin wrapper over {@link RoundedRectRenderState}, which
 * rides the proven P4a {@code UI_ROUNDED_RECT} pipeline (see
 * {@link dev.talos.client.ui.pipeline.TalosRenderPipelines}) — nothing in this class
 * touches OpenGL or the render state directly.
 */
public final class TalosUi {
    private TalosUi() {
    }

    /** Solid-fill rounded rectangle. {@code color} is ARGB. */
    public static void roundedRect(GuiGraphicsExtractor ctx, float x, float y, float w, float h, float radius, int color) {
        RoundedRectRenderState.draw(ctx, Math.round(x), Math.round(y), Math.round(w), Math.round(h),
                Math.round(radius), color);
    }

    /** Rounded rectangle with an independent ARGB color per corner, bilinearly interpolated. */
    public static void roundedRect(GuiGraphicsExtractor ctx, float x, float y, float w, float h, float radius,
            int colorTopLeft, int colorTopRight, int colorBottomLeft, int colorBottomRight) {
        RoundedRectRenderState.draw(ctx, Math.round(x), Math.round(y), Math.round(w), Math.round(h),
                Math.round(radius), colorTopLeft, colorTopRight, colorBottomLeft, colorBottomRight);
    }

    /**
     * Outline-only rounded rectangle: a {@code borderWidth}-px ring following the same
     * SDF as the filled variants. This rides the same vertex format and pipeline as
     * {@link #roundedRect} — the fragment shader subtracts an inset SDF evaluation to
     * carve the ring out of the filled shape (see {@code ui_rounded_rect.fsh}).
     */
    public static void roundedRectBorder(GuiGraphicsExtractor ctx, float x, float y, float w, float h, float radius,
            float borderWidth, int color) {
        RoundedRectRenderState.drawBorder(ctx, Math.round(x), Math.round(y), Math.round(w), Math.round(h),
                Math.round(radius), Math.round(borderWidth), color);
    }

    /** Gradient variant of {@link #roundedRectBorder}, one ARGB color per corner. */
    public static void roundedRectBorder(GuiGraphicsExtractor ctx, float x, float y, float w, float h, float radius,
            float borderWidth, int colorTopLeft, int colorTopRight, int colorBottomLeft, int colorBottomRight) {
        RoundedRectRenderState.drawBorder(ctx, Math.round(x), Math.round(y), Math.round(w), Math.round(h),
                Math.round(radius), Math.round(borderWidth),
                colorTopLeft, colorTopRight, colorBottomLeft, colorBottomRight);
    }

    /**
     * Frosted "glass" surface: blurs everything drawn earlier this frame, then lays a
     * tinted rounded rect on top of the (now blurred) backdrop.
     *
     * <p><b>Phase-1 limitation.</b> {@code DrawContext.applyBlur()} is already public in
     * 1.21.11 (unlike {@code SimpleGuiElementRenderState} submission, it needs no
     * access-widener) but it blurs <em>everything drawn so far this frame</em>, not just
     * the region under {@code (x, y, w, h)}, and vanilla's {@code GuiRenderState} allows
     * only one blur per frame — a second call throws {@code IllegalStateException}. We
     * swallow that here: the first {@code glassPanel} call in a frame gets the real
     * frosted backdrop; later calls (e.g. glass-styled widgets drawn on top of an
     * already-blurred panel) just draw their tint without re-blurring, which is the
     * correct look since there is nothing further back left to blur. True
     * bounds-scoped blur would use {@code GuiRenderState.createNewRootLayer()} /
     * {@code goUpLayer()} layering to isolate the backdrop — left for a later phase.
     */
    public static void glassPanel(GuiGraphicsExtractor ctx, float x, float y, float w, float h, float radius, int tintColor) {
        Minecraft client = Minecraft.getInstance();
        if (client.options.getMenuBackgroundBlurriness() >= 1) {
            try {
                ctx.blurBeforeThisStratum();
            } catch (IllegalStateException alreadyBlurredThisFrame) {
                // Only one backdrop blur allowed per frame; see method doc above.
            }
        }
        roundedRect(ctx, x, y, w, h, radius, tintColor);
    }
}
