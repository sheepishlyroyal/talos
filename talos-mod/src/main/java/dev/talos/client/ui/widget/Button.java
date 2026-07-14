package dev.talos.client.ui.widget;

import dev.talos.client.ui.anim.Animation;
import dev.talos.client.ui.anim.EaseOutQuad;
import dev.talos.client.ui.draw.TalosUi;
import dev.talos.client.ui.theme.ColorUtil;
import dev.talos.client.ui.theme.Theme;
import dev.talos.client.ui.theme.ThemePalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A glass-styled rounded button: label centered, hover lifts its tint toward the accent color. */
public final class Button extends Widget {
    private static final float RADIUS_FRACTION = 0.35f;

    private final Component label;
    private final Runnable onClick;
    private final Animation hoverAnim = new EaseOutQuad(120, Animation.Direction.BACKWARDS);

    public Button(int x, int y, int width, int height, Component label, Runnable onClick) {
        super(x, y, width, height);
        this.label = label;
        this.onClick = onClick;
    }

    @Override
    protected void renderWidget(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float deltaTicks) {
        hoverAnim.setDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float hover = hoverAnim.getValue();

        ThemePalette palette = Theme.palette();
        float radius = height * RADIUS_FRACTION;
        int fill = ColorUtil.lerpArgb(palette.rect(), palette.accent(), hover * 0.4f);

        TalosUi.glassPanel(ctx, x, y, width, height, radius, fill);
        TalosUi.roundedRectBorder(ctx, x, y, width, height, radius, 1.0f,
                ColorUtil.lerpArgb(palette.outline(), palette.accent(), hover));

        Minecraft client = Minecraft.getInstance();
        int textWidth = client.font.width(label);
        int textX = x + Math.round((width - textWidth) / 2.0f);
        int textY = y + Math.round((height - client.font.lineHeight) / 2.0f);
        ctx.text(client.font, label, textX, textY, palette.text(), false);
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
