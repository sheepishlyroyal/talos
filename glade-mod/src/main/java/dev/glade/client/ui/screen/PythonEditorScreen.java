package dev.glade.client.ui.screen;

import dev.glade.client.script.ScriptEngine;
import dev.glade.client.ui.draw.GladeUi;
import dev.glade.client.ui.theme.Spacing;
import dev.glade.client.ui.theme.Theme;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Native in-game Python editor opened by {@code /glade script editor}: a scrollable
 * multi-line source buffer, a script-name field, and Run/Stop/Save/Load buttons wired
 * straight into {@link ScriptEngine}.
 *
 * <p>Uses vanilla {@link EditBoxWidget}/{@link ButtonWidget} via
 * {@link #addDrawableChild} rather than Glade's hand-rolled {@code Widget} framework
 * (see {@link GladeScreen}'s class doc) — a real text editor needs vanilla's caret,
 * selection, scroll and clipboard handling, which that lightweight framework doesn't
 * implement. Panel chrome still goes through {@link GladeUi} for visual consistency.
 *
 * <p>Run/Stop only ever touch {@link ScriptEngine}'s public, thread-safe entry points
 * (never the GraalPy {@code Context} directly): Run saves the buffer to
 * {@code glade/scripts/<name>.py} and calls {@link ScriptEngine#run(String)}, which now
 * defaults to {@link ScriptEngine#CHAT} so output streams into the chat HUD; Stop calls
 * {@link ScriptEngine#stop()}, which cancels the worker thread's {@code Context} from
 * whatever thread calls it — safe to invoke directly from this screen's render thread.
 */
public final class PythonEditorScreen extends net.minecraft.client.gui.screen.Screen {
    private static final int HEADER_HEIGHT = 34;
    private static final String DEFAULT_TEMPLATE = """
            import glade

            glade.log("hi")
            """;

    /** Remembered across screen instances so reopening the editor keeps the last script name. */
    private static String lastScriptName = "editor";

    private EditBoxWidget sourceEditor;
    private TextFieldWidget nameField;
    private ButtonWidget runButton;
    private ButtonWidget stopButton;
    private volatile String status = "Ready";
    private volatile boolean statusError;

    public PythonEditorScreen() {
        super(Text.literal("Glade Python Editor"));
    }

    @Override
    protected void init() {
        this.nameField = new TextFieldWidget(this.textRenderer,
                Spacing.S8, HEADER_HEIGHT - 24, 140, 18, Text.literal("Script name"));
        this.nameField.setMaxLength(64);
        this.nameField.setText(lastScriptName);
        this.nameField.setChangedListener(text -> lastScriptName = text);
        this.addDrawableChild(this.nameField);

        int buttonWidth = 54;
        int buttonY = HEADER_HEIGHT - 24;
        int x = Spacing.S8 + 150;
        this.runButton = ButtonWidget.builder(Text.literal("Run"), button -> runScript())
                .dimensions(x, buttonY, buttonWidth, 18).build();
        this.addDrawableChild(this.runButton);
        x += buttonWidth + 6;
        this.stopButton = ButtonWidget.builder(Text.literal("Stop"), button -> stopScript())
                .dimensions(x, buttonY, buttonWidth, 18).build();
        this.addDrawableChild(this.stopButton);
        x += buttonWidth + 6;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveScript(true))
                .dimensions(x, buttonY, buttonWidth, 18).build());
        x += buttonWidth + 6;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Load"), button -> loadScript(true))
                .dimensions(x, buttonY, buttonWidth, 18).build());
        x += buttonWidth + 6;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(x, buttonY, buttonWidth, 18).build());

        this.sourceEditor = EditBoxWidget.builder()
                .x(Spacing.S8)
                .y(HEADER_HEIGHT + Spacing.S8)
                .hasBackground(true)
                .hasOverlay(true)
                .textColor(Theme.palette().text())
                .cursorColor(Theme.palette().accent())
                .placeholder(Text.literal("import glade\n\nglade.log(\"hi\")"))
                .build(this.textRenderer,
                        Math.max(100, this.width - Spacing.S16),
                        Math.max(60, this.height - HEADER_HEIGHT - Spacing.S16 - Spacing.S16),
                        Text.literal("Python source"));
        this.sourceEditor.setMaxLength(1_000_000);
        this.addDrawableChild(this.sourceEditor);

        loadScript(false);
        this.setInitialFocus(this.sourceEditor);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        context.fill(0, 0, this.width, this.height, Theme.palette().bg());
        GladeUi.glassPanel(context, 4, 4, this.width - 8, HEADER_HEIGHT - 8, 8, Theme.palette().panelHeader());
        super.render(context, mouseX, mouseY, deltaTicks);

        int statusWidth = this.textRenderer.getWidth(status);
        context.drawText(this.textRenderer, status,
                Math.max(this.width - statusWidth - Spacing.S8, Spacing.S8), 12,
                statusError ? 0xFFFF7777 : Theme.palette().description(), false);

        boolean running = ScriptEngine.instance().state() == ScriptEngine.State.RUNNING;
        this.runButton.active = !running;
        this.stopButton.active = running;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private String scriptName() {
        String raw = this.nameField.getText().trim();
        return raw.isEmpty() ? "editor" : raw;
    }

    private Path scriptsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("glade").resolve("scripts");
    }

    private Path scriptFile(String name) {
        return scriptsDir().resolve(name.endsWith(".py") ? name : name + ".py");
    }

    private void saveScript(boolean announce) {
        try {
            Path dir = scriptsDir();
            Files.createDirectories(dir);
            Files.writeString(scriptFile(scriptName()), this.sourceEditor.getText(), StandardCharsets.UTF_8);
            if (announce) setStatus("Saved " + scriptName() + ".py", false);
        } catch (IOException error) {
            setStatus("Save failed: " + error.getMessage(), true);
        }
    }

    private void loadScript(boolean announce) {
        Path file = scriptFile(scriptName());
        try {
            if (Files.isRegularFile(file)) {
                this.sourceEditor.setText(Files.readString(file, StandardCharsets.UTF_8));
                if (announce) setStatus("Loaded " + scriptName() + ".py", false);
            } else if (announce) {
                setStatus("No saved script named " + scriptName() + ".py yet", true);
            } else {
                this.sourceEditor.setText(DEFAULT_TEMPLATE);
            }
        } catch (IOException error) {
            setStatus("Load failed: " + error.getMessage(), true);
        }
    }

    private void runScript() {
        saveScript(false);
        String name = scriptName();
        setStatus("Running " + name + "...", false);
        ScriptEngine.instance().run(name).whenComplete((ignored, error) -> {
            if (this.client == null) return;
            this.client.execute(() -> {
                if (error == null) setStatus("Script finished: " + name, false);
                else setStatus("Script failed: " + describe(error), true);
            });
        });
    }

    private void stopScript() {
        ScriptEngine.instance().stop();
        setStatus("Stopped script engine", false);
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    private void setStatus(String text, boolean error) {
        this.status = text;
        this.statusError = error;
    }
}
