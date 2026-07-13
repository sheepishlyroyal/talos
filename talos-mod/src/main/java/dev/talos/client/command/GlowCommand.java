package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.render.RenderQueue;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** {@code /talos glow <x> <y> <z> [seconds]} — wireframe-highlight a block position. */
final class GlowCommand {
    static final int DEFAULT_SECONDS = 30;
    static final int GLOW_COLOR = 0x33FF66;

    /** Slight over-expansion so the outline does not z-fight with block faces. */
    private static final double BOX_INFLATION = 0.002;

    private GlowCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context, BlockPos pos, int seconds) {
        highlight(pos, seconds * 20);
        context.getSource().sendFeedback(Text.literal("Glowing %d %d %d for %ds"
                .formatted(pos.getX(), pos.getY(), pos.getZ(), seconds)));
        return 1;
    }

    /** Adds a glow box keyed by position, replacing any previous glow at the same spot. */
    static void highlight(BlockPos pos, int lifeTicks) {
        RenderQueue.add("glow:" + pos.asLong(), new Box(pos).expand(BOX_INFLATION), GLOW_COLOR, lifeTicks);
    }
}
