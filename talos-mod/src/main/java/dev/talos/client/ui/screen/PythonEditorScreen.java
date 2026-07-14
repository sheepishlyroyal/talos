package dev.talos.client.ui.screen;

import dev.talos.client.script.ScriptEngine;
import dev.talos.client.ui.draw.TalosUi;
import dev.talos.client.ui.theme.Spacing;
import dev.talos.client.ui.theme.Theme;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/**
 * Native in-game Python editor opened by {@code /talos script editor}: a scrollable
 * multi-line source buffer, a script-name field, and Run/Stop/Save/Load buttons wired
 * straight into {@link ScriptEngine}.
 *
 * <p>Uses vanilla {@link MultiLineEditBox}/{@link Button} via
 * {@link #addRenderableWidget} rather than Talos's hand-rolled {@code Widget} framework
 * (see {@link TalosScreen}'s class doc) — a real text editor needs vanilla's caret,
 * selection, scroll and clipboard handling, which that lightweight framework doesn't
 * implement. Panel chrome still goes through {@link TalosUi} for visual consistency.
 *
 * <p>Run/Stop only ever touch {@link ScriptEngine}'s public, thread-safe entry points
 * (never the GraalPy {@code Context} directly): Run saves the buffer to
 * {@code talos/scripts/<name>.py} and calls {@link ScriptEngine#run(String)}, which now
 * defaults to {@link ScriptEngine#CHAT} so output streams into the chat HUD; Stop calls
 * {@link ScriptEngine#stop()}, which cancels the worker thread's {@code Context} from
 * whatever thread calls it — safe to invoke directly from this screen's render thread.
 */
public final class PythonEditorScreen extends net.minecraft.client.gui.screens.Screen {
    private static final int HEADER_HEIGHT = 34;
    private static final String DEFAULT_TEMPLATE = """
            import talos

            talos.log("hi")
            """;

    /** Remembered across screen instances so reopening the editor keeps the last script name. */
    private static String lastScriptName = "editor";

    private MultiLineEditBox sourceEditor;
    private EditBox nameField;
    private Button runButton;
    private Button stopButton;
    private volatile String status = "Ready";
    private volatile boolean statusError;

    /** Set by the preload constructor to seed the buffer instead of loading from disk. */
    private String pendingName;
    private String pendingSource;

    public PythonEditorScreen() {
        super(Component.literal("Talos Python Editor"));
    }

    /** Opens the editor preloaded with source — used by the block editor's one-way "Switch to Python". */
    public PythonEditorScreen(String initialName, String initialSource) {
        this();
        this.pendingName = initialName;
        this.pendingSource = initialSource;
    }

    @Override
    protected void init() {
        this.nameField = new EditBox(this.font,
                Spacing.S8, HEADER_HEIGHT - 24, 140, 18, Component.literal("Script name"));
        this.nameField.setMaxLength(64);
        this.nameField.setValue(lastScriptName);
        this.nameField.setResponder(text -> lastScriptName = text);
        this.addRenderableWidget(this.nameField);

        int buttonWidth = 54;
        int buttonY = HEADER_HEIGHT - 24;
        int x = Spacing.S8 + 150;
        this.runButton = Button.builder(Component.literal("Run"), button -> runScript())
                .bounds(x, buttonY, buttonWidth, 18).build();
        this.addRenderableWidget(this.runButton);
        x += buttonWidth + 6;
        this.stopButton = Button.builder(Component.literal("Stop"), button -> stopScript())
                .bounds(x, buttonY, buttonWidth, 18).build();
        this.addRenderableWidget(this.stopButton);
        x += buttonWidth + 6;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> saveScript(true))
                .bounds(x, buttonY, buttonWidth, 18).build());
        x += buttonWidth + 6;
        this.addRenderableWidget(Button.builder(Component.literal("Load"), button -> loadScript(true))
                .bounds(x, buttonY, buttonWidth, 18).build());
        x += buttonWidth + 6;
        this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(x, buttonY, buttonWidth, 18).build());

        this.sourceEditor = MultiLineEditBox.builder()
                .setX(Spacing.S8)
                .setY(HEADER_HEIGHT + Spacing.S8)
                .setShowBackground(true)
                .setShowDecorations(true)
                .setTextColor(Theme.palette().text())
                .setCursorColor(Theme.palette().accent())
                .setPlaceholder(Component.literal("import talos\n\ntalos.log(\"hi\")"))
                .build(this.font,
                        Math.max(100, this.width - Spacing.S16),
                        Math.max(60, this.height - HEADER_HEIGHT - Spacing.S16 - Spacing.S16),
                        Component.literal("Python source"));
        this.sourceEditor.setCharacterLimit(1_000_000);
        this.addRenderableWidget(this.sourceEditor);

        if (this.pendingSource != null) {
            if (this.pendingName != null && !this.pendingName.isBlank()) {
                lastScriptName = this.pendingName;
                this.nameField.setValue(this.pendingName);
            }
            this.sourceEditor.setValue(this.pendingSource);
            setStatus("Converted from blocks (one-way) — Save to keep this script", false);
        } else {
            loadScript(false);
        }
        this.setInitialFocus(this.sourceEditor);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        context.fill(0, 0, this.width, this.height, Theme.palette().bg());
        TalosUi.glassPanel(context, 4, 4, this.width - 8, HEADER_HEIGHT - 8, 8, Theme.palette().panelHeader());
        super.extractRenderState(context, mouseX, mouseY, deltaTicks);

        int statusWidth = this.font.width(status);
        context.text(this.font, status,
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
        String raw = this.nameField.getValue().trim();
        return raw.isEmpty() ? "editor" : raw;
    }

    private Path scriptsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("talos").resolve("scripts");
    }

    private Path scriptFile(String name) {
        return scriptsDir().resolve(name.endsWith(".py") ? name : name + ".py");
    }

    private void saveScript(boolean announce) {
        try {
            Path dir = scriptsDir();
            Files.createDirectories(dir);
            Files.writeString(scriptFile(scriptName()), this.sourceEditor.getValue(), StandardCharsets.UTF_8);
            if (announce) setStatus("Saved " + scriptName() + ".py", false);
        } catch (IOException error) {
            setStatus("Save failed: " + error.getMessage(), true);
        }
    }

    private void loadScript(boolean announce) {
        Path file = scriptFile(scriptName());
        try {
            if (Files.isRegularFile(file)) {
                this.sourceEditor.setValue(Files.readString(file, StandardCharsets.UTF_8));
                if (announce) setStatus("Loaded " + scriptName() + ".py", false);
            } else if (announce) {
                setStatus("No saved script named " + scriptName() + ".py yet", true);
            } else {
                this.sourceEditor.setValue(DEFAULT_TEMPLATE);
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
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
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
