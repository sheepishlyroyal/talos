package dev.glade.client.blockeditor.screen;

import dev.glade.client.blockeditor.canvas.BlockCanvas;
import dev.glade.client.blockeditor.codegen.PythonCodeGenerator;
import dev.glade.client.blockeditor.file.GladeProjectFile;
import dev.glade.client.blockeditor.model.BlockNode;
import dev.glade.client.blockeditor.model.Workspace;
import dev.glade.client.blockeditor.registry.BlockRegistry;
import dev.glade.client.script.ScriptEngine;
import dev.glade.client.ui.draw.GladeUi;
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
    private final GladeProjectFile projects = new GladeProjectFile();
    private final PythonCodeGenerator generator = new PythonCodeGenerator();
    private Workspace workspace = initialWorkspace();
    private final BlockCanvas canvas = new BlockCanvas(workspace);
    private List<Button> toolbar = List.of();
    private volatile String status = "Ready — project: " + PROJECT_NAME + ".glade";
    private volatile boolean statusError;

    public BlockEditorScreen() { super(Text.literal("Glade Block Editor")); }

    @Override
    protected void init() {
        int buttonY = 9;
        toolbar = List.of(
                new Button(12, buttonY, 54, 20, Text.literal("New"), this::newProject),
                new Button(72, buttonY, 54, 20, Text.literal("Save"), this::saveProject),
                new Button(132, buttonY, 54, 20, Text.literal("Load"), this::loadProject),
                new Button(192, buttonY, 54, 20, Text.literal("Run"), this::runProject));
        canvas.bounds(8, 38, Math.max(100, width - 16), Math.max(80, height - 46));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.fill(0, 0, width, height, Theme.palette().bg());
        GladeUi.glassPanel(context, 6, 5, width - 12, 29, 10, Theme.palette().panelHeader());
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, Theme.palette().text());
        for (Button button : toolbar) button.render(context, mouseX, mouseY, deltaTicks);
        int statusWidth = textRenderer.getWidth(status);
        context.drawText(textRenderer, status, Math.max(252, width - statusWidth - 14), 12,
                statusError ? 0xFFFF7777 : Theme.palette().description(), false);
        canvas.render(context, mouseX, mouseY, deltaTicks);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        for (Button button : toolbar) if (button.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (canvas.mouseClicked(click.x(), click.y(), click.button(), doubled)) return true;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (canvas.mouseDragged(click.x(), click.y(), offsetX, offsetY)) return true;
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (canvas.mouseReleased(click.x(), click.y(), click.button())) return true;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (canvas.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
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
    private void setStatus(String text, boolean error) { status = text; statusError = error; }

    private static Workspace initialWorkspace() {
        Workspace result = new Workspace();
        BlockNode start = new BlockNode(BlockRegistry.get("on_script_start"), 35, 35);
        BlockNode tick = new BlockNode(BlockRegistry.get("on_tick"), 270, 35);
        result.add(start, true); result.add(tick, true);
        return result;
    }
}
