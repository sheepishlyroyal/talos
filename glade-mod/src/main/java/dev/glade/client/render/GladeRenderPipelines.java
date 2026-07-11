package dev.glade.client.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;

/**
 * Single choke point for every render layer / pipeline choice Glade makes.
 *
 * <p>All in-world drawing (wireframes today, the P4 UI overlay later) must obtain
 * its {@link RenderLayer} here so that swapping the underlying pipeline — custom
 * shaders, no-depth X-ray variants, translucent fills — is a one-file change.
 */
public final class GladeRenderPipelines {
    private GladeRenderPipelines() {
    }

    /**
     * Layer used for wireframe box outlines. Depth-tested vanilla lines:
     * highlights are occluded by terrain, which is the honest v1 behavior.
     */
    // ponytail: X-ray (see-through-walls) wireframes are a P9 refinement — build a custom
    // pipeline via RenderPipeline.builder(...)
    //     .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
    // wrapped in a RenderLayer, and return it here. For P3 the vanilla depth-tested
    // lines layer is all we need.
    public static RenderLayer wireframeLines() {
        return RenderLayers.lines();
    }
}
