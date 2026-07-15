package dev.talos.client.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.talos.client.ui.screen.PythonEditorScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;

/**
 * Registers {@code /talos script editor}, opening the in-game Python editor screen.
 *
 * <p>Kept as its own registration (instead of a new branch inside {@link TalosCommands})
 * so this feature ships without touching that file: Brigadier merges sibling
 * {@code CommandDispatcher.register} calls that share a literal name (see
 * {@code CommandNode#addChild}), so this simply adds an {@code editor} child alongside
 * the existing {@code run}/{@code stop} children of {@code /talos script}.
 */
public final class ScriptCommand {
    private ScriptCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(ScriptCommand::registerCommands);
    }

    private static void registerCommands(
            CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommands.literal("talos")
                .then(ClientCommands.literal("script")
                        .then(ClientCommands.literal("editor")
                                .executes(context -> {
                                    var client = context.getSource().getClient();
                                    // Chat closes after command dispatch, so defer opening
                                    // by one client task (same trick as /talos editor).
                                    client.execute(() -> client.gui.setScreen(new PythonEditorScreen()));
                                    return 1;
                                }))));
    }
}
