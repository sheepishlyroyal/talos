package dev.glade.client.command;

import dev.glade.client.scan.BlockStatePredicate;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
                                                        IntegerArgumentType.getInteger(context, "radius"))))))));
    }
}
