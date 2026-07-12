package dev.glade.client.blockeditor.screen;

import dev.glade.client.blockeditor.canvas.BlockCanvas;
import dev.glade.client.blockeditor.codegen.PythonCodeGenerator;
import dev.glade.client.blockeditor.file.GladeProjectFile;
import dev.glade.client.blockeditor.model.BlockNode;
import dev.glade.client.blockeditor.model.Workspace;
import dev.glade.client.blockeditor.registry.BlockRegistry;
import dev.glade.client.blockeditor.util.GladeGuiScale;
import dev.glade.client.script.ScriptEngine;
import dev.glade.client.ui.draw.GladeUi;
import dev.glade.client.ui.screen.PythonEditorScreen;
import dev.glade.client.ui.theme.Theme;
import dev.glade.client.ui.widget.Button;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Native full-screen v1 block editor opened by {@code /glade editor}. */
public final class BlockEditorScreen extends Screen {
    private static final String PROJECT_NAME = "block-editor";
    private static final String BUILD_NAME = "block_editor_generated";
    // Item 8: one-way blocks -> Python export. The generated file is written under this name so
    // the confirmation warning can tell the user exactly what to type/Load in the Python editor.
    private static final String SWITCH_SCRIPT_NAME = "block_editor_switch";
    private static final String SWITCH_WARNING =
            "Switch to Python mode? This converts your blocks to Python one-way. "
                    + "You cannot convert Python back to blocks. Your saved block project is kept.";
    private final GladeProjectFile projects = new GladeProjectFile();
    private final PythonCodeGenerator generator = new PythonCodeGenerator();
    private Workspace workspace = initialWorkspace();
    private final BlockCanvas canvas = new BlockCanvas(workspace);
    private List<Button> toolbar = List.of();
    private List<Button> switchWarningButtons = List.of();
    private boolean switchWarningVisible;
    private volatile String status = "Ready — project: " + PROJECT_NAME + ".glade";
    private volatile boolean statusError;
    // Item 9: at large GUI scales this.width/height shrink below what the toolbar/canvas need to
    // stay legible, so all layout and rendering below goes through a capped "virtual" canvas —
    // see GladeGuiScale's class doc. scaleAdjust.factor() == 1 for scale 1-3 (identity, no change).
    private GladeGuiScale.Adjust scaleAdjust = new GladeGuiScale.Adjust(1f, 1, 1);

    public BlockEditorScreen() { super(Text.literal("Glade Block Editor")); }

    @Override
    protected void init() {
        scaleAdjust = GladeGuiScale.compute(width, height);
        int vw = scaleAdjust.virtualWidth(), vh = scaleAdjust.virtualHeight();
        int buttonY = 9;
        toolbar = List.of(
                new Button(12, buttonY, 54, 20, Text.literal("New"), this::newProject),
                new Button(72, buttonY, 54, 20, Text.literal("Save"), this::saveProject),
                new Button(132, buttonY, 54, 20, Text.literal("Load"), this::loadProject),
                new Button(192, buttonY, 54, 20, Text.literal("Run"), this::runProject),
                new Button(252, buttonY, 108, 20, Text.literal("Switch to Python"), this::requestSwitchToPython));
        canvas.bounds(8, 38, Math.max(100, vw - 16), Math.max(80, vh - 46));

        int warnCenterX = vw / 2;
        int warnButtonY = vh / 2 + 46;
        switchWarningButtons = List.of(
                new Button(warnCenterX - 116, warnButtonY, 108, 20, Text.literal("Cancel"), this::cancelSwitchToPython),
                new Button(warnCenterX + 8, warnButtonY, 108, 20, Text.literal("Confirm"), this::confirmSwitchToPython));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        // Recompute every frame: the user can change the GUI Scale option while this screen is open.
        scaleAdjust = GladeGuiScale.compute(width, height);
        int vw = scaleAdjust.virtualWidth(), vh = scaleAdjust.virtualHeight();
        canvas.bounds(8, 38, Math.max(100, vw - 16), Math.max(80, vh - 46));
        int vMouseX = (int) scaleAdjust.toVirtualX(mouseX), vMouseY = (int) scaleAdjust.toVirtualY(mouseY);

        context.getMatrices().pushMatrix();
        context.getMatrices().scale(scaleAdjust.factor());
        context.fill(0, 0, vw, vh, Theme.palette().bg());
        GladeUi.glassPanel(context, 6, 5, vw - 12, 29, 10, Theme.palette().panelHeader());
        context.drawCenteredTextWithShadow(textRenderer, title, vw / 2, 12, Theme.palette().text());
        for (Button button : toolbar) button.render(context, vMouseX, vMouseY, deltaTicks);
        int statusWidth = textRenderer.getWidth(status);
        context.drawText(textRenderer, status, Math.max(368, vw - statusWidth - 14), 12,
                statusError ? 0xFFFF7777 : Theme.palette().description(), false);
        canvas.render(context, vMouseX, vMouseY, deltaTicks);
        if (switchWarningVisible) renderSwitchWarning(context, vw, vh, vMouseX, vMouseY, deltaTicks);
        context.getMatrices().popMatrix();
    }

