package dev.glade.client.action;

import dev.glade.client.humanize.HumanizationProfile;
import dev.glade.client.humanize.RotationHumanizer;
import dev.glade.client.humanize.SeededRng;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Applies humanized rotation plans without ever snapping directly to a target. */
public final class AimController {
    private static final double REPLAN_DISTANCE_SQUARED = 0.12 * 0.12;
    private static final float AIM_EPSILON_DEGREES = 1.25F;

    private final MinecraftClient client;
    private final RotationHumanizer humanizer;
    private final HumanizationProfile profile;
    private final SeededRng rng;
    private RotationHumanizer.RotationPlan plan;
    private Vec3d plannedTarget;
    private Vec3d target;

    public AimController(MinecraftClient client, RotationHumanizer humanizer,
                         HumanizationProfile profile, long seed) {
        this.client = Objects.requireNonNull(client);
        this.humanizer = Objects.requireNonNull(humanizer);
        this.profile = Objects.requireNonNull(profile);
        this.rng = new SeededRng(seed);
    }

    public void aimAt(BlockPos pos) {
        aimAt(Vec3d.ofCenter(pos));
    }

    public void aimAt(Entity entity) {
        aimAt(entity.getBoundingBox().getCenter());
    }

    public void aimAt(Vec3d newTarget) {
        target = Objects.requireNonNull(newTarget);
        if (client.player == null) {
            plan = null;
            return;
        }
        if (plan == null || plannedTarget == null
                || plannedTarget.squaredDistanceTo(newTarget) > REPLAN_DISTANCE_SQUARED) {
            float[] angles = RotationHumanizer.yawPitchTo(client.player.getEyePos(), newTarget);
            plan = humanizer.plan(client.player.getYaw(), client.player.getPitch(),
                    angles[0], angles[1], profile, rng.fork());
            plannedTarget = newTarget;
        }
    }

    /** Advances at most one humanized rotation step. */
    public void tick() {
        if (client.player == null || plan == null || !plan.hasNext()) {
            return;
        }
        float[] step = plan.next();
        client.player.setYaw(step[0]);
        client.player.setPitch(step[1]);
        client.player.setHeadYaw(step[0]);
        client.player.setBodyYaw(step[0]);
    }

    public boolean isAimed() {
        if (client.player == null || target == null || (plan != null && plan.hasNext())) {
            return false;
        }
        float[] desired = RotationHumanizer.yawPitchTo(client.player.getEyePos(), target);
        return Math.abs(MathHelper.wrapDegrees(desired[0] - client.player.getYaw())) <= AIM_EPSILON_DEGREES
                && Math.abs(desired[1] - client.player.getPitch()) <= AIM_EPSILON_DEGREES;
    }

    public boolean hasLineOfSight(Vec3d targetPoint) {
        if (client.player == null || client.world == null) {
            return false;
        }
        var hit = client.world.raycast(new RaycastContext(client.player.getEyePos(), targetPoint,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }
}
