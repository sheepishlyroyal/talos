package dev.talos.client.ui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Minimal widget base for the Talos UI toolkit. Deliberately not a
 * {@code net.minecraft.client.gui.Element} — screens own a plain list of these and
 * forward {@code render}/{@code mouseClicked} calls by hand (see
 * {@link dev.talos.client.ui.screen.TalosScreen}), which sidesteps 1.21.11's
 * {@code Click}-record input plumbing and vanilla's navigation/narration machinery
 * that a full custom {@code ClickableWidget} would otherwise have to implement.
 */
public abstract class Widget {
    protected int x;
    protected int y;
    protected int width;
    protected int height;
    protected boolean hovered;

    protected Widget(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public final int x() {
        return x;
    }

    public final int y() {
        return y;
    }

    public final int width() {
        return width;
    }

    public final int height() {
        return height;
    }

    public final void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public final void render(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float deltaTicks) {
        this.hovered = isMouseOver(mouseX, mouseY);
        renderWidget(ctx, mouseX, mouseY, deltaTicks);
    }

    protected abstract void renderWidget(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float deltaTicks);

    /** @return true if this widget consumed the click (stops the screen from checking siblings). */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    /** Vanilla's standard UI click blip, positioned at the listener (non-spatial). */
    protected static void playClickSound() {
        Minecraft client = Minecraft.getInstance();
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }
}
