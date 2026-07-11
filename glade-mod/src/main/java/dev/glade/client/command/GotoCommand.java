package dev.glade.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.glade.client.GladeClient;
import dev.glade.client.pathing.Goal;
import dev.glade.client.pathing.PathResult;
import dev.glade.client.pathing.PathingEngine;
import dev.glade.client.pathing.PathingOptions;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

final class GotoCommand {
    private GotoCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context, Goal goal) {
        FabricClientCommandSource source = context.getSource();
        PathingEngine engine = GladeClient.pathingEngine();
        if (!engine.isAvailable()) {
            source.sendError(Text.literal(
                    "Baritone not installed — install the Baritone mod to use /glade goto"));
            return 0;
        }

        source.sendFeedback(Text.literal("Pathing started"));
        engine.goTo(goal, PathingOptions.DEFAULT).whenComplete((result, error) ->
                source.getClient().execute(() -> report(source, result, error)));
        return 1;
    }

    private static void report(
            FabricClientCommandSource source, PathResult result, Throwable error) {
        if (error != null) {
            String message = error.getMessage();
            source.sendError(Text.literal("Pathing failed"
                    + (message == null ? "" : ": " + message)));
        } else if (result.successful()) {
            source.sendFeedback(Text.literal("Arrived"));
        } else {
            source.sendError(Text.literal("Pathing failed: " + result.detail()));
        }
    }
}
