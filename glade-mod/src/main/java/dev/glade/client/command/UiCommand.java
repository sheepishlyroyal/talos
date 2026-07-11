package dev.glade.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.glade.client.ui.screen.GladeScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;

/** {@code /glade ui} — open the P4b liquid-glass settings panel (see {@code GladeScreen}). */
final class UiCommand {
    private UiCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = context.getSource().getClient();
        // Defer: the chat screen closes after the command runs and would
        // immediately replace a screen set synchronously here.
        client.send(() -> client.setScreen(new GladeScreen()));
        return 1;
    }
}
