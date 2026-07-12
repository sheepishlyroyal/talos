package dev.glade.client.command;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

/** {@code /glade look <yaw> <pitch>} — set the player's look angles, supports {@code ^} relative syntax. */
final class LookCommand {
    private LookCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        ClientPlayerEntity player = source.getPlayer();

        float yaw = RelativeAngleArgumentType.resolve(context, "yaw", player.getYaw());
        float pitch = RelativeAngleArgumentType.resolve(context, "pitch", player.getPitch());
        pitch = net.minecraft.util.math.MathHelper.clamp(pitch, -90.0F, 90.0F);

        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);

        source.sendFeedback(Text.literal("Looking at yaw %.2f, pitch %.2f".formatted(yaw, pitch)));
        return 1;
    }
}
