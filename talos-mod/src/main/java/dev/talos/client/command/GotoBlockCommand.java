package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.pathing.talos.BlockGoalNavigator;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** {@code /talos goto block <id> [radius]} — see {@link BlockGoalNavigator}. */
final class GotoBlockCommand {
    private GotoBlockCommand() {}

    static int execute(CommandContext<FabricClientCommandSource> context, String blockId,
                       int radius) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("Looking for " + blockId + "..."));
        BlockGoalNavigator.navigate(source.getClient(), blockId, radius)
                .whenComplete((result, error) -> source.getClient().execute(() -> {
                    if (error != null) {
                        source.sendError(Component.literal("Pathing failed: " + error.getMessage()));
                    } else if (result.successful()) {
                        source.sendFeedback(Component.literal("Arrived"));
                    } else {
                        source.sendError(Component.literal(result.detail()));
                    }
                }));
        return 1;
    }
}
