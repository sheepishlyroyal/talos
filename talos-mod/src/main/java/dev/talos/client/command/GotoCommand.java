package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.TalosClient;
import dev.talos.client.pathing.Goal;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.pathing.PathingEngine;
import dev.talos.client.pathing.PathingOptions;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

final class GotoCommand {
    private GotoCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context, Goal goal) {
        FabricClientCommandSource source = context.getSource();
        PathingEngine engine = TalosClient.pathingEngine();
        if (!engine.isAvailable()) {
            source.sendError(Component.literal(
                    "Baritone not installed — install the Baritone mod to use /talos goto"));
            return 0;
        }

        source.sendFeedback(Component.literal("Pathing started"));
        engine.goTo(goal, PathingOptions.DEFAULT).whenComplete((result, error) ->
                source.getClient().execute(() -> report(source, result, error)));
        return 1;
    }

    private static void report(
            FabricClientCommandSource source, PathResult result, Throwable error) {
        if (error != null) {
            String message = error.getMessage();
            source.sendError(Component.literal("Pathing failed"
                    + (message == null ? "" : ": " + message)));
        } else if (result.successful()) {
            source.sendFeedback(Component.literal("Arrived"));
        } else {
            source.sendError(Component.literal("Pathing failed: " + result.detail()));
        }
    }
}
