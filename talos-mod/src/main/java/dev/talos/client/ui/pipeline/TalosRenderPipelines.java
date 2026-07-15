package dev.talos.client.ui.pipeline;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/**
 * Single choke point for every render layer / pipeline choice Talos makes.
 *
 * <p>All drawing — in-world wireframes and the P4 UI overlay — must obtain its
 * {@link RenderPipeline} here so that swapping the underlying
 * pipeline — custom shaders, no-depth X-ray variants, translucent fills — is a
 * one-file change.
 *
 * <h2>How custom GUI pipelines work on 26.2</h2>
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
     * <p>26.2 describes attributes directly with {@link GpuFormat}; the names and formats
     * mirror the vanilla GUI-compatible position/color/UV channels.
     */
    public static final VertexFormat UI_ROUNDED_RECT_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("UV1", GpuFormat.RG16_SINT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .build();

    /**
     * GUI-layer pipeline drawing antialiased rounded rectangles via a signed-distance
     * function, with a bilinear 4-corner gradient from per-vertex colors. Mirrors
     * vanilla's {@code GUI} pipeline state (translucent blend, no depth test) and
     * declares exactly the UBOs the GUI render pass binds.
     *
     * <p>The vanilla GUI snippet supplies the standard bind-group layouts, while the
     * custom vertex binding and quad topology carry the SDF parameters.
     */
    public static final RenderPipeline UI_ROUNDED_RECT = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("talos", "pipeline/ui_rounded_rect"))
            .withVertexShader(Identifier.fromNamespaceAndPath("talos", "core/ui_rounded_rect"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("talos", "core/ui_rounded_rect"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(Optional.empty())
            .withVertexBinding(0, UI_ROUNDED_RECT_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();
}
