package dev.glade.client.ui.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/**
 * GUI element render state for one SDF rounded rectangle, drawn with
 * {@link GladeRenderPipelines#UI_ROUNDED_RECT}.
 *
 * <p>Submitted through the vanilla 1.21.11 GUI batcher: {@code GuiRenderer} groups
 * consecutive states by (pipeline, texture, scissor) and writes them into a shared
 * vertex buffer using this state's {@link #setupVertices}, so any number of these
 * rects per frame collapses into a single draw call.
 *
 * <p>Corner colors interpolate bilinearly across the quad, giving the 4-corner
 * gradient for free; the SDF parameters ride in the UV channels (see
 * {@link GladeRenderPipelines#UI_ROUNDED_RECT_FORMAT}).
 */
public record RoundedRectRenderState(
        Matrix3x2fc pose,
        int x0,
        int y0,
        int x1,
        int y1,
        int radius,
        int colorTopLeft,
        int colorTopRight,
        int colorBottomLeft,
        int colorBottomRight,
        @Nullable ScreenRect scissorArea,
        @Nullable ScreenRect bounds) implements SimpleGuiElementRenderState {

    /**
     * Queues one rounded rect on the current GUI layer. Colors are ARGB.
     * Radius is clamped to half the smaller rect dimension by the SDF itself.
     */
    public static void draw(DrawContext context, int x, int y, int width, int height, int radius,
                            int colorTopLeft, int colorTopRight, int colorBottomLeft, int colorBottomRight) {
        // Snapshot the pose: the context's matrix stack mutates as the screen renders,
        // but this state is only replayed at end of frame (mirrors vanilla fill()).
        Matrix3x2f pose = new Matrix3x2f(context.getMatrices());
        // DrawContext.state is access-widened (glade.accesswidener); vanilla offers no
        // public entry point for custom SimpleGuiElementRenderState implementations.
        context.state.addSimpleElement(new RoundedRectRenderState(
                pose, x, y, x + width, y + height, radius,
                colorTopLeft, colorTopRight, colorBottomLeft, colorBottomRight,
                null,
                new ScreenRect(x, y, width, height).transformEachVertex(pose)));
    }

    /** Convenience overload: single fill color. */
    public static void draw(DrawContext context, int x, int y, int width, int height, int radius, int color) {
        draw(context, x, y, width, height, radius, color, color, color, color);
    }

    @Override
    public void setupVertices(VertexConsumer vertices) {
        float centerX = (this.x0 + this.x1) / 2.0f;
        float centerY = (this.y0 + this.y1) / 2.0f;
        // Same winding as vanilla ColoredQuadGuiElementRenderState.
        this.vertex(vertices, this.x0, this.y0, centerX, centerY, this.colorTopLeft);
        this.vertex(vertices, this.x0, this.y1, centerX, centerY, this.colorBottomLeft);
        this.vertex(vertices, this.x1, this.y1, centerX, centerY, this.colorBottomRight);
        this.vertex(vertices, this.x1, this.y0, centerX, centerY, this.colorTopRight);
    }

    private void vertex(VertexConsumer vertices, int x, int y, float centerX, float centerY, int color) {
        vertices.vertex(this.pose, x, y)
                .color(color)
                // UV0: this corner's offset from the rect center, in GUI px.
                .texture(x - centerX, y - centerY)
                // UV1 (overlay shorts): full extent; the shader halves it, keeping
                // odd sizes exact.
                .overlay(this.x1 - this.x0, this.y1 - this.y0)
                // UV2 (light shorts): corner radius + reserved lane.
                .light(this.radius, 0);
    }

    @Override
    public RenderPipeline pipeline() {
        return GladeRenderPipelines.UI_ROUNDED_RECT;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.empty();
    }
}
