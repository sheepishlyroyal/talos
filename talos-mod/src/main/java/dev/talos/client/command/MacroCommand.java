package dev.talos.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.talos.client.macro.MacroSystem;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/** {@code /talos macro record|stop|play|list|delete}. */
public final class MacroCommand {
    private MacroCommand() {}

    /**
     * Tab-completes saved macro names on {@code play}/{@code delete}/{@code export}
     * (not {@code record}, whose name is new). {@link MacroSystem#list()} already
     * returns an empty list when ~/.talos/macros is missing, so this never throws.
     */
    private static final SuggestionProvider<FabricClientCommandSource> MACRO_NAMES =
            (context, builder) -> SharedSuggestionProvider.suggest(MacroSystem.list(), builder);

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        return ClientCommands.literal("macro")
                .then(ClientCommands.literal("record")
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .executes(context -> record(context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        MacroSystem.CH_ALL))
                                .then(ClientCommands.argument("channels", StringArgumentType.word())
                                        .executes(context -> {
                                            int channels = MacroSystem.parseChannels(
                                                    StringArgumentType.getString(context, "channels"));
                                            if (channels < 0) {
                                                context.getSource().sendError(Component.literal(
                                                        "Unknown channel — use move, jump, sneak, sprint, "
                                                                + "clicks, look, hotbar, keys, input, all "
                                                                + "or combos like clicks+look"));
                                                return 0;
                                            }
                                            return record(context.getSource(),
                                                    StringArgumentType.getString(context, "name"),
                                                    channels);
                                        }))))
                .then(ClientCommands.literal("stop").executes(context -> {
                    int frames = MacroSystem.stopRecording();
                    if (frames < 0) {
                        context.getSource().sendError(Component.literal("Not recording"));
                        return 0;
                    }
                    context.getSource().sendFeedback(Component.literal(
                            "Saved macro (" + frames + " ticks, " + String.format("%.1f", frames / 20.0) + "s)"));
                    return 1;
                }))
                .then(ClientCommands.literal("play")
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .suggests(MACRO_NAMES)
                                .executes(context -> play(context.getSource(),
                                        StringArgumentType.getString(context, "name"), 1, 0))
                                .then(ClientCommands.argument("times", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> play(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                IntegerArgumentType.getInteger(context, "times"), 0))
                                        .then(ClientCommands.argument("channels", StringArgumentType.word())
                                                .executes(context -> {
                                                    int channels = MacroSystem.parseChannels(
                                                            StringArgumentType.getString(context, "channels"));
                                                    if (channels < 0) {
                                                        context.getSource().sendError(
                                                                Component.literal("Unknown channel spec"));
                                                        return 0;
                                                    }
                                                    return play(context.getSource(),
                                                            StringArgumentType.getString(context, "name"),
                                                            IntegerArgumentType.getInteger(context, "times"),
                                                            channels);
                                                })))))
                .then(ClientCommands.literal("export")
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .suggests(MACRO_NAMES)
                                .executes(context -> {
                                    try {
                                        context.getSource().sendFeedback(Component.literal(
                                                "Exported to " + dev.talos.client.macro
                                                        .RecordingExporter.export(
                                                        StringArgumentType.getString(context, "name"))));
                                        return 1;
                                    } catch (java.io.IOException error) {
                                        context.getSource().sendError(Component.literal(
                                                "Export failed: " + error.getMessage()));
                                        return 0;
                                    }
                                })))
                .then(ClientCommands.literal("list").executes(context -> {
                    var names = MacroSystem.list();
                    context.getSource().sendFeedback(Component.literal(names.isEmpty()
                            ? "No macros saved" : "Macros: " + String.join(", ", names)));
                    return 1;
                }))
                .then(ClientCommands.literal("delete")
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .suggests(MACRO_NAMES)
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    if (MacroSystem.delete(name)) {
                                        context.getSource().sendFeedback(Component.literal("Deleted '" + name + "'"));
                                        return 1;
                                    }
                                    context.getSource().sendError(Component.literal("No macro '" + name + "'"));
                                    return 0;
                                })));
    }

    private static int record(FabricClientCommandSource source, String name, int channels) {
        if (!MacroSystem.startRecording(name, channels)) {
            source.sendError(Component.literal("Already recording '" + MacroSystem.recordingName()
                    + "' — /talos macro stop first"));
            return 0;
        }
        source.sendFeedback(Component.literal("Recording macro '" + name + "' ("
                + MacroSystem.channelNames(channels)
                + ") — play normally, then /talos macro stop"));
        return 1;
    }

    private static int play(FabricClientCommandSource source, String name, int times,
            int channels) {
        if (MacroSystem.isRecording()) {
            source.sendError(Component.literal("Stop recording before playing a macro"));
            return 0;
        }
        try {
            if (!MacroSystem.play(source.getClient(), name, times, channels)) {
                source.sendError(Component.literal(
                        "No macro '" + name + "' (or no overlap with those channels)"));
                return 0;
            }
        } catch (RuntimeException exception) {
            source.sendError(Component.literal(exception.getMessage()));
            return 0;
        }
        source.sendFeedback(Component.literal("Playing macro '" + name + "'"
                + (times > 1 ? " x" + times : "")));
        return 1;
    }
}
