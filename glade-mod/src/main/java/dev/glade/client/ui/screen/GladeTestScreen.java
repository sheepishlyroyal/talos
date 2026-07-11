package dev.glade.client.ui.screen;

import dev.glade.client.ui.pipeline.RoundedRectRenderState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * P4a spike screen: proves the custom SDF rounded-rect {@code RenderPipeline}
 * renders inside a vanilla GUI screen. Opened via {@code /glade ui}.
 *
 * <p>Everything here goes through the vanilla GUI batcher — the panel and badge
 * below share one pipeline and land in a single draw call; vanilla text batches
 * on top with its own pipeline, all inside the same render pass.
 */
public final class GladeTestScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 220;
    private static final int PANEL_RADIUS = 24;

    public GladeTestScreen() {
        super(Text.literal("Glade UI Spike"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);

        int x = (this.width - PANEL_WIDTH) / 2;
        int y = (this.height - PANEL_HEIGHT) / 2;

        // Main panel: 4-corner gradient, heavy rounding.
        RoundedRectRenderState.draw(context, x, y, PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS,
                0xF02B3A4E, 0xF01E4D46, 0xF0201F33, 0xF0402B41);

        // Small solid badge: exercises batching (same pipeline, second quad) and a
        // radius large enough to clamp into a pill shape.
        RoundedRectRenderState.draw(context, x + 16, y + PANEL_HEIGHT - 40, 96, 24, 12, 0xE6E8E4DC);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, y + 14, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("custom RenderPipeline + SDF shader"),
                this.width / 2, y + 30, 0xFFA0B8C8);
    }
}
