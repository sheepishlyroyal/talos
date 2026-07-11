package dev.glade.client.ui.screen;

import dev.glade.client.ui.anim.Animation;
import dev.glade.client.ui.anim.EaseOutQuad;
import dev.glade.client.ui.draw.GladeUi;
import dev.glade.client.ui.theme.AccentGradient;
import dev.glade.client.ui.theme.ColorUtil;
import dev.glade.client.ui.theme.Spacing;
import dev.glade.client.ui.theme.Theme;
import dev.glade.client.ui.theme.ThemeMode;
import dev.glade.client.ui.theme.ThemePalette;
import dev.glade.client.ui.widget.Button;
import dev.glade.client.ui.widget.ToggleSwitch;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The real liquid-glass settings panel: a frosted, gradient-bordered rounded rect
 * that scale-fades open/closed, hosting a theme toggle and a close button — the
 * toolkit's first non-spike screen. Everything drawing-related goes through
 * {@link GladeUi}; nothing here touches a pipeline or vertex format directly.
 *
 * <p>Widgets are plain {@link dev.glade.client.ui.widget.Widget}s, not vanilla
 * {@code Element}s: this screen owns them directly and forwards {@code render} /
 * {@code mouseClicked} by hand (see {@link dev.glade.client.ui.widget.Widget}'s
 * class doc for why).
 */
public final class GladeScreen extends Screen {
    private static final int PANEL_WIDTH = 322;
    private static final int PANEL_HEIGHT = 233;
    private static final int PANEL_RADIUS = 20;
    private static final long OPEN_DURATION_MILLIS = 220;

    private static final Text DESCRIPTION = Text.literal("liquid-glass UI toolkit");
    private static final Text TOGGLE_LABEL = Text.literal("Light theme");
    private static final Text CLOSE_LABEL = Text.literal("Close");

    private final Animation openAnim = new EaseOutQuad(OPEN_DURATION_MILLIS, Animation.Direction.FORWARDS);
    private ToggleSwitch themeToggle;
    private Button closeButton;
    private boolean closing;

    public GladeScreen() {
        super(Text.literal("Glade"));
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        this.themeToggle = new ToggleSwitch(
                panelX + PANEL_WIDTH - Spacing.S24 - 36, panelY + 86,
                Theme.mode() == ThemeMode.LIGHT,
                isLight -> Theme.setMode(isLight ? ThemeMode.LIGHT : ThemeMode.DARK));

        this.closeButton = new Button(
                panelX + PANEL_WIDTH - Spacing.S24 - 80, panelY + PANEL_HEIGHT - Spacing.S24 - 20,
                80, 20, CLOSE_LABEL, this::close);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);

        if (closing && openAnim.isSettled()) {
            this.client.setScreen(null);
            return;
        }

        float progress = openAnim.getValue();
        float scale = 0.92f + 0.08f * progress;

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        float centerX = panelX + PANEL_WIDTH / 2.0f;
        float centerY = panelY + PANEL_HEIGHT / 2.0f;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(centerX, centerY);
        context.getMatrices().scale(scale);
        context.getMatrices().translate(-centerX, -centerY);

        ThemePalette palette = Theme.palette();

        int tint = ColorUtil.withAlpha(palette.blurTint(), progress);
        GladeUi.glassPanel(context, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS, tint);

        // Animated 4-stop accent gradient ring — the toolkit's AccentGradient on
        // display, riding the same border SDF lane as GladeUi.roundedRectBorder.
        AccentGradient.Corners accent = AccentGradient.sample();
        GladeUi.roundedRectBorder(context, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS, 1.5f,
                ColorUtil.withAlpha(accent.topLeft(), progress),
                ColorUtil.withAlpha(accent.topRight(), progress),
                ColorUtil.withAlpha(accent.bottomLeft(), progress),
                ColorUtil.withAlpha(accent.bottomRight(), progress));

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, panelY + 16, ColorUtil.withAlpha(palette.text(), progress));
        context.drawCenteredTextWithShadow(this.textRenderer, DESCRIPTION,
                this.width / 2, panelY + 32, ColorUtil.withAlpha(palette.description(), progress));

        context.drawText(this.textRenderer, TOGGLE_LABEL,
                panelX + Spacing.S24, panelY + 90, ColorUtil.withAlpha(palette.text(), progress), false);

        this.themeToggle.render(context, mouseX, mouseY, deltaTicks);
        this.closeButton.render(context, mouseX, mouseY, deltaTicks);

        context.getMatrices().popMatrix();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (!closing) {
            if (themeToggle.mouseClicked(click.x(), click.y(), click.button())) {
                return true;
            }
            if (closeButton.mouseClicked(click.x(), click.y(), click.button())) {
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void close() {
        // Defer the actual client.setScreen(null) until the close animation settles
        // (see render()) instead of popping instantly — this also fires on vanilla's
        // own Escape-key handling, since Screen.close() is the single choke point.
        if (!closing) {
            closing = true;
            openAnim.setDirection(Animation.Direction.BACKWARDS);
        }
    }
}
