package dev.glade.client.command;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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
    static BlockPos blockAt(ClientPlayerEntity player, float yaw, float pitch) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return null;
        }

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector(pitch, yaw);
        double reach = player.getBlockInteractionRange();
        if (reach <= 0.0) {
            reach = FALLBACK_REACH;
        }
        Vec3d end = eye.add(look.multiply(reach));

        BlockHitResult hit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getBlockPos();
    }
}