    private void renderSwitchWarning(DrawContext context, int vw, int vh, int vMouseX, int vMouseY, float deltaTicks) {
        int panelWidth = Math.min(320, vw - 24);
        int panelHeight = 118;
        int panelX = (vw - panelWidth) / 2;
        int panelY = vh / 2 - panelHeight / 2;
        context.fill(0, 0, vw, vh, 0xC0000000);
        GladeUi.glassPanel(context, panelX, panelY, panelWidth, panelHeight, 10, Theme.palette().panelHeader());
        GladeUi.roundedRectBorder(context, panelX, panelY, panelWidth, panelHeight, 10, 1.0f, Theme.palette().outline());
        int textX = panelX + 12;
        int textY = panelY + 12;
        for (String line : wrapText(SWITCH_WARNING, panelWidth - 24)) {
            context.drawText(textRenderer, line, textX, textY, Theme.palette().text(), false);
            textY += textRenderer.fontHeight + 2;
        }
        for (Button button : switchWarningButtons) button.render(context, vMouseX, vMouseY, deltaTicks);
    }

    /** Simple greedy word-wrap; the warning text is short/static so this avoids a DrawContext dependency. */
    private List<String> wrapText(String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textRenderer.getWidth(candidate) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double vx = scaleAdjust.toVirtualX(click.x()), vy = scaleAdjust.toVirtualY(click.y());
        if (switchWarningVisible) {
            for (Button button : switchWarningButtons) if (button.mouseClicked(vx, vy, click.button())) return true;
            return true; // swallow clicks on the scrim while the modal warning is up
        }
        for (Button button : toolbar) if (button.mouseClicked(vx, vy, click.button())) return true;
        if (canvas.mouseClicked(vx, vy, click.button(), doubled)) return true;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        double vx = scaleAdjust.toVirtualX(click.x()), vy = scaleAdjust.toVirtualY(click.y());
        double factor = scaleAdjust.factor();
        if (canvas.mouseDragged(vx, vy, offsetX / factor, offsetY / factor)) return true;
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        double vx = scaleAdjust.toVirtualX(click.x()), vy = scaleAdjust.toVirtualY(click.y());
        if (canvas.mouseReleased(vx, vy, click.button())) return true;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        double vx = scaleAdjust.toVirtualX(mouseX), vy = scaleAdjust.toVirtualY(mouseY);
        if (canvas.mouseScrolled(vx, vy, verticalAmount)) return true;
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (input.isValidChar() && canvas.charTyped(input.asString())) return true;
        return super.charTyped(input);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.getKeycode() == GLFW.GLFW_KEY_BACKSPACE && canvas.backspace()) return true;
        if (input.getKeycode() == GLFW.GLFW_KEY_ESCAPE && canvas.escapePressed()) return true;
        return super.keyPressed(input);
    }

    private void newProject() {
        workspace = initialWorkspace(); canvas.workspace(workspace); setStatus("New project", false);
    }
    private void saveProject() {
        try { Path file = projects.save(PROJECT_NAME, workspace); setStatus("Saved " + file.getFileName(), false); }
        catch (IOException error) { setStatus("Save failed: " + error.getMessage(), true); }
    }
    private void loadProject() {
        try { workspace = projects.load(PROJECT_NAME); canvas.workspace(workspace); setStatus("Loaded " + PROJECT_NAME + ".glade", false); }
        catch (IOException error) { setStatus("Load failed: " + error.getMessage(), true); }
    }
    private void runProject() {
        try {
            String source = generator.generate(workspace);
            Path scripts = FabricLoader.getInstance().getGameDir().resolve("glade").resolve("scripts");
            Files.createDirectories(scripts);
            Files.writeString(scripts.resolve(BUILD_NAME + ".py"), source, StandardCharsets.UTF_8);
            setStatus("Running generated Python…", false);
            ScriptEngine.instance().run(BUILD_NAME, (level, text) -> setStatus(level + ": " + text, level.equals("error")))
                    .whenComplete((ignored, error) -> {
                        if (client == null) return;
                        client.execute(() -> {
                            if (error == null) {
                                setStatus("Script loaded successfully", false);
                                if (client.player != null) client.player.sendMessage(Text.literal("Glade block script loaded"), false);
                            } else {
                                Throwable cause = error.getCause() == null ? error : error.getCause();
                                setStatus("Run failed: " + cause.getMessage(), true);
                                if (client.player != null) client.player.sendMessage(Text.literal("Glade run failed: " + cause.getMessage()), false);
                            }
                        });
                    });
        } catch (IOException | RuntimeException error) { setStatus("Run failed: " + error.getMessage(), true); }
    }
    /** Item 8: block -> Python conversion is intentionally one-way; no code path here (or
     *  anywhere in this file) reads a .py file back into a Workspace. */
    private void requestSwitchToPython() { switchWarningVisible = true; }
    private void cancelSwitchToPython() { switchWarningVisible = false; }

    private void confirmSwitchToPython() {
        switchWarningVisible = false;
        try {
            String source = generator.generate(workspace);
            Path scripts = FabricLoader.getInstance().getGameDir().resolve("glade").resolve("scripts");
            Files.createDirectories(scripts);
            Files.writeString(scripts.resolve(SWITCH_SCRIPT_NAME + ".py"), source, StandardCharsets.UTF_8);
            setStatus("Converted to Python — load '" + SWITCH_SCRIPT_NAME + "' in the Python editor", false);
            if (client != null) client.setScreen(new PythonEditorScreen());
        } catch (IOException | RuntimeException error) {
            String message = "Switch to Python failed: " + error.getMessage();
            setStatus(message, true);
            if (client != null && client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(Text.literal(message));
            }
        }
    }

    private void setStatus(String text, boolean error) { status = text; statusError = error; }

    private static Workspace initialWorkspace() {
        Workspace result = new Workspace();
        BlockNode start = new BlockNode(BlockRegistry.get("on_script_start"), 35, 35);
        BlockNode tick = new BlockNode(BlockRegistry.get("on_tick"), 270, 35);
        result.add(start, true); result.add(tick, true);
        return result;
    }
}
