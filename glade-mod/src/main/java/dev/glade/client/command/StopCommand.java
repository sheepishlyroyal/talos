package dev.glade.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.glade.client.GladeClient;
import dev.glade.client.script.ScriptEngine;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/** Implements {@code /glade stop} / {@code /glade stop all} — halts pathing, scripts, and tasks. */
final class StopCommand {
    private StopCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        GladeClient.pathingEngine().cancel();
        ScriptEngine.instance().stop();
        int cancelledTasks = GladeClient.taskScheduler().cancelAll();
        source.sendFeedback(Text.literal(
                "Glade: stopped " + cancelledTasks + " tasks, pathing, and scripts."));
        return 1;
    }
}
