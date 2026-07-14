package dev.talos.client.command;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Look-relative geometry shared by {@code /talos raytrace} and the Python raytrace bridge.
 *
 * <p>Two independent pieces:</p>
 * <ul>
 *   <li>{@link #local(Vec3d, float, float, double, double, double)} — resolves Minecraft's caret
 *       ({@code ^left ^up ^forward}) local-coordinate system to a world point, using the exact
 *       same orthonormal basis vanilla builds in {@code LookingPosArgument}. This is what makes
 *       "5 blocks forward" ({@code ^ ^ 5}) mean the same thing here as in a vanilla command.</li>
 *   <li>{@link #cast(ClientPlayerEntity, World, double)} — a single ray from the eyes along the
 *       current look that returns the nearer of the first block and first entity hit, with
 *       sub-block precision.</li>
 * </ul>
 */
public final class RaycastMath {
    private static final float DEG = (float) (Math.PI / 180.0);

    private RaycastMath() {
    }

    /**
     * Resolves a caret local coordinate to a world point. {@code left}/{@code up}/{@code forward}
     * are the three {@code ^} values, in the frame of an entity at {@code origin} facing
     * {@code yaw}/{@code pitch}. Mirrors {@code LookingPosArgument#toAbsolutePos}.
     */
    public static Vec3d local(Vec3d origin, float yaw, float pitch,
                              double left, double up, double forward) {
        float f = MathHelper.cos((yaw + 90.0F) * DEG);
        float g = MathHelper.sin((yaw + 90.0F) * DEG);
        float h = MathHelper.cos(-pitch * DEG);
        float i = MathHelper.sin(-pitch * DEG);
        float j = MathHelper.cos((-pitch + 90.0F) * DEG);
        float k = MathHelper.sin((-pitch + 90.0F) * DEG);
        Vec3d fwd = new Vec3d(f * h, i, g * h);
        Vec3d upVec = new Vec3d(f * j, k, g * j);
        Vec3d leftVec = fwd.crossProduct(upVec).multiply(-1.0);
        double dx = fwd.x * forward + upVec.x * up + leftVec.x * left;
        double dy = fwd.y * forward + upVec.y * up + leftVec.y * left;
        double dz = fwd.z * forward + upVec.z * up + leftVec.z * left;
        return origin.add(dx, dy, dz);
    }

    /** Outcome kind of a {@link #cast} — a block cell, an entity, or nothing in range. */
    public enum HitType { BLOCK, ENTITY, MISS }

    /**
     * The result of a look raycast. {@code point} is the exact hit location (never null; equals the
     * ray's far end on a miss). {@code id} is the block/entity registry id, {@code blockPos} the hit
     * cell for a block hit (null otherwise), {@code entity} the struck entity for an entity hit.
     */
    public record Hit(HitType type, Vec3d point, String id, double distance,
                      BlockPos blockPos, Entity entity) {
        public boolean isMiss() {
            return type == HitType.MISS;
        }
    }

    /**
     * Casts from {@code player}'s eyes along their current look, up to {@code maxDist} blocks, and
     * returns the nearer of the first solid block and the first entity struck. Fluids are ignored.
     */
    public static Hit cast(ClientPlayerEntity player, World world, double maxDist) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector();
        Vec3d end = eye.add(look.multiply(maxDist));

        BlockHitResult block = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        double blockDist = block != null && block.getType() == HitResult.Type.BLOCK
                ? eye.distanceTo(block.getPos()) : maxDist;

        // Only search for entities nearer than the block hit — a block occludes what is behind it.
        Box search = player.getBoundingBox()
                .stretch(look.multiply(blockDist)).expand(1.0, 1.0, 1.0);
        EntityHitResult entityHit = ProjectileUtil.raycast(player, eye,
                eye.add(look.multiply(blockDist)), search,
                e -> e != player && e.isAlive() && !e.isSpectator() && e.canHit(),
                blockDist * blockDist);

        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            return new Hit(HitType.ENTITY, entityHit.getPos(),
                    Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                    eye.distanceTo(entityHit.getPos()), null, entity);
        }
        if (block != null && block.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = block.getBlockPos();
            return new Hit(HitType.BLOCK, block.getPos(),
                    Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString(),
                    eye.distanceTo(block.getPos()), pos, null);
        }
        return new Hit(HitType.MISS, end, null, maxDist, null, null);
    }
}
