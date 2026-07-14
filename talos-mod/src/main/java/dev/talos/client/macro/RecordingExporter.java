package dev.talos.client.macro;

import dev.talos.client.macro.MacroSystem.Frame;
import dev.talos.client.macro.MacroSystem.MacroData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Converts a recorded {@link MacroSystem} macro into a runnable Talos Python script with
 * preserved timing, written to {@code <gameDir>/talos/scripts/} — the exact directory
 * {@code ScriptEngine.run(name)} loads from, so the export is immediately runnable via
 * {@code /talos script run <name>}.
 *
 * <p>Every recorded channel exports as CONTINUOUS input on the raw script primitives: a
 * key going down emits {@code talos.key("<name>", True)}, the elapsed hold is expressed
 * through {@code talos.wait(<seconds>)} (1 tick = 0.05 s), and the release emits
 * {@code talos.release_keys("<name>")}. The body is an EVENT walk over the frames: one
 * wait between consecutive event ticks, shared by every event on the same tick, so
 * overlapping holds on different keys interleave correctly. Look changes beyond a small
 * epsilon replay as {@code talos.look(yaw, pitch)}, hotbar changes as
 * {@code talos.select_slot(n)}. Single-tick attack/use presses stay
 * {@code talos.left_click()}/{@code talos.right_click()}; multi-tick holds become
 * {@code talos.key("attack"/"use", True)} … {@code talos.release_keys(...)}.
 */
public final class RecordingExporter {
    /** Logical key names accepted by {@code talos.key()}, indexed by the bit order of
     *  {@code MacroSystem.bindings(...)}: forward, back, left, right, jump, sneak,
     *  sprint, attack, use. */
    private static final String[] KEY_NAMES = {
            "forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use"
    };

    /** Channel owning each key bit; mirrors MacroSystem's bit → channel layout. */
    private static final int[] KEY_CHANNEL = {
            MacroSystem.CH_MOVE, MacroSystem.CH_MOVE, MacroSystem.CH_MOVE, MacroSystem.CH_MOVE,
            MacroSystem.CH_JUMP, MacroSystem.CH_SNEAK, MacroSystem.CH_SPRINT,
            MacroSystem.CH_CLICKS, MacroSystem.CH_CLICKS
    };

    private static final int BIT_ATTACK = 7;
    private static final int BIT_USE = 8;

    /**
     * Minimum yaw/pitch change that re-emits {@code talos.look(...)}. Deliberately at
     * float-noise level, NOT a smoothing threshold: replay must reproduce the recorded
     * per-tick view EXACTLY. Per-tick is the only resolution any observer (the server,
     * other players) can ever see, so a raw look() each changed tick — no humanized
     * easing, no interpolation — replays the person's real mouse movement perfectly,
     * natural speed variation included, because it IS their data.
     */
    private static final float LOOK_EPSILON = 0.005F;

    private RecordingExporter() {}

    /** Exports macro {@code name}; returns the written script path. */
    public static Path export(String name) throws IOException {
        MacroData data = MacroSystem.load(name);
        if (data == null || data.frames().isEmpty())
            throw new IOException("No macro '" + name + "'");

        List<String> body = emitBody(data);
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");
        StringBuilder script = new StringBuilder();
        script.append("import talos\n\n");
        script.append("# Exported from macro '").append(safe).append("' (")
                .append(data.frames().size()).append(" ticks, channels: ")
                .append(MacroSystem.channelNames(data.channels())).append(")\n");
        script.append("# Continuous input replay on the raw primitives: talos.key(name, True)\n");
        script.append("# holds a key, talos.wait(seconds) preserves the recorded timing between\n");
        script.append("# events (1 tick = 0.05s), talos.release_keys(name) lets go, and\n");
        script.append("# talos.look(yaw, pitch) / talos.select_slot(n) replay view and hotbar.\n\n");
        script.append("@talos.on_start\ndef replay():\n");
        if (body.isEmpty()) {
            script.append("    pass  # nothing was recorded on these channels\n");
        } else {
            for (String line : body) script.append("    ").append(line).append("\n");
        }

        Path scripts = FabricLoader.getInstance().getGameDir().resolve("talos").resolve("scripts");
        Files.createDirectories(scripts);
        Path file = scripts.resolve(safe + ".py");
        Files.writeString(file, script.toString());
        return file;
    }

