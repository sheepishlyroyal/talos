package dev.glade.client.action;

import dev.glade.client.humanize.HumanizationProfile;
import dev.glade.client.humanize.RotationHumanizer;
import dev.glade.client.humanize.SeededRng;
import dev.glade.client.render.RenderQueue;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Humanized cube aim. Around every intended look-point a 1x1x1m cube is placed OFF-GRID
 * (centered exactly on the point, like the hitbox nodes). A concrete aim spot — the "red X"
 * — is chosen on one of the cube's player-visible faces, weighted by how much flat area of
 * each face the player can actually see (face-on beats edge-on). The rotation then runs a
 * two-sensitivity profile: a randomly drawn FAST sensitivity far out that blends smoothly
 * (never instantly) into a randomly drawn SLOW sensitivity once the aim ray passes within
 * 0.4m of the cube, which is the fast-flick-then-settle shape of real mouse movement. The
 * whole convergence completes within a few ticks, so movement code can use it without the
 * old snap.
 */
public final class AimController {
    /** Escape hatch: false = legacy instant snap (no cube, no easing). */
    public static boolean HUMANIZE = true;
    private static final float AIM_EPSILON_DEGREES = 1.25F;
    private static final double SLOW_ZONE_METERS = 0.4;
    private static final double REPLAN_DISTANCE_SQUARED = 0.35 * 0.35;
    private static final int CUBE_COLOR = 0xFFE14D;   // yellow guide cube
    private static final int MARK_COLOR = 0xFF3333;   // the red X
    private static final int VISUAL_TTL = 15;

    private final MinecraftClient client;
    private final SeededRng rng;
    private Vec3d center;   // the point the caller asked to look at (cube center)
    private Vec3d mark;     // the chosen red-X spot actually aimed for
    private double fastSensitivity;
    private double slowSensitivity;
    private double currentSensitivity;

    public AimController(MinecraftClient client, RotationHumanizer humanizer,
                         HumanizationProfile profile, long seed) {
        this.client = Objects.requireNonNull(client);
        this.rng = new SeededRng(seed);
    }

    public void aimAt(BlockPos pos) {
        aimAt(Vec3d.ofCenter(pos));
    }

    public void aimAt(Entity entity) {
        aimAt(entity.getBoundingBox().getCenter());
    }

    public void aimAt(Vec3d newTarget) {
        Objects.requireNonNull(newTarget);
        if (client.player == null) return;
        if (center != null && center.squaredDistanceTo(newTarget) <= REPLAN_DISTANCE_SQUARED) {
            center = newTarget; // small drift: keep the session, keep the mark's face offset
            return;
        }
        center = newTarget;
        mark = chooseMark(newTarget);
        // Fresh sensitivities per aim session; the pair itself is part of the human variance.
        fastSensitivity = 18.0 + rng.nextDouble() * 22.0;   // deg/tick far out
        slowSensitivity = 2.5 + rng.nextDouble() * 3.5;     // deg/tick in the slow zone
        currentSensitivity = fastSensitivity * (0.75 + rng.nextDouble() * 0.25);
        renderVisuals();
    }

    /** Advances one tick of rotation toward the mark. Call every tick until isAimed(). */
    public void tick() {
        if (client.player == null || center == null) return;
        if (!HUMANIZE) { snapTo(center); return; }
        renderVisuals();

        Vec3d eye = client.player.getEyePos();
        float[] desired = RotationHumanizer.yawPitchTo(eye, mark);
        float yawError = MathHelper.wrapDegrees(desired[0] - client.player.getYaw());
        float pitchError = desired[1] - client.player.getPitch();
        double angularError = Math.max(Math.abs(yawError), Math.abs(pitchError));

        // Where the current look ray passes at the mark's distance: its miss distance in
        // meters is the "how close to the cube" measure that gates the slowdown.
        double distance = eye.distanceTo(mark);
        Vec3d rayPoint = eye.add(client.player.getRotationVecClient().multiply(distance));
        double missMeters = rayPoint.distanceTo(mark);

        // Smooth blend, never a step: outside 3x the slow zone -> fast; inside the slow
        // zone -> slow; the sensitivity itself is additionally low-pass filtered.
        double blend = MathHelper.clamp(
                (missMeters - SLOW_ZONE_METERS) / (SLOW_ZONE_METERS * 2.0), 0.0, 1.0);
        blend = blend * blend * (3.0 - 2.0 * blend); // smoothstep
        double targetSensitivity = slowSensitivity
                + (fastSensitivity - slowSensitivity) * blend;
        currentSensitivity += (targetSensitivity - currentSensitivity) * 0.35;

        if (angularError < 1.0E-3) return;
        // Per-tick jitter keeps successive approaches from tracing identical curves.
        double step = currentSensitivity * (0.9 + rng.nextDouble() * 0.2);
        double fraction = Math.min(1.0, step / angularError);
        float yaw = client.player.getYaw() + (float) (yawError * fraction);
        float pitch = MathHelper.clamp(
                client.player.getPitch() + (float) (pitchError * fraction), -90.0F, 90.0F);
        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
        client.player.setHeadYaw(yaw);
        client.player.setBodyYaw(yaw);
    }

