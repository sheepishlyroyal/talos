package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.talos.client.TalosClient;
import dev.talos.client.scan.BlockStatePredicate;
import dev.talos.client.scan.ScanTask;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

final class FindCommand {
    /** How long the nearest match stays highlighted after a successful scan. */
    private static final int HIGHLIGHT_TICKS = 10 * 20;

    private FindCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context, int radius)
            throws CommandSyntaxException {
        BlockStatePredicate predicate = BlockStatePredicate.fromArgument(context, "blockPredicate");
        FabricClientCommandSource source = context.getSource();
        ScanTask task = new ScanTask(predicate, radius, result -> report(source, result));
        try {
            TalosClient.taskScheduler().addTask("find-block", task);
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
        GlowCommand.highlight(result, HIGHLIGHT_TICKS);

        String coords = "%d %d %d".formatted(result.getX(), result.getY(), result.getZ());
        Text coordinates = Text.literal("%d, %d, %d".formatted(result.getX(), result.getY(), result.getZ()))
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/talos goto " + coords))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to walk there")))
                        .withColor(Formatting.AQUA));
        Text glow = Text.literal("[Glow]")
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/talos glow " + coords + " 60"))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("Highlight for 60s")))
                        .withColor(Formatting.YELLOW));
        source.sendFeedback(Text.literal("Closest match at ")
                .append(coordinates)
                .append(Text.literal(" (%.2f blocks away) ".formatted(distance)))
                .append(glow));
    }
}
