package dev.glade.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.glade.client.macro.MacroSystem;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/** {@code /glade macro record|stop|play|list|delete}. */
public final class MacroCommand {
    private MacroCommand() {}

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
                .then(ClientCommandManager.literal("list").executes(context -> {
                    var names = MacroSystem.list();
                    context.getSource().sendFeedback(Text.literal(names.isEmpty()
                            ? "No macros saved" : "Macros: " + String.join(", ", names)));
                    return 1;
                }))
                .then(ClientCommandManager.literal("delete")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
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
                    + "' — /glade macro stop first"));
            return 0;
        }
        source.sendFeedback(Text.literal("Recording macro '" + name + "' ("
                + MacroSystem.channelNames(channels)
                + ") — play normally, then /glade macro stop"));
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
