package dev.talos.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.talos.client.macro.MacroSystem;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;

/** {@code /talos macro record|stop|play|list|delete}. */
public final class MacroCommand {
    private MacroCommand() {}

    /**
     * Tab-completes saved macro names on {@code play}/{@code delete}/{@code export}
     * (not {@code record}, whose name is new). {@link MacroSystem#list()} already
     * returns an empty list when ~/.talos/macros is missing, so this never throws.
     */
    private static final SuggestionProvider<FabricClientCommandSource> MACRO_NAMES =
            (context, builder) -> CommandSource.suggestMatching(MacroSystem.list(), builder);

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        return ClientCommandManager.literal("macro")
                .then(ClientCommandManager.literal("record")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> record(context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        MacroSystem.CH_ALL))
                                .then(ClientCommandManager.argument("channels", StringArgumentType.word())
                                        .executes(context -> {
                                            int channels = MacroSystem.parseChannels(
                                                    StringArgumentType.getString(context, "channels"));
                                            if (channels < 0) {
                                                context.getSource().sendError(Text.literal(
                                                        "Unknown channel — use move, jump, sneak, sprint, "
                                                                + "clicks, look, hotbar, keys, input, all "
                                                                + "or combos like clicks+look"));
                                                return 0;
                                            }
                                            return record(context.getSource(),
                                                    StringArgumentType.getString(context, "name"),
                                                    channels);
                                        }))))
                .then(ClientCommandManager.literal("stop").executes(context -> {
                    int frames = MacroSystem.stopRecording();
                    if (frames < 0) {
                        context.getSource().sendError(Text.literal("Not recording"));
                        return 0;
                    }
                    context.getSource().sendFeedback(Text.literal(
                            "Saved macro (" + frames + " ticks, " + String.format("%.1f", frames / 20.0) + "s)"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("play")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests(MACRO_NAMES)
                                .executes(context -> play(context.getSource(),
                                        StringArgumentType.getString(context, "name"), 1, 0))
                                .then(ClientCommandManager.argument("times", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> play(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                IntegerArgumentType.getInteger(context, "times"), 0))
                                        .then(ClientCommandManager.argument("channels", StringArgumentType.word())
                                                .executes(context -> {
                                                    int channels = MacroSystem.parseChannels(
                                                            StringArgumentType.getString(context, "channels"));
                                                    if (channels < 0) {
                                                        context.getSource().sendError(
                                                                Text.literal("Unknown channel spec"));
                                                        return 0;
                                                    }
                                                    return play(context.getSource(),
                                                            StringArgumentType.getString(context, "name"),
                                                            IntegerArgumentType.getInteger(context, "times"),
                                                            channels);
                                                })))))
                .then(ClientCommandManager.literal("export")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests(MACRO_NAMES)
                                .executes(context -> {
                                    try {
                                        context.getSource().sendFeedback(Text.literal(
                                                "Exported to " + dev.talos.client.macro
                                                        .RecordingExporter.export(
                                                        StringArgumentType.getString(context, "name"))));
                                        return 1;
                                    } catch (java.io.IOException error) {
                                        context.getSource().sendError(Text.literal(
                                                "Export failed: " + error.getMessage()));
                                        return 0;
                                    }
                                })))
                .then(ClientCommandManager.literal("list").executes(context -> {
                    var names = MacroSystem.list();
                    context.getSource().sendFeedback(Text.literal(names.isEmpty()
                            ? "No macros saved" : "Macros: " + String.join(", ", names)));
                    return 1;
                }))
                .then(ClientCommandManager.literal("delete")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests(MACRO_NAMES)
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    if (MacroSystem.delete(name)) {
                                        context.getSource().sendFeedback(Text.literal("Deleted '" + name + "'"));
                                        return 1;
                                    }
                                    context.getSource().sendError(Text.literal("No macro '" + name + "'"));
                                    return 0;
                                })));
    }

    private static int record(FabricClientCommandSource source, String name, int channels) {
        if (!MacroSystem.startRecording(name, channels)) {
            source.sendError(Text.literal("Already recording '" + MacroSystem.recordingName()
                    + "' — /talos macro stop first"));
            return 0;
        }
        source.sendFeedback(Text.literal("Recording macro '" + name + "' ("
                + MacroSystem.channelNames(channels)
                + ") — play normally, then /talos macro stop"));
        return 1;
    }

    private static int play(FabricClientCommandSource source, String name, int times,
            int channels) {
        if (MacroSystem.isRecording()) {
            source.sendError(Text.literal("Stop recording before playing a macro"));
            return 0;
        }
        try {
            if (!MacroSystem.play(source.getClient(), name, times, channels)) {
                source.sendError(Text.literal(
                        "No macro '" + name + "' (or no overlap with those channels)"));
                return 0;
            }
        } catch (RuntimeException exception) {
            source.sendError(Text.literal(exception.getMessage()));
            return 0;
        }
        source.sendFeedback(Text.literal("Playing macro '" + name + "'"
                + (times > 1 ? " x" + times : "")));
        return 1;
    }
}
