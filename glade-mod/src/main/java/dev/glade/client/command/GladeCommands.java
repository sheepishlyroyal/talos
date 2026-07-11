package dev.glade.client.command;

import dev.glade.client.scan.BlockStatePredicate;
import dev.glade.client.pathing.GoalBlock;
import dev.glade.client.pathing.GoalNear;
import dev.glade.client.pathing.GoalXZ;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.glade.client.script.ScriptEngine;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;

public final class GladeCommands {
    private GladeCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(GladeCommands::registerCommands);
    }

    private static void registerCommands(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher,
            CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("glade")
                .then(ClientCommandManager.literal("find")
                        .then(ClientCommandManager.literal("block")
                                .then(ClientCommandManager.argument(
                                                "blockPredicate", BlockStatePredicate.argument(registryAccess))
                                        .executes(context -> FindCommand.execute(
                                                context,
                                                context.getSource().getClient().options.getViewDistance().getValue()))
                                        .then(ClientCommandManager.argument(
                                                        "radius", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> FindCommand.execute(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")))))))
                .then(ClientCommandManager.literal("goto")
                        .then(ClientCommandManager.literal("near")
                                .then(integer("x")
                                        .then(integer("y")
                                                .then(integer("z")
                                                        .then(ClientCommandManager.argument(
                                                                        "range", IntegerArgumentType.integer(0))
                                                                .executes(context -> GotoCommand.execute(
                                                                        context,
                                                                        new GoalNear(
                                                                                value(context, "x"),
                                                                                value(context, "y"),
                                                                                value(context, "z"),
                                                                                value(context, "range")))))))))
                        .then(ClientCommandManager.literal("xz")
                                .then(integer("x")
                                        .then(integer("z")
                                                .executes(context -> GotoCommand.execute(
                                                        context,
                                                        new GoalXZ(value(context, "x"), value(context, "z")))))))
                        .then(integer("x")
                                .then(integer("y")
                                        .then(integer("z")
                                                .executes(context -> GotoCommand.execute(
                                                        context,
                                                        new GoalBlock(
                                                                value(context, "x"),
                                                                value(context, "y"),
                                                                value(context, "z"))))))))
                .then(ClientCommandManager.literal("glow")
                        .then(integer("x")
                                .then(integer("y")
                                        .then(integer("z")
                                                .executes(context -> GlowCommand.execute(
                                                        context, blockPos(context), GlowCommand.DEFAULT_SECONDS))
                                                .then(ClientCommandManager.argument(
                                                                "seconds", IntegerArgumentType.integer(1, 3600))
                                                        .executes(context -> GlowCommand.execute(
                                                                context,
                                                                blockPos(context),
                                                                value(context, "seconds"))))))))
                .then(ClientCommandManager.literal("mine")
                        .then(coordinates((context, pos) -> ActionCommand.mine(context, pos))))
                .then(ClientCommandManager.literal("place")
                        .then(coordinates((context, pos) -> ActionCommand.place(context, pos))))
                .then(ClientCommandManager.literal("ui")
                        .executes(UiCommand::execute))
                .then(ClientCommandManager.literal("script")
                        .then(ClientCommandManager.literal("run")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            var source = context.getSource();
                                            String name = StringArgumentType.getString(context, "name");
                                            ScriptEngine.instance().run(name).whenComplete((ignored, error) ->
                                                    source.getClient().execute(() -> {
                                                        if (error == null) source.sendFeedback(Text.literal("Script finished: " + name));
                                                        else source.sendError(Text.literal("Script failed: " + error.getMessage()));
                                                    }));
                                            source.sendFeedback(Text.literal("Started script: " + name));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("stop")
                                .executes(context -> {
                                    ScriptEngine.instance().stop();
                                    context.getSource().sendFeedback(Text.literal("Stopped script engine"));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("kill")
                        .then(ClientCommandManager.literal("nearest")
                                .executes(context -> ActionCommand.killNearest(context, 6.0))
                                .then(ClientCommandManager.argument(
                                                "radius", DoubleArgumentType.doubleArg(1.0, 64.0))
                                        .executes(context -> ActionCommand.killNearest(context,
                                                DoubleArgumentType.getDouble(context, "radius")))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource, Integer> coordinates(
                    CoordinateExecutor executor) {
        return integer("x").then(integer("y").then(integer("z").executes(context ->
                executor.execute(context, blockPos(context)))));
    }

    @FunctionalInterface
    private interface CoordinateExecutor {
        int execute(com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
                    net.minecraft.util.math.BlockPos pos);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource, Integer> integer(String name) {
        return ClientCommandManager.argument(name, IntegerArgumentType.integer());
    }

    private static net.minecraft.util.math.BlockPos blockPos(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        return new net.minecraft.util.math.BlockPos(
                value(context, "x"), value(context, "y"), value(context, "z"));
    }

    private static int value(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
            String name) {
        return IntegerArgumentType.getInteger(context, name);
    }
}