    public boolean isAimed() {
        if (client.player == null || center == null) return false;
        if (!HUMANIZE) return true;
        float[] desired = RotationHumanizer.yawPitchTo(client.player.getEyePos(), mark);
        return Math.abs(MathHelper.wrapDegrees(desired[0] - client.player.getYaw()))
                <= AIM_EPSILON_DEGREES
                && Math.abs(desired[1] - client.player.getPitch()) <= AIM_EPSILON_DEGREES;
    }

    /**
     * Picks the red-X spot: faces are weighted by their visible flat area (the dot of the
     * face normal with the direction to the eye — a face seen square-on offers its whole
     * square meter, a grazing face almost none), then the spot lands center-biased on the
     * chosen face so the cube's true center remains the expected value.
     */
    private Vec3d chooseMark(Vec3d cubeCenter) {
        Vec3d toEye = client.player.getEyePos().subtract(cubeCenter);
        if (toEye.lengthSquared() < 1.0E-6) return cubeCenter;
        toEye = toEye.normalize();
        Vec3d[] normals = {
                new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0), new Vec3d(0, 1, 0),
                new Vec3d(0, -1, 0), new Vec3d(0, 0, 1), new Vec3d(0, 0, -1)};
        double[] weights = new double[6];
        double total = 0.0;
        for (int i = 0; i < 6; i++) {
            weights[i] = Math.max(0.0, normals[i].dotProduct(toEye));
            total += weights[i];
        }
        Vec3d normal = normals[0];
        if (total > 0.0) {
            double pick = rng.nextDouble() * total;
            for (int i = 0; i < 6; i++) {
                pick -= weights[i];
                if (pick <= 0.0) { normal = normals[i]; break; }
            }
        }
        // Triangular distribution biases the X toward the face center: sloppy but not wild.
        double u = (rng.nextDouble() + rng.nextDouble() - 1.0) * 0.45;
        double v = (rng.nextDouble() + rng.nextDouble() - 1.0) * 0.45;
        Vec3d tangentU = Math.abs(normal.y) > 0.5 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d tangentV = normal.crossProduct(tangentU);
        return cubeCenter.add(normal.multiply(0.5))
                .add(tangentU.multiply(u)).add(tangentV.multiply(v));
    }

    private void renderVisuals() {
        if (center == null || mark == null) return;
        RenderQueue.add("talos-aim-cube",
                new Box(center.x - 0.5, center.y - 0.5, center.z - 0.5,
                        center.x + 0.5, center.y + 0.5, center.z + 0.5),
                CUBE_COLOR, VISUAL_TTL);
        RenderQueue.add("talos-aim-mark",
                new Box(mark.x - 0.07, mark.y - 0.07, mark.z - 0.07,
                        mark.x + 0.07, mark.y + 0.07, mark.z + 0.07),
                MARK_COLOR, VISUAL_TTL);
    }

    private void snapTo(Vec3d point) {
        float[] desired = RotationHumanizer.yawPitchTo(client.player.getEyePos(), point);
        client.player.setYaw(desired[0]);
        client.player.setPitch(desired[1]);
        client.player.setHeadYaw(desired[0]);
        client.player.setBodyYaw(desired[0]);
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
