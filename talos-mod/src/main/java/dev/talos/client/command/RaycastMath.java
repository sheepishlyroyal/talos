package dev.talos.client.command;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Look-relative geometry shared by {@code /talos raytrace} and the Python raytrace bridge.
 *
 * <p>Two independent pieces:</p>
 * <ul>
 *   <li>{@link #local(Vec3, float, float, double, double, double)} — resolves Minecraft's caret
 *       ({@code ^left ^up ^forward}) local-coordinate system to a world point, using the exact
 *       same orthonormal basis vanilla builds in {@code LookingPosArgument}. This is what makes
 *       "5 blocks forward" ({@code ^ ^ 5}) mean the same thing here as in a vanilla command.</li>
 *   <li>{@link #cast(LocalPlayer, Level, double)} — a single ray from the eyes along the
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
    public static Vec3 local(Vec3 origin, float yaw, float pitch,
                              double left, double up, double forward) {
        float f = Mth.cos((yaw + 90.0F) * DEG);
        float g = Mth.sin((yaw + 90.0F) * DEG);
        float h = Mth.cos(-pitch * DEG);
        float i = Mth.sin(-pitch * DEG);
        float j = Mth.cos((-pitch + 90.0F) * DEG);
        float k = Mth.sin((-pitch + 90.0F) * DEG);
        Vec3 fwd = new Vec3(f * h, i, g * h);
        Vec3 upVec = new Vec3(f * j, k, g * j);
        Vec3 leftVec = fwd.cross(upVec).scale(-1.0);
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
    public record Hit(HitType type, Vec3 point, String id, double distance,
                      BlockPos blockPos, Entity entity) {
        public boolean isMiss() {
            return type == HitType.MISS;
        }
    }

    /**
     * Casts from {@code player}'s eyes along their current look, up to {@code maxDist} blocks, and
     * returns the nearer of the first solid block and the first entity struck. Fluids are ignored.
     */
    public static Hit cast(LocalPlayer player, Level world, double maxDist) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(maxDist));

        BlockHitResult block = world.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        double blockDist = block != null && block.getType() == HitResult.Type.BLOCK
                ? eye.distanceTo(block.getLocation()) : maxDist;

        // Only search for entities nearer than the block hit — a block occludes what is behind it.
        AABB search = player.getBoundingBox()
                .expandTowards(look.scale(blockDist)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, eye,
                eye.add(look.scale(blockDist)), search,
                e -> e != player && e.isAlive() && !e.isSpectator() && e.isPickable(),
                blockDist * blockDist);

        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            return new Hit(HitType.ENTITY, entityHit.getLocation(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    eye.distanceTo(entityHit.getLocation()), null, entity);
        }
        if (block != null && block.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = block.getBlockPos();
            return new Hit(HitType.BLOCK, block.getLocation(),
                    BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString(),
                    eye.distanceTo(block.getLocation()), pos, null);
        }
        return new Hit(HitType.MISS, end, null, maxDist, null, null);
    }
}
