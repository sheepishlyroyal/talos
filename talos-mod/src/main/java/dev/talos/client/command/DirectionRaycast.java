package dev.talos.client.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Converts a look direction (yaw/pitch, in degrees) into the {@link BlockPos} it points at, by
 * raycasting from the player's eyes out to their block-interaction reach.
 */
final class DirectionRaycast {
    /** Reach used when the player has no valid interaction range (e.g. spectator edge cases). */
    private static final double FALLBACK_REACH = 5.0;

    private DirectionRaycast() {
    }

    /**
     * Raycasts from {@code player}'s eyes along {@code yaw}/{@code pitch} up to the player's
     * block-interaction range. Returns the hit {@link BlockPos}, or {@code null} if the ray hits
     * nothing (or hits fluid only, which is excluded).
     */
    static BlockPos blockAt(LocalPlayer player, float yaw, float pitch) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return null;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.calculateViewVector(pitch, yaw);
        double reach = player.blockInteractionRange();
        if (reach <= 0.0) {
            reach = FALLBACK_REACH;
        }
        Vec3 end = eye.add(look.scale(reach));

        BlockHitResult hit = world.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getBlockPos();
    }
}
