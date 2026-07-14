package dev.talos.client.ui.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/**
 * GUI element render state for one SDF rounded rectangle, drawn with
 * {@link TalosRenderPipelines#UI_ROUNDED_RECT}.
 *
 * <p>Submitted through the vanilla 1.21.11 GUI batcher: {@code GuiRenderer} groups
 * consecutive states by (pipeline, texture, scissor) and writes them into a shared
 * vertex buffer using this state's {@link #buildVertices}, so any number of these
 * rects per frame collapses into a single draw call.
 *
 * <p>Corner colors interpolate bilinearly across the quad, giving the 4-corner
 * gradient for free; the SDF parameters ride in the UV channels (see
 * {@link TalosRenderPipelines#UI_ROUNDED_RECT_FORMAT}).
 *
 * <p>{@code borderWidth} rides the previously-reserved second short of the UV2
 * (light) channel. When it is {@code 0} the fragment shader fills the whole SDF
 * shape (the original P4a behavior, unchanged); when positive the shader carves
 * out the interior with a second, inset SDF evaluation, leaving only a
 * {@code borderWidth}-px ring. No new vertex format or pipeline is needed.
 */
public record RoundedRectRenderState(
        Matrix3x2fc pose,
        int x0,
        int y0,
        int x1,
        int y1,
        int radius,
        int borderWidth,
        int colorTopLeft,
        int colorTopRight,
        int colorBottomLeft,
        int colorBottomRight,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds) implements GuiElementRenderState {

    /**
     * Queues one filled rounded rect on the current GUI layer. Colors are ARGB.
     * Radius is clamped to half the smaller rect dimension by the SDF itself.
     */
    public static void draw(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius,
                            int colorTopLeft, int colorTopRight, int colorBottomLeft, int colorBottomRight) {
        submit(context, x, y, width, height, radius, 0,
                colorTopLeft, colorTopRight, colorBottomLeft, colorBottomRight);
    }

    /** Convenience overload: single fill color. */
    public static void draw(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius, int color) {
        draw(context, x, y, width, height, radius, color, color, color, color);
    }

    /**
     * Queues one rounded rect outline (a {@code borderWidth}-px ring inset from the
     * SDF edge) on the current GUI layer. Colors are ARGB, one per corner.
     */
    public static void drawBorder(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius,
                                  int borderWidth, int colorTopLeft, int colorTopRight,
                                  int colorBottomLeft, int colorBottomRight) {
        submit(context, x, y, width, height, radius, borderWidth,
                colorTopLeft, colorTopRight, colorBottomLeft, colorBottomRight);
    }

    /** Convenience overload: single border color. */
    public static void drawBorder(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius,
                                  int borderWidth, int color) {
        drawBorder(context, x, y, width, height, radius, borderWidth, color, color, color, color);
    }

    private static void submit(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius,
                               int borderWidth, int colorTopLeft, int colorTopRight,
                               int colorBottomLeft, int colorBottomRight) {
        // Snapshot the pose: the context's matrix stack mutates as the screen renders,
        // but this state is only replayed at end of frame (mirrors vanilla fill()).
        Matrix3x2f pose = new Matrix3x2f(context.pose());
        // DrawContext.state is access-widened (talos.accesswidener); vanilla offers no
        // public entry point for custom SimpleGuiElementRenderState implementations.
        context.guiRenderState.addGuiElement(new RoundedRectRenderState(
                pose, x, y, x + width, y + height, radius, borderWidth,
                colorTopLeft, colorTopRight, colorBottomLeft, colorBottomRight,
                null,
                new ScreenRectangle(x, y, width, height).transformMaxBounds(pose)));
    }

    @Override
    public void buildVertices(VertexConsumer vertices) {
        float centerX = (this.x0 + this.x1) / 2.0f;
        float centerY = (this.y0 + this.y1) / 2.0f;
        // Same winding as vanilla ColoredQuadGuiElementRenderState.
        this.vertex(vertices, this.x0, this.y0, centerX, centerY, this.colorTopLeft);
        this.vertex(vertices, this.x0, this.y1, centerX, centerY, this.colorBottomLeft);
        this.vertex(vertices, this.x1, this.y1, centerX, centerY, this.colorBottomRight);
        this.vertex(vertices, this.x1, this.y0, centerX, centerY, this.colorTopRight);
    }

    private void vertex(VertexConsumer vertices, int x, int y, float centerX, float centerY, int color) {
        vertices.addVertexWith2DPose(this.pose, x, y)
                .setColor(color)
                // UV0: this corner's offset from the rect center, in GUI px.
                .setUv(x - centerX, y - centerY)
                // UV1 (overlay shorts): full extent; the shader halves it, keeping
                // odd sizes exact.
                .setUv1(this.x1 - this.x0, this.y1 - this.y0)
                // UV2 (light shorts): corner radius + border width (0 = filled).
                .setUv2(this.radius, this.borderWidth);
    }

    @Override
    public RenderPipeline pipeline() {
        return TalosRenderPipelines.UI_ROUNDED_RECT;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }
}
