package dev.talos.client.render;

import net.minecraft.world.phys.AABB;

/**
 * A single wireframe highlight: a world-space box, an RGB color, and the
 * client tick at which it expires.
 *
 * @param box       axis-aligned box in absolute world coordinates
 * @param colorRgb  0xRRGGBB (alpha is applied at draw time)
 * @param deathTick {@link RenderQueue} tick counter value at which this box is dropped
 */
public record WireframeBox(AABB box, int colorRgb, int deathTick) {
}
