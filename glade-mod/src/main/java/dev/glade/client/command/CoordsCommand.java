package dev.glade.client.command;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * {@code /glade coords direction <yaw> <pitch>} — reports the block coordinates hit by a raycast
 * from the player's eyes along a look direction (supports {@code ^}/{@code ^n} relative angles).
 * The raycast itself (and the "nothing in range" case) is handled by {@code GladeCommands}'
 * shared {@code directionNode} helper via {@link DirectionRaycast}; this just formats feedback
 * for an already-resolved hit.
 */
final class CoordsCommand {
    private CoordsCommand() {
    }

    static int executeDirection(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        context.getSource().sendFeedback(Text.literal(
                "Ray hits %d, %d, %d".formatted(pos.getX(), pos.getY(), pos.getZ())));
        return 1;
    }
}
