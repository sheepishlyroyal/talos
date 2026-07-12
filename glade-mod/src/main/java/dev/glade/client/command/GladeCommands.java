package dev.glade.client.command;

import dev.glade.client.scan.BlockStatePredicate;
import dev.glade.client.bridge.GladeBridge;
import dev.glade.client.pathing.GoalBlock;
import dev.glade.client.pathing.GoalNear;
import dev.glade.client.pathing.GoalXZ;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.glade.client.script.ScriptEngine;
import dev.glade.client.blockeditor.screen.BlockEditorScreen;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
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
                                .then(coordinate("x")
                                        .then(coordinate("y")
                                                .then(coordinate("z")
                                                        .then(ClientCommandManager.argument(
                                                                        "range", IntegerArgumentType.integer(0))
                                                                .executes(context -> GotoCommand.execute(
                                                                        context,
                                                                        new GoalNear(
                                                                                coordValue(context, "x", eyePos(context).x),
                                                                                coordValue(context, "y", eyePos(context).y),
                                                                                coordValue(context, "z", eyePos(context).z),
                                                                                value(context, "range")))))))))
                        .then(ClientCommandManager.literal("xz")
                                .then(coordinate("x")
                                        .then(coordinate("z")
                                                .executes(context -> GotoCommand.execute(
                                                        context, xzGoal(context))))))
                        .then(coordinate("x")
                                .then(coordinate("y")
                                        .then(coordinate("z")
                                                .executes(context -> GotoCommand.execute(
                                                        context,
                                                        new GoalBlock(
                                                                coordValue(context, "x", eyePos(context).x),
                                                                coordValue(context, "y", eyePos(context).y),
                                                                coordValue(context, "z", eyePos(context).z))))))))
                // Top-level shorthand for `glade goto xz <x> <z>`.
                .then(ClientCommandManager.literal("xz")
                        .then(coordinate("x")
                                .then(coordinate("z")
                                        .executes(context -> GotoCommand.execute(
                                                context, xzGoal(context))))))
                .then(ClientCommandManager.literal("look")
                        .then(ClientCommandManager.argument("yaw", RelativeAngleArgumentType.angle())
                                .then(ClientCommandManager.argument("pitch", RelativeAngleArgumentType.angle())
                                        .executes(LookCommand::execute))))
                .then(ClientCommandManager.literal("glow")
                        .then(coordinate("x")
                                .then(coordinate("y")
                                        .then(coordinate("z")
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
                .then(ClientCommandManager.literal("editor")
                        .executes(context -> {
                            var client = context.getSource().getClient();
                            // Chat closes after command dispatch, so defer opening by one client task.
                            client.send(() -> client.setScreen(new BlockEditorScreen()));
                            return 1;
                        }))
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
                .then(ClientCommandManager.literal("bridge")
                        .then(ClientCommandManager.literal("allow")
                                .executes(context -> {
                                    int result = GladeBridge.allowSession();
                                    if (result == 0) context.getSource().sendError(Text.literal("Glade bridge is not running"));
                                    else context.getSource().sendFeedback(Text.literal("VS Code bridge allowed for this session"));
                                    return result;
                                }))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> {
                                    context.getSource().sendFeedback(Text.literal(GladeBridge.statusText()));
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

    private static GoalXZ xzGoal(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        Vec3d eye = eyePos(context);
        return new GoalXZ(coordValue(context, "x", eye.x), coordValue(context, "z", eye.z));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource,
            RelativeCoordinateArgumentType.Coordinate> coordinates(
                    CoordinateExecutor executor) {
        return coordinate("x").then(coordinate("y").then(coordinate("z").executes(context ->
                executor.execute(context, blockPos(context)))));
    }

    @FunctionalInterface
    private interface CoordinateExecutor {
        int execute(com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
                    net.minecraft.util.math.BlockPos pos);
    }

    /**
     * Builds a coordinate argument node ({@code x}, {@code y} or {@code z}) that accepts either an
     * absolute number or Minecraft-style {@code ~}-relative syntax (e.g. {@code ~}, {@code ~5}, {@code ~-3}).
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource,
            RelativeCoordinateArgumentType.Coordinate> coordinate(String name) {
        return ClientCommandManager.argument(name, RelativeCoordinateArgumentType.coordinate());
    }

    /** The player's current eye position, used as the base for {@code ~}-relative coordinate args. */
    private static Vec3d eyePos(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        return context.getSource().getPlayer().getEyePos();
    }

    private static net.minecraft.util.math.BlockPos blockPos(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        Vec3d eye = eyePos(context);
        return new net.minecraft.util.math.BlockPos(
                coordValue(context, "x", eye.x),
                coordValue(context, "y", eye.y),
                coordValue(context, "z", eye.z));
    }

    /** Resolves a coordinate argument (absolute or {@code ~}-relative) to a block coordinate. */
    private static int coordValue(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
            String name, double base) {
        return RelativeCoordinateArgumentType.resolveBlock(context, name, base);
    }

    private static int value(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
            String name) {
        return IntegerArgumentType.getInteger(context, name);
    }
}
