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
 * Converts a recorded {@link MacroSystem} macro into a runnable Talos Python script of
 * relative commands with preserved timing, written to {@code <gameDir>/talos/scripts/}
 * — the exact directory {@code ScriptEngine.run(name)} loads from, so the export is
 * immediately runnable via {@code /talos script run <name>}.
 *
 * <p>Only channels expressible through the public script API are exported: clicks
 * (attack → {@code talos.left_click()}, use → {@code talos.right_click()}) and hotbar
 * changes ({@code talos.select_slot(n)}). Movement, look, jump, sneak and sprint have
 * no raw-input script API, so those channels are noted in the header and skipped —
 * replay them natively with {@code /talos macro play} instead.
 */
public final class RecordingExporter {
    /** Bit indices matching {@code MacroSystem.bindings(...)} order. */
    private static final int BIT_ATTACK = 1 << 7;
    private static final int BIT_USE = 1 << 8;

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
        int unsupported = data.channels()
                & (MacroSystem.CH_MOVE | MacroSystem.CH_JUMP | MacroSystem.CH_SNEAK
                        | MacroSystem.CH_SPRINT | MacroSystem.CH_LOOK);
        if (unsupported != 0) {
            script.append("# Skipped channels with no script API equivalent: ")
                    .append(MacroSystem.channelNames(unsupported))
                    .append(" — use /talos macro play for those.\n");
        }
        script.append("# Held clicks replay as single clicks; timing uses 1 tick = 0.05s.\n\n");
        script.append("@talos.on_start\ndef replay():\n");
        if (body.isEmpty()) {
            script.append("    pass  # nothing exportable was recorded on these channels\n");
        } else {
            for (String line : body) script.append("    ").append(line).append("\n");
        }

        Path scripts = FabricLoader.getInstance().getGameDir().resolve("talos").resolve("scripts");
        Files.createDirectories(scripts);
        Path file = scripts.resolve(safe + ".py");
        Files.writeString(file, script.toString());
        return file;
    }

    private static List<String> emitBody(MacroData data) {
        boolean clicks = (data.channels() & MacroSystem.CH_CLICKS) != 0;
        boolean hotbar = (data.channels() & MacroSystem.CH_HOTBAR) != 0;
        List<String> lines = new ArrayList<>();
        int previousKeys = 0;
        int previousSlot = -1;
        int ticksSinceAction = 0;
        for (Frame frame : data.frames()) {
            List<String> actions = new ArrayList<>();
            if (hotbar && frame.slot() != previousSlot) {
                actions.add("talos.select_slot(" + frame.slot() + ")");
                previousSlot = frame.slot();
            }
            if (clicks) {
                // Rising edges only: a fresh press is one click, a hold stays one click.
                if ((frame.keys() & BIT_ATTACK) != 0 && (previousKeys & BIT_ATTACK) == 0)
                    actions.add("talos.left_click()");
                if ((frame.keys() & BIT_USE) != 0 && (previousKeys & BIT_USE) == 0)
                    actions.add("talos.right_click()");
            }
            previousKeys = frame.keys();
            if (actions.isEmpty()) {
                ticksSinceAction++;
                continue;
            }
            if (ticksSinceAction > 0) {
                String seconds = String.format(Locale.ROOT, "%.2f", ticksSinceAction * 0.05);
                lines.add("talos.wait_between(" + seconds + ", " + seconds + ")");
                ticksSinceAction = 0;
            }
            lines.addAll(actions);
            ticksSinceAction = 1; // this frame's tick elapses before the next action
        }
        return lines;
    }
}
