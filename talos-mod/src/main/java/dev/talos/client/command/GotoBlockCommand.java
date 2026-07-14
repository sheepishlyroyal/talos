package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.pathing.talos.BlockGoalNavigator;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/** {@code /talos goto block <id> [radius]} — see {@link BlockGoalNavigator}. */
final class GotoBlockCommand {
    private GotoBlockCommand() {}

    static int execute(CommandContext<FabricClientCommandSource> context, String blockId,
                       int radius) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("Looking for " + blockId + "..."));
        BlockGoalNavigator.navigate(source.getClient(), blockId, radius)
                .whenComplete((result, error) -> source.getClient().execute(() -> {
                    if (error != null) {
                        source.sendError(Text.literal("Pathing failed: " + error.getMessage()));
                    } else if (result.successful()) {
                        source.sendFeedback(Text.literal("Arrived"));
                    } else {
                        source.sendError(Text.literal(result.detail()));
                    }
                }));
        return 1;
    }
}
