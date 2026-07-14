package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.ui.screen.TalosScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

/** {@code /talos ui} — open the P4b liquid-glass settings panel (see {@code TalosScreen}). */
final class UiCommand {
    private UiCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        // Defer: the chat screen closes after the command runs and would
        // immediately replace a screen set synchronously here.
        client.schedule(() -> client.setScreen(new TalosScreen()));
        return 1;
    }
}