    /**
     * Tick-by-tick event walk. Events are key edges, single-tick clicks, look changes and
     * hotbar changes; a single {@code talos.wait} covers the gap since the previous event
     * tick, so every event on one tick shares it and parallel holds stay aligned.
     */
    private static List<String> emitBody(MacroData data) {
        List<Frame> frames = data.frames();
        int channels = data.channels();
        boolean look = (channels & MacroSystem.CH_LOOK) != 0;
        boolean hotbar = (channels & MacroSystem.CH_HOTBAR) != 0;

        List<String> lines = new ArrayList<>();
        int lastEventTick = 0;
        int previousKeys = 0;
        int previousSlot = -1;
        boolean lookEmitted = false;
        float lastYaw = 0.0F;
        float lastPitch = 0.0F;
        // Attack/use holds we chose to express as key(...)/release_keys(...) rather than
        // a click; falling edges only emit a release when the rising edge emitted a hold.
        boolean[] holding = new boolean[KEY_NAMES.length];

        for (int tick = 0; tick < frames.size(); tick++) {
            Frame frame = frames.get(tick);
            List<String> events = new ArrayList<>();

            if (hotbar && frame.slot() != previousSlot) {
                events.add("talos.select_slot(" + frame.slot() + ")");
                previousSlot = frame.slot();
            }

            // Look is recorded once per tick, so one-per-tick rate limiting is inherent;
            // only changes beyond the epsilon re-emit (the first frame sets the view).
            if (look && (!lookEmitted
                    || Math.abs(wrapDegrees(frame.yaw() - lastYaw)) > LOOK_EPSILON
                    || Math.abs(frame.pitch() - lastPitch) > LOOK_EPSILON)) {
                events.add(String.format(Locale.ROOT, "talos.look(%.3f, %.3f)",
                        frame.yaw(), frame.pitch()));
                lastYaw = frame.yaw();
                lastPitch = frame.pitch();
                lookEmitted = true;
            }

            for (int bit = 0; bit < KEY_NAMES.length; bit++) {
                if ((KEY_CHANNEL[bit] & channels) == 0) continue;
                boolean now = (frame.keys() & (1 << bit)) != 0;
                boolean was = (previousKeys & (1 << bit)) != 0;
                if (now && !was) {
                    // Rising edge. Single-tick attack/use presses replay as one clean
                    // click; everything else (and multi-tick holds) as a timed hold.
                    boolean heldNextTick = tick + 1 < frames.size()
                            && (frames.get(tick + 1).keys() & (1 << bit)) != 0;
                    if (bit == BIT_ATTACK && !heldNextTick) {
                        events.add("talos.left_click()");
                    } else if (bit == BIT_USE && !heldNextTick) {
                        events.add("talos.right_click()");
                    } else {
                        events.add("talos.key(\"" + KEY_NAMES[bit] + "\", True)");
                        holding[bit] = true;
                    }
                } else if (!now && was && holding[bit]) {
                    events.add("talos.release_keys(\"" + KEY_NAMES[bit] + "\")");
                    holding[bit] = false;
                }
            }

            previousKeys = frame.keys();
            if (events.isEmpty()) continue;
            addWait(lines, tick - lastEventTick);
            lastEventTick = tick;
            lines.addAll(events);
        }

        // Keys still held when the recording stopped release after their remaining hold.
        List<String> trailing = new ArrayList<>();
        for (int bit = 0; bit < KEY_NAMES.length; bit++) {
            if (holding[bit]) trailing.add("talos.release_keys(\"" + KEY_NAMES[bit] + "\")");
        }
        if (!trailing.isEmpty()) {
            addWait(lines, frames.size() - lastEventTick);
            lines.addAll(trailing);
        }
        return lines;
    }

    /** One shared wait between event ticks; same-tick events emit no wait between them. */
    private static void addWait(List<String> lines, int ticks) {
        if (ticks <= 0) return;
        lines.add(String.format(Locale.ROOT, "talos.wait(%.2f)", ticks * 0.05));
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }
}
