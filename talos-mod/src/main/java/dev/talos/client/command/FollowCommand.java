package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.pathing.talos.FollowTask;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * {@code /talos follow <target> [distance]} — follow any entity, not just players.
 * Targets resolve through {@link EntitySelectors}: a player name, an entity type
 * ({@code zombie}), or a selector ({@code @e[type=cow,distance=..20]}). Ends via
 * {@code /talos stop}, by issuing any other goto, or when the target stays gone.
 */
final class FollowCommand {
    private FollowCommand() {}

    static int execute(CommandContext<FabricClientCommandSource> context, String target,
                       double distance) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = source.getClient();
        Entity entity;
        try {
            entity = EntitySelectors.resolve(client, target, true);
        } catch (IllegalArgumentException error) {
            source.sendError(Component.literal(error.getMessage()));
            return 0;
        }
        if (entity == client.player) {
            source.sendError(Component.literal("Cannot follow yourself"));
            return 0;
        }
        String name = entity.getName().getString();
        source.sendFeedback(Component.literal("Following " + name
                + " (keeping ~" + (int) distance + " blocks; /talos stop to end)"));
        FollowTask.start(client, entity, distance).whenComplete((result, error) ->
                client.execute(() -> {
                    String detail = error != null ? String.valueOf(error.getMessage())
                            : result.detail();
                    source.sendFeedback(Component.literal("Follow ended: " + detail));
                }));
        return 1;
    }
}
