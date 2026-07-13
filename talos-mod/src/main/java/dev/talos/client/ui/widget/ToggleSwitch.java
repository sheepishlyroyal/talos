package dev.talos.client.ui.widget;

import dev.talos.client.ui.anim.Animation;
import dev.talos.client.ui.anim.EaseOutQuad;
import dev.talos.client.ui.draw.TalosUi;
import dev.talos.client.ui.theme.ColorUtil;
import dev.talos.client.ui.theme.Theme;
import dev.talos.client.ui.theme.ThemePalette;
import net.minecraft.client.gui.DrawContext;

import java.util.function.Consumer;

/** A pill-shaped on/off switch: track color and knob position both animate toward the current value. */
public final class ToggleSwitch extends Widget {
    private static final int WIDTH = 36;
    private static final int HEIGHT = 18;
    private static final int KNOB_INSET = 2;

    private final Consumer<Boolean> onChange;
    private final Animation valueAnim;
    private final Animation hoverAnim = new EaseOutQuad(120, Animation.Direction.BACKWARDS);
    private boolean value;

    public ToggleSwitch(int x, int y, boolean initialValue, Consumer<Boolean> onChange) {
        super(x, y, WIDTH, HEIGHT);
        this.value = initialValue;
        this.onChange = onChange;
        this.valueAnim = new EaseOutQuad(160,
                initialValue ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
    }

    public boolean value() {
        return value;
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float deltaTicks) {
        hoverAnim.setDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);

        float onAmount = valueAnim.getValue();
        float hover = hoverAnim.getValue();
        ThemePalette palette = Theme.palette();

        int trackColor = ColorUtil.lerpArgb(palette.rectDark(), palette.accent(), onAmount);
        TalosUi.roundedRect(ctx, x, y, width, height, height / 2.0f, trackColor);
        TalosUi.roundedRectBorder(ctx, x, y, width, height, height / 2.0f, 1.0f,
                ColorUtil.lerpArgb(palette.outline(), palette.accent(), hover * 0.6f));

        float knobDiameter = height - KNOB_INSET * 2.0f;
        float knobTravel = width - height;
        float knobX = x + KNOB_INSET + onAmount * knobTravel;
        TalosUi.roundedRect(ctx, knobX, y + KNOB_INSET, knobDiameter, knobDiameter, knobDiameter / 2.0f, palette.text());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        value = !value;
        valueAnim.setDirection(value ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        playClickSound();
        if (onChange != null) {
            onChange.accept(value);
        }
        return true;
    }
}
