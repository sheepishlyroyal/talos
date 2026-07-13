package dev.talos.client.ui.screen;

import dev.talos.client.blockeditor.util.TalosGuiScale;
import dev.talos.client.ui.anim.Animation;
import dev.talos.client.ui.anim.EaseOutQuad;
import dev.talos.client.ui.draw.TalosUi;
import dev.talos.client.ui.theme.AccentGradient;
import dev.talos.client.ui.theme.ColorUtil;
import dev.talos.client.ui.theme.Spacing;
import dev.talos.client.ui.theme.Theme;
import dev.talos.client.ui.theme.ThemeMode;
import dev.talos.client.ui.theme.ThemePalette;
import dev.talos.client.ui.widget.Button;
import dev.talos.client.ui.widget.ToggleSwitch;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The real liquid-glass settings panel: a frosted, gradient-bordered rounded rect
 * that scale-fades open/closed, hosting a theme toggle and a close button — the
 * toolkit's first non-spike screen. Everything drawing-related goes through
 * {@link TalosUi}; nothing here touches a pipeline or vertex format directly.
 *
 * <p>Widgets are plain {@link dev.talos.client.ui.widget.Widget}s, not vanilla
 * {@code Element}s: this screen owns them directly and forwards {@code render} /
 * {@code mouseClicked} by hand (see {@link dev.talos.client.ui.widget.Widget}'s
 * class doc for why).
 */
public final class TalosScreen extends Screen {
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
    // Item 9: cap the effective GUI scale this panel lays out against so it stays legible at the
    // largest configured GUI scales — see TalosGuiScale's class doc. Identity (factor 1) at scale 1-3.
    private TalosGuiScale.Adjust scaleAdjust = new TalosGuiScale.Adjust(1f, 1, 1);

    public TalosScreen() {
        super(Text.literal("Talos"));
    }

    @Override
    protected void init() {
        scaleAdjust = TalosGuiScale.compute(this.width, this.height);
        int panelX = (scaleAdjust.virtualWidth() - PANEL_WIDTH) / 2;
        int panelY = (scaleAdjust.virtualHeight() - PANEL_HEIGHT) / 2;

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

        scaleAdjust = TalosGuiScale.compute(this.width, this.height); // GUI Scale can change while open
        int vw = scaleAdjust.virtualWidth(), vh = scaleAdjust.virtualHeight();
        int panelX = (vw - PANEL_WIDTH) / 2;
        int panelY = (vh - PANEL_HEIGHT) / 2;
        float centerX = panelX + PANEL_WIDTH / 2.0f;
        float centerY = panelY + PANEL_HEIGHT / 2.0f;

        context.getMatrices().pushMatrix();
        context.getMatrices().scale(scaleAdjust.factor());
        context.getMatrices().translate(centerX, centerY);
        context.getMatrices().scale(scale);
        context.getMatrices().translate(-centerX, -centerY);

        ThemePalette palette = Theme.palette();

        // Keep the palette's designed translucency (blurTint alpha ~0xB0 ≈ 0.69) and
        // only fade it in with the open animation — never drive it to fully opaque.
        // Overwriting the alpha with `progress` (which settles at 1.0) made the glass
        // solid once the panel finished opening, hiding the blurred backdrop entirely.
        float baseAlpha = ((palette.blurTint() >>> 24) & 0xFF) / 255.0f;
        int tint = ColorUtil.withAlpha(palette.blurTint(), baseAlpha * progress);
        TalosUi.glassPanel(context, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS, tint);

        // Animated 4-stop accent gradient ring — the toolkit's AccentGradient on
        // display, riding the same border SDF lane as TalosUi.roundedRectBorder.
        AccentGradient.Corners accent = AccentGradient.sample();
        TalosUi.roundedRectBorder(context, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS, 1.5f,
                ColorUtil.withAlpha(accent.topLeft(), progress),
                ColorUtil.withAlpha(accent.topRight(), progress),
                ColorUtil.withAlpha(accent.bottomLeft(), progress),
                ColorUtil.withAlpha(accent.bottomRight(), progress));

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                vw / 2, panelY + 16, ColorUtil.withAlpha(palette.text(), progress));
        context.drawCenteredTextWithShadow(this.textRenderer, DESCRIPTION,
                vw / 2, panelY + 32, ColorUtil.withAlpha(palette.description(), progress));

        context.drawText(this.textRenderer, TOGGLE_LABEL,
                panelX + Spacing.S24, panelY + 90, ColorUtil.withAlpha(palette.text(), progress), false);

        int vMouseX = (int) scaleAdjust.toVirtualX(mouseX), vMouseY = (int) scaleAdjust.toVirtualY(mouseY);
        this.themeToggle.render(context, vMouseX, vMouseY, deltaTicks);
        this.closeButton.render(context, vMouseX, vMouseY, deltaTicks);

        context.getMatrices().popMatrix();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double vx = scaleAdjust.toVirtualX(click.x());
        double vy = scaleAdjust.toVirtualY(click.y());
        if (!closing) {
            if (themeToggle.mouseClicked(vx, vy, click.button())) {
                return true;
            }
            if (closeButton.mouseClicked(vx, vy, click.button())) {
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
