package dev.glade.client.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.glade.client.GladeClient;
import dev.glade.client.scan.BlockStatePredicate;
import dev.glade.client.scan.ScanTask;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

final class FindCommand {
    private FindCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context, int radius)
            throws CommandSyntaxException {
        BlockStatePredicate predicate = BlockStatePredicate.fromArgument(context, "blockPredicate");
        FabricClientCommandSource source = context.getSource();
        ScanTask task = new ScanTask(predicate, radius, result -> report(source, result));
        try {
            GladeClient.taskScheduler().addTask("find-block", task);
        } catch (IllegalStateException conflict) {
            source.sendError(Text.literal("A world scan is already running"));
            return 0;
        }
        source.sendFeedback(Text.literal("Scanning loaded chunks..."));
        return 1;
    }

    private static void report(FabricClientCommandSource source, BlockPos result) {
        if (result == null) {
            source.sendError(Text.literal("No match found"));
            return;
        }
        var camera = MinecraftClient.getInstance().getCameraEntity();
        double distance = camera == null ? 0.0 : Math.sqrt(result.getSquaredDistance(camera.getCameraPosVec(0.0F)));
        source.sendFeedback(Text.literal("Closest match at %d, %d, %d (%.2f blocks away)"
                .formatted(result.getX(), result.getY(), result.getZ(), distance)));
    }
}
