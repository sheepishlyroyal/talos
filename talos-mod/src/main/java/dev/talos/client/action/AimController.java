package dev.talos.client.action;

import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.RotationHumanizer;
import dev.talos.client.humanize.SeededRng;
import dev.talos.client.render.RenderQueue;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
    private static final float AIM_EPSILON_DEGREES = 1.25F;
    private static final double SLOW_ZONE_METERS = 0.4;
    private static final double REPLAN_DISTANCE_SQUARED = 0.35 * 0.35;
    private static final int CUBE_COLOR = 0xFFE14D;   // yellow guide cube
    private static final int MARK_COLOR = 0xFF3333;   // the red X
    private static final int VISUAL_TTL = 15;

    private final Minecraft client;
    private final SeededRng rng;
    private Vec3 center;   // the point the caller asked to look at (cube center)
    private Vec3 mark;     // the chosen red-X spot actually aimed for
    private double fastSensitivity;
    private double slowSensitivity;
    private double currentSensitivity;
    // Quadratic (parabolic) per-session modulation: both peak mid-approach and vanish at
    // the ends, so the speed-up/slow-down and the up/down bow are smooth, never linear.
    private double speedAmplitude;   // +-: randomly faster or slower mid-flight
    private double bowDegrees;       // +-: the path arcs slightly up or slightly down
    private double initialMiss = -1.0;
    private int pathRenderCooldown;
    private int lastPathCount;

    public AimController(Minecraft client, RotationHumanizer humanizer,
                         HumanizationProfile profile, long seed) {
        this.client = Objects.requireNonNull(client);
        this.rng = new SeededRng(seed);
    }

    public void aimAt(BlockPos pos) {
        aimAt(Vec3.atCenterOf(pos));
    }

    public void aimAt(Entity entity) {
        aimAt(entity.getBoundingBox().getCenter());
    }

    public void aimAt(Vec3 newTarget) {
        Objects.requireNonNull(newTarget);
        if (client.player == null) return;
        if (center != null && center.distanceToSqr(newTarget) <= REPLAN_DISTANCE_SQUARED) {
            center = newTarget; // small drift: keep the session, keep the mark's face offset
            return;
        }
        center = newTarget;
        mark = chooseMark(newTarget);
        // Fresh sensitivities per aim session; the pair itself is part of the human variance.
        fastSensitivity = 18.0 + rng.nextDouble() * 22.0;   // deg/tick far out
        slowSensitivity = 2.5 + rng.nextDouble() * 3.5;     // deg/tick in the slow zone
        currentSensitivity = fastSensitivity * (0.75 + rng.nextDouble() * 0.25);
        speedAmplitude = (rng.nextDouble() - 0.5) * 0.36;   // up to +-18%: felt, not flashy
        bowDegrees = (1.0 + rng.nextDouble() * 3.0) * (rng.nextDouble() < 0.5 ? -1.0 : 1.0);
        initialMiss = -1.0;
        renderVisuals();
    }

    /** Advances one tick of rotation toward the mark. Call every tick until isAimed(). */
    public void tick() {
        if (client.player == null || center == null) return;
        if (!dev.talos.client.TalosClient.humanizer().humanizedAim()) {
            snapTo(center);
            return;
        }
        renderVisuals();

        Vec3 eye = client.player.getEyePosition();
        float[] desired = RotationHumanizer.yawPitchTo(eye, mark);

        // Where the current look ray passes at the mark's distance: its miss distance in
        // meters is the "how close to the cube" measure that gates the slowdown.
        double distance = eye.distanceTo(mark);
        Vec3 rayPoint = eye.add(client.player.getForward().scale(distance));
        double missMeters = rayPoint.distanceTo(mark);
        if (initialMiss < 0.0) initialMiss = Math.max(missMeters, 1.0E-3);

        // Progress through the approach drives the parabolic modulation 4p(1-p): zero at
        // both ends, peaking mid-flight — the quadratic smoothness of a real hand.
        double progress = Mth.clamp(1.0 - missMeters / initialMiss, 0.0, 1.0);
        double parabola = 4.0 * progress * (1.0 - progress);
        float pitchTarget = Mth.clamp(
                desired[1] + (float) (bowDegrees * parabola), -90.0F, 90.0F);

        float yawError = Mth.wrapDegrees(desired[0] - client.player.getYRot());
        float pitchError = pitchTarget - client.player.getXRot();
        double angularError = Math.max(Math.abs(yawError), Math.abs(pitchError));

        // Smooth blend, never a step: outside 3x the slow zone -> fast; inside the slow
        // zone -> slow; the sensitivity itself is additionally low-pass filtered.
        double blend = Mth.clamp(
                (missMeters - SLOW_ZONE_METERS) / (SLOW_ZONE_METERS * 2.0), 0.0, 1.0);
        blend = blend * blend * (3.0 - 2.0 * blend); // smoothstep
        double targetSensitivity = slowSensitivity
                + (fastSensitivity - slowSensitivity) * blend;
        currentSensitivity += (targetSensitivity - currentSensitivity) * 0.35;

        if (angularError < 1.0E-3) return;
        // Per-tick jitter plus the parabolic speed swell/sag chosen for this session.
        double step = currentSensitivity * (0.9 + rng.nextDouble() * 0.2)
                * (1.0 + speedAmplitude * parabola);
        double fraction = Math.min(1.0, step / angularError);
        float yaw = client.player.getYRot() + (float) (yawError * fraction);
        float pitch = Mth.clamp(
                client.player.getXRot() + (float) (pitchError * fraction), -90.0F, 90.0F);
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        client.player.setYHeadRot(yaw);
        client.player.setYBodyRot(yaw);
    }

    public boolean isAimed() {
        if (client.player == null || center == null) return false;
        if (!dev.talos.client.TalosClient.humanizer().humanizedAim()) return true;
        float[] desired = RotationHumanizer.yawPitchTo(client.player.getEyePosition(), mark);
        return Math.abs(Mth.wrapDegrees(desired[0] - client.player.getYRot()))
                <= AIM_EPSILON_DEGREES
                && Math.abs(desired[1] - client.player.getXRot()) <= AIM_EPSILON_DEGREES;
    }

    /**
     * Picks the red-X spot: faces are weighted by their visible flat area (the dot of the
     * face normal with the direction to the eye — a face seen square-on offers its whole
     * square meter, a grazing face almost none), then the spot lands center-biased on the
     * chosen face so the cube's true center remains the expected value.
     */
    private Vec3 chooseMark(Vec3 cubeCenter) {
        return markOn(cubeCenter, client.player.getEyePosition(), rng);
    }

    /**
     * Shared cube-aim mark chooser (also used by navigation's gaze): picks a spot on a
     * player-visible face of the 1m cube at {@code cubeCenter}, faces weighted by visible
     * area, spot center-biased on the chosen face.
     */
    public static Vec3 markOn(Vec3 cubeCenter, Vec3 eye, SeededRng rng) {
        Vec3 toEye = eye.subtract(cubeCenter);
        if (toEye.lengthSqr() < 1.0E-6) return cubeCenter;
        toEye = toEye.normalize();
        Vec3[] normals = {
                new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0),
                new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};
        double[] weights = new double[6];
        double total = 0.0;
        for (int i = 0; i < 6; i++) {
            weights[i] = Math.max(0.0, normals[i].dot(toEye));
            total += weights[i];
        }
        Vec3 normal = normals[0];
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
        Vec3 tangentU = Math.abs(normal.y) > 0.5 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 tangentV = normal.cross(tangentU);
        return cubeCenter.add(normal.scale(0.5))
                .add(tangentU.scale(u)).add(tangentV.scale(v));
    }

    private void renderVisuals() {
        if (center == null || mark == null) return;
        RenderQueue.add("talos-aim-cube",
                new AABB(center.x - 0.5, center.y - 0.5, center.z - 0.5,
                        center.x + 0.5, center.y + 0.5, center.z + 0.5),
                CUBE_COLOR, VISUAL_TTL);
        RenderQueue.add("talos-aim-mark",
                new AABB(mark.x - 0.07, mark.y - 0.07, mark.z - 0.07,
                        mark.x + 0.07, mark.y + 0.07, mark.z + 0.07),
                MARK_COLOR, VISUAL_TTL);
        if (--pathRenderCooldown <= 0) {
            pathRenderCooldown = 4;
            renderPath();
        }
    }

    /**
     * The red line: a forward simulation of this exact controller (same sensitivities, same
     * parabolic bow and speed swell, jitter fixed at its mean) traced as where the look ray
     * crosses the mark's depth each tick — the curve the crosshair is about to draw.
     */
    private void renderPath() {
        if (client.player == null) return;
        Vec3 eye = client.player.getEyePosition();
        double distance = eye.distanceTo(mark);
        float[] desired = RotationHumanizer.yawPitchTo(eye, mark);
        double yaw = client.player.getYRot();
        double pitch = client.player.getXRot();
        double sensitivity = currentSensitivity;
        double startMiss = initialMiss > 0.0 ? initialMiss : 1.0;
        int samples = 0;
        for (int step = 0; step < 48; step++) {
            Vec3 look = lookVector(yaw, pitch);
            Vec3 rayPoint = eye.add(look.scale(distance));
            double miss = rayPoint.distanceTo(mark);
            RenderQueue.add("talos-aim-path:" + samples,
                    new AABB(rayPoint.x - 0.02, rayPoint.y - 0.02, rayPoint.z - 0.02,
                            rayPoint.x + 0.02, rayPoint.y + 0.02, rayPoint.z + 0.02),
                    MARK_COLOR, VISUAL_TTL);
            samples++;
            double progress = Mth.clamp(1.0 - miss / startMiss, 0.0, 1.0);
            double parabola = 4.0 * progress * (1.0 - progress);
            double pitchTarget = Mth.clamp(desired[1] + bowDegrees * parabola, -90.0, 90.0);
            double yawError = Mth.wrapDegrees(desired[0] - yaw);
            double pitchError = pitchTarget - pitch;
            double angularError = Math.max(Math.abs(yawError), Math.abs(pitchError));
            if (angularError < AIM_EPSILON_DEGREES * 0.5) break;
            double blend = Mth.clamp(
                    (miss - SLOW_ZONE_METERS) / (SLOW_ZONE_METERS * 2.0), 0.0, 1.0);
            blend = blend * blend * (3.0 - 2.0 * blend);
            sensitivity += (slowSensitivity + (fastSensitivity - slowSensitivity) * blend
                    - sensitivity) * 0.35;
            double stepSize = sensitivity * (1.0 + speedAmplitude * parabola);
            double fraction = Math.min(1.0, stepSize / angularError);
            yaw += yawError * fraction;
            pitch = Mth.clamp(pitch + pitchError * fraction, -90.0, 90.0);
        }
        for (int i = samples; i < lastPathCount; i++) RenderQueue.remove("talos-aim-path:" + i);
        lastPathCount = samples;
    }

    private static Vec3 lookVector(double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosPitch = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
    }

    /** Runs a standalone aim session as a scheduled task until converged (for /talos look). */
    public static void startTask(Minecraft client, Vec3 target, long seed) {
        AimController controller = new AimController(client, null, null, seed);
        controller.aimAt(target);
        // A snap-mode controller is already logically complete according to isAimed(), so
        // apply its one tick here rather than enqueueing a task whose body would never run.
        if (!dev.talos.client.TalosClient.humanizer().humanizedAim()) {
            controller.tick();
            return;
        }
        dev.talos.client.TalosClient.taskScheduler().addTask("talos-look",
                new dev.talos.client.task.TalosTask() {
                    private int ticks;
                    @Override public void initialize() { }
                    @Override public boolean condition() {
                        return client.player != null && ticks < 100 && !controller.isAimed();
                    }
                    @Override public void increment() { ticks++; }
                    @Override public void body() { controller.tick(); scheduleDelay(); }
                    @Override public java.util.Set<Object> getMutexKeys() {
                        return java.util.Set.of("talos-aim");
                    }
                });
    }

    private void snapTo(Vec3 point) {
        float[] desired = RotationHumanizer.yawPitchTo(client.player.getEyePosition(), point);
        client.player.setYRot(desired[0]);
        client.player.setXRot(desired[1]);
        client.player.setYHeadRot(desired[0]);
        client.player.setYBodyRot(desired[0]);
    }

    public boolean hasLineOfSight(Vec3 targetPoint) {
        if (client.player == null || client.level == null) {
            return false;
        }
        var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), targetPoint,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }
}
