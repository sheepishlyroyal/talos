package dev.talos.client.ui.pipeline;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/**
 * Single choke point for every render layer / pipeline choice Talos makes.
 *
 * <p>All drawing — in-world wireframes and the P4 UI overlay — must obtain its
 * {@link RenderType} or {@link RenderPipeline} here so that swapping the underlying
 * pipeline — custom shaders, no-depth X-ray variants, translucent fills — is a
 * one-file change.
 *
 * <h2>How custom GUI pipelines work on 1.21.11 (P4a spike findings)</h2>
 *
 * <p>The JSON core-shader system is gone (1.21.9+). A pipeline is now built in code via
 * {@link RenderPipeline#builder}, pointing at raw GLSL assets resolved by
 * {@code ShaderType.idConverter()} as {@code assets/<ns>/shaders/<path>.vsh|.fsh}.
 * Vanilla's own registry ({@code net.minecraft.client.gl.RenderPipelines#register}) is
 * private and only feeds startup precompilation; mod pipelines simply compile lazily on
 * first use, so a static-final constant here is the whole registration story.
 *
 * <p>On the GUI path, {@code GuiRenderer} batches {@code SimpleGuiElementRenderState}s
 * by (pipeline, textureSetup, scissor) into per-{@link VertexFormat} ring buffers, so a
 * custom vertex format is fully supported. Per-element shader uniforms are NOT: the GUI
 * render pass only binds the standard UBOs ({@code DynamicTransforms}, {@code Projection},
 * {@code Fog}, globals). Anything per-rect therefore rides in vertex attributes — see
 * {@link #UI_ROUNDED_RECT_FORMAT}.
 */
public final class TalosRenderPipelines {
    private TalosRenderPipelines() {
    }

    /**
     * Vertex format for SDF-shaded GUI rects. Since the GUI render pass exposes no
     * per-element uniforms, the rect geometry parameters ride in vanilla UV channels:
     *
     * <ul>
     *   <li>{@code UV0} (2 floats) — fragment offset from the rect center, in GUI px</li>
     *   <li>{@code UV1} (2 shorts, the overlay channel) — full rect extent (w, h)</li>
     *   <li>{@code UV2} (2 shorts, the light channel) — (corner radius, reserved)</li>
     * </ul>
     *
     * <p>Reusing vanilla {@link VertexFormatElement}s (instead of registering new ones)
     * avoids the global 32-element id space and keeps {@code BufferBuilder}'s standard
     * {@code texture/overlay/light} writers usable.
     */
    public static final VertexFormat UI_ROUNDED_RECT_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .build();

    /**
     * GUI-layer pipeline drawing antialiased rounded rectangles via a signed-distance
     * function, with a bilinear 4-corner gradient from per-vertex colors. Mirrors
     * vanilla's {@code GUI} pipeline state (translucent blend, no depth test) and
     * declares exactly the UBOs the GUI render pass binds.
     *
     * <p>{@code withUsePipelineDrawModeForGui} is a Fabric API injected method
     * (fabric-rendering-v1 {@code FabricRenderPipeline.Builder}); it makes the GUI
     * renderer honor this pipeline's draw mode for index-buffer selection instead of
     * assuming quads. We draw quads anyway, but wiring it keeps the intent explicit.
     */
    public static final RenderPipeline UI_ROUNDED_RECT = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("talos", "pipeline/ui_rounded_rect"))
            .withVertexShader(Identifier.fromNamespaceAndPath("talos", "core/ui_rounded_rect"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("talos", "core/ui_rounded_rect"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(Optional.empty())
            .withVertexFormat(UI_ROUNDED_RECT_FORMAT, VertexFormat.Mode.QUADS)
            .withUsePipelineDrawModeForGui(true)
            .build();

    /**
     * Layer used for wireframe box outlines. Depth-tested vanilla lines:
     * highlights are occluded by terrain, which is the honest v1 behavior.
     */
    // ponytail: X-ray (see-through-walls) wireframes are a P9 refinement — build a custom
    // pipeline via RenderPipeline.builder(...)
    //     .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
    // wrapped in a RenderLayer, and return it here. For P3 the vanilla depth-tested
    // lines layer is all we need.
    public static RenderType wireframeLines() {
        return RenderTypes.lines();
    }
}
