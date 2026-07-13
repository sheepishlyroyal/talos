package dev.glade.client.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.glade.client.GladeClient;
import dev.glade.client.task.GladeTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Input macros: record the player's real per-tick control state (all nine gameplay bindings,
 * look angles, hotbar slot) and replay it later. Replay presses logical {@link KeyBinding}s,
 * so rebound controls behave identically. Macros persist as JSON under ~/.glade/macros/.
 */
public final class MacroSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(MacroSystem.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final Path MACRO_DIR =
            Path.of(System.getProperty("user.home"), ".glade", "macros");
    private static final int MAX_RECORD_TICKS = 20 * 300; // five minutes

    /** One tick of input. Bit order matches {@link #bindings(GameOptions)}. */
    public record Frame(int keys, float yaw, float pitch, int slot) {}

    private static List<Frame> recording;
    private static String recordingName;

    private MacroSystem() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(MacroSystem::sampleTick);
    }

    private static KeyBinding[] bindings(GameOptions options) {
        return new KeyBinding[] {
                options.forwardKey, options.backKey, options.leftKey, options.rightKey,
                options.jumpKey, options.sneakKey, options.sprintKey,
                options.attackKey, options.useKey
        };
    }

    /* ------------------------------------------------------------------ recording */

    public static synchronized boolean startRecording(String name) {
        if (recording != null) return false;
        recording = new ArrayList<>();
        recordingName = name;
        return true;
    }

    public static synchronized int stopRecording() {
        if (recording == null) return -1;
        List<Frame> frames = recording;
        String name = recordingName;
        recording = null;
        recordingName = null;
        try {
            Files.createDirectories(MACRO_DIR);
            Files.writeString(file(name), GSON.toJson(frames));
        } catch (IOException exception) {
            LOGGER.warn("Could not save macro {}", name, exception);
            return -1;
        }
        return frames.size();
    }

    public static boolean isRecording() { return recording != null; }
    public static String recordingName() { return recordingName; }

    private static void sampleTick(MinecraftClient client) {
        List<Frame> target = recording;
        if (target == null || client.player == null) return;
        KeyBinding[] keys = bindings(client.options);
        int mask = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].isPressed()) mask |= 1 << i;
        }
        target.add(new Frame(mask, client.player.getYaw(), client.player.getPitch(),
                client.player.getInventory().getSelectedSlot()));
        if (target.size() >= MAX_RECORD_TICKS) {
            int frames = stopRecording();
            client.player.sendMessage(Text.literal(
                    "§bGlade §7» §fmacro recording auto-stopped at " + frames + " ticks"), false);
        }
    }

    /* ------------------------------------------------------------------ storage */

    public static List<String> list() {
        if (!Files.isDirectory(MACRO_DIR)) return List.of();
        try (Stream<Path> paths = Files.list(MACRO_DIR)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted().toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    public static boolean delete(String name) {
        try {
            return Files.deleteIfExists(file(name));
        } catch (IOException exception) {
            return false;
        }
    }

    public static List<Frame> loadFrames(String name) {
        try {
            Frame[] frames = GSON.fromJson(Files.readString(file(name)), Frame[].class);
            return frames == null ? List.of() : List.of(frames);
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }

    private static Path file(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");
        return MACRO_DIR.resolve(safe + ".json");
    }

    /* ------------------------------------------------------------------ replay */

    public static boolean play(MinecraftClient client, String name, int repeats) {
        List<Frame> frames = loadFrames(name);
        if (frames.isEmpty()) return false;
        GladeClient.taskScheduler().addTask("glade-macro-" + name,
                new PlayTask(client, name, frames, repeats));
        return true;
    }

    private static final class PlayTask extends GladeTask {
        private final MinecraftClient client;
        private final String name;
        private final List<Frame> frames;
        private final int repeats;
        private int index;
        private int loop;
        private int previousMask;
        private boolean done;

        PlayTask(MinecraftClient client, String name, List<Frame> frames, int repeats) {
            this.client = client;
            this.name = name;
            this.frames = frames;
            this.repeats = Math.max(1, repeats);
        }

        @Override public void initialize() { }
        @Override public boolean condition() { return !done && client.player != null; }
        @Override public void increment() { }

        @Override public void body() {
            if (index >= frames.size()) {
                index = 0;
                if (++loop >= repeats) { finish(); return; }
            }
            Frame frame = frames.get(index++);
            KeyBinding[] keys = bindings(client.options);
            for (int i = 0; i < keys.length; i++) {
                boolean pressed = (frame.keys() & (1 << i)) != 0;
                keys[i].setPressed(pressed);
                // A rising edge is a fresh press event so single attack/use clicks replay.
                if (pressed && (previousMask & (1 << i)) == 0) {
                    KeyBinding.onKeyPressed(
                            net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
                                    .getBoundKeyOf(keys[i]));
                }
            }
            previousMask = frame.keys();
            client.player.setYaw(frame.yaw());
            client.player.setHeadYaw(frame.yaw());
            client.player.setBodyYaw(frame.yaw());
            client.player.setPitch(frame.pitch());
            client.player.getInventory().setSelectedSlot(frame.slot());
            scheduleDelay();
        }

        private void finish() {
            done = true;
            release();
            if (client.player != null) client.player.sendMessage(
                    Text.literal("§bGlade §7» §fmacro '" + name + "' finished"), true);
        }

        private void release() {
            for (KeyBinding key : bindings(client.options)) key.setPressed(false);
        }

        @Override public void onCompleted() { release(); }
        @Override public Set<Object> getMutexKeys() { return Set.of("glade-player-movement"); }
    }
}
