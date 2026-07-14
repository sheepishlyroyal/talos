package dev.talos.client.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.talos.client.TalosClient;
import dev.talos.client.task.TalosTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Input macros: record the player's real per-tick control state (all nine gameplay bindings,
 * look angles, hotbar slot) and replay it later. Replay presses logical {@link KeyMapping}s,
 * so rebound controls behave identically. Macros persist as JSON under ~/.talos/macros/.
 */
public final class MacroSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(MacroSystem.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final Path MACRO_DIR =
            Path.of(System.getProperty("user.home"), ".talos", "macros");
    private static final int MAX_RECORD_TICKS = 20 * 300; // five minutes

    /** One tick of input. Bit order matches {@link #bindings(Options)}. */
    public record Frame(int keys, float yaw, float pitch, int slot) {}

    /** Saved macro: which channels were recorded plus the per-tick frames. */
    public record MacroData(int channels, List<Frame> frames) {}

    /*
     * Channels — record or replay any combination. A replay only touches its channels: a
     * clicks-only macro leaves WASD to the player (or to /talos goto) while it clicks.
     */
    public static final int CH_MOVE = 1;      // WASD
    public static final int CH_JUMP = 2;
    public static final int CH_SNEAK = 4;
    public static final int CH_SPRINT = 8;
    public static final int CH_CLICKS = 16;   // attack + use
    public static final int CH_LOOK = 32;     // yaw + pitch
    public static final int CH_HOTBAR = 64;   // selected slot
    public static final int CH_ALL = 127;

    /** Parses "clicks+look", "move", "all", "keys" (all keyboard), "input" (keys+clicks). */
    public static int parseChannels(String spec) {
        int mask = 0;
        for (String part : spec.toLowerCase(java.util.Locale.ROOT).split("\\+")) {
            mask |= switch (part.trim()) {
                case "move", "wasd", "movement" -> CH_MOVE;
                case "jump" -> CH_JUMP;
                case "sneak" -> CH_SNEAK;
                case "sprint" -> CH_SPRINT;
                case "clicks", "click", "mouse-buttons" -> CH_CLICKS;
                case "look", "turn", "mouse", "camera" -> CH_LOOK;
                case "hotbar", "slots" -> CH_HOTBAR;
                case "keys", "keyboard" -> CH_MOVE | CH_JUMP | CH_SNEAK | CH_SPRINT;
                case "input" -> CH_MOVE | CH_JUMP | CH_SNEAK | CH_SPRINT | CH_CLICKS;
                case "all", "" -> CH_ALL;
                default -> -1;
            };
            if (mask < 0) return -1;
        }
        return mask == 0 ? CH_ALL : mask;
    }

    public static String channelNames(int mask) {
        if ((mask & CH_ALL) == CH_ALL) return "all";
        List<String> names = new ArrayList<>();
        if ((mask & CH_MOVE) != 0) names.add("move");
        if ((mask & CH_JUMP) != 0) names.add("jump");
        if ((mask & CH_SNEAK) != 0) names.add("sneak");
        if ((mask & CH_SPRINT) != 0) names.add("sprint");
        if ((mask & CH_CLICKS) != 0) names.add("clicks");
        if ((mask & CH_LOOK) != 0) names.add("look");
        if ((mask & CH_HOTBAR) != 0) names.add("hotbar");
        return String.join("+", names);
    }

    /** Channel owning each index of {@link #bindings(Options)}. */
    private static final int[] KEY_CHANNEL = {
            CH_MOVE, CH_MOVE, CH_MOVE, CH_MOVE, CH_JUMP, CH_SNEAK, CH_SPRINT,
            CH_CLICKS, CH_CLICKS
    };

    private static List<Frame> recording;
    private static String recordingName;
    private static int recordingChannels = CH_ALL;

    private MacroSystem() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(MacroSystem::sampleTick);
    }

    private static KeyMapping[] bindings(Options options) {
        return new KeyMapping[] {
                options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                options.keyJump, options.keyShift, options.keySprint,
                options.keyAttack, options.keyUse
        };
    }

    /* ------------------------------------------------------------------ recording */

    public static synchronized boolean startRecording(String name, int channels) {
        if (recording != null) return false;
        recording = new ArrayList<>();
        recordingName = name;
        recordingChannels = channels;
        return true;
    }

    public static synchronized int stopRecording() {
        if (recording == null) return -1;
        List<Frame> frames = recording;
        String name = recordingName;
        int channels = recordingChannels;
        recording = null;
        recordingName = null;
        try {
            Files.createDirectories(MACRO_DIR);
            Files.writeString(file(name), GSON.toJson(new MacroData(channels, frames)));
        } catch (IOException exception) {
            LOGGER.warn("Could not save macro {}", name, exception);
            return -1;
        }
        return frames.size();
    }

    public static boolean isRecording() { return recording != null; }
    public static String recordingName() { return recordingName; }

    private static void sampleTick(Minecraft client) {
        List<Frame> target = recording;
        if (target == null || client.player == null) return;
        KeyMapping[] keys = bindings(client.options);
        int mask = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].isDown()) mask |= 1 << i;
        }
        target.add(new Frame(mask, client.player.getYRot(), client.player.getXRot(),
                client.player.getInventory().getSelectedSlot()));
        if (target.size() >= MAX_RECORD_TICKS) {
            int frames = stopRecording();
            client.player.sendSystemMessage(Component.literal(
                    "§bTalos §7» §fmacro recording auto-stopped at " + frames + " ticks"));
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

    public static MacroData load(String name) {
        try {
            String json = Files.readString(file(name));
            if (json.stripLeading().startsWith("[")) {
                // Legacy channel-less format: a bare frame array means every channel.
                Frame[] frames = GSON.fromJson(json, Frame[].class);
                return frames == null ? null : new MacroData(CH_ALL, List.of(frames));
            }
            MacroData data = GSON.fromJson(json, MacroData.class);
            return data == null || data.frames() == null ? null : data;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static Path file(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");
        return MACRO_DIR.resolve(safe + ".json");
    }

    /* ------------------------------------------------------------------ replay */

    /** channelOverride limits playback further; 0 means play the recorded channels. */
    public static boolean play(Minecraft client, String name, int repeats,
            int channelOverride) {
        MacroData data = load(name);
        if (data == null || data.frames().isEmpty()) return false;
        int channels = channelOverride == 0 ? data.channels()
                : data.channels() & channelOverride;
        if (channels == 0) return false;
        TalosClient.taskScheduler().addTask("talos-macro-" + name,
                new PlayTask(client, name, data.frames(), repeats, channels));
        return true;
    }

    private static final class PlayTask extends TalosTask {
        private final Minecraft client;
        private final String name;
        private final List<Frame> frames;
        private final int repeats;
        private final int channels;
        private int index;
        private int loop;
        private int previousMask;
        private boolean done;

        PlayTask(Minecraft client, String name, List<Frame> frames, int repeats,
                int channels) {
            this.client = client;
            this.name = name;
            this.frames = frames;
            this.repeats = Math.max(1, repeats);
            this.channels = channels;
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
            KeyMapping[] keys = bindings(client.options);
            for (int i = 0; i < keys.length; i++) {
                // Keys outside the played channels are left untouched, so a clicks-only
                // macro can run WHILE the player (or the pathfinder) drives movement.
                if ((KEY_CHANNEL[i] & channels) == 0) continue;
                boolean pressed = (frame.keys() & (1 << i)) != 0;
                keys[i].setDown(pressed);
                // A rising edge is a fresh press event so single attack/use clicks replay.
                if (pressed && (previousMask & (1 << i)) == 0) {
                    KeyMapping.click(
                            net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
                                    .getBoundKeyOf(keys[i]));
                }
            }
            previousMask = frame.keys();
            if ((channels & CH_LOOK) != 0) {
                client.player.setYRot(frame.yaw());
                client.player.setYHeadRot(frame.yaw());
                client.player.setYBodyRot(frame.yaw());
                client.player.setXRot(frame.pitch());
            }
            if ((channels & CH_HOTBAR) != 0) {
                client.player.getInventory().setSelectedSlot(frame.slot());
            }
            scheduleDelay();
        }

        private void finish() {
            done = true;
            release();
            if (client.player != null) client.player.sendOverlayMessage(
                    Component.literal("§bTalos §7» §fmacro '" + name + "' finished"));
        }

        private void release() {
            KeyMapping[] keys = bindings(client.options);
            for (int i = 0; i < keys.length; i++) {
                if ((KEY_CHANNEL[i] & channels) != 0) keys[i].setDown(false);
            }
        }

        @Override public void onCompleted() { release(); }

        @Override public Set<Object> getMutexKeys() {
            // Only movement-channel macros contend with pathing; a clicks/look macro
            // composes with /talos goto instead of cancelling it.
            boolean movement = (channels & (CH_MOVE | CH_JUMP | CH_SNEAK | CH_SPRINT)) != 0;
            return movement ? Set.of("talos-player-movement") : Set.of("talos-macro-aux");
        }
    }
}
