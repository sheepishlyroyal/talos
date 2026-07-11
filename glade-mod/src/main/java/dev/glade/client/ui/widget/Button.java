package dev.glade.client.ui.widget;

import dev.glade.client.ui.anim.Animation;
import dev.glade.client.ui.anim.EaseOutQuad;
import dev.glade.client.ui.draw.GladeUi;
import dev.glade.client.ui.theme.ColorUtil;
import dev.glade.client.ui.theme.Theme;
import dev.glade.client.ui.theme.ThemePalette;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** A glass-styled rounded button: label centered, hover lifts its tint toward the accent color. */
public final class Button extends Widget {
    private static final float RADIUS_FRACTION = 0.35f;

    private final Text label;
    private final Runnable onClick;
    private final Animation hoverAnim = new EaseOutQuad(120, Animation.Direction.BACKWARDS);

    public Button(int x, int y, int width, int height, Text label, Runnable onClick) {
        super(x, y, width, height);
        this.label = label;
        this.onClick = onClick;
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float deltaTicks) {
        hoverAnim.setDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float hover = hoverAnim.getValue();

        ThemePalette palette = Theme.palette();
        float radius = height * RADIUS_FRACTION;
        int fill = ColorUtil.lerpArgb(palette.rect(), palette.accent(), hover * 0.4f);

        GladeUi.glassPanel(ctx, x, y, width, height, radius, fill);
        GladeUi.roundedRectBorder(ctx, x, y, width, height, radius, 1.0f,
                ColorUtil.lerpArgb(palette.outline(), palette.accent(), hover));

        MinecraftClient client = MinecraftClient.getInstance();
        int textWidth = client.textRenderer.getWidth(label);
        int textX = x + Math.round((width - textWidth) / 2.0f);
        int textY = y + Math.round((height - client.textRenderer.fontHeight) / 2.0f);
        ctx.drawText(client.textRenderer, label, textX, textY, palette.text(), false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        playClickSound();
        if (onClick != null) {
            onClick.run();
        }
        return true;
    }
}
