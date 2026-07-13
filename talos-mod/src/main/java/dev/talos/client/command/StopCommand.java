package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.TalosClient;
import dev.talos.client.script.ScriptEngine;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/** Implements {@code /talos stop} / {@code /talos stop all} — halts pathing, scripts, and tasks. */
final class StopCommand {
    private StopCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        TalosClient.pathingEngine().cancel();
        ScriptEngine.instance().stop();
        int cancelledTasks = TalosClient.taskScheduler().cancelAll();
        source.sendFeedback(Text.literal(
                "Talos: stopped " + cancelledTasks + " tasks, pathing, and scripts."));
        return 1;
    }
}
