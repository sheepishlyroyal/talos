package dev.talos.client.humanize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Pure, seed-deterministic rotation trajectory planning. */
public final class RotationHumanizer {
    private static final double EPSILON = 1.0e-5;

    public static float[] yawPitchTo(Vec3 eye, Vec3 target) {
        double dx = target.x() - eye.x();
        double dy = target.y() - eye.y();
        double dz = target.z() - eye.z();
        double horizontal = Math.hypot(dx, dz);
        float yaw = wrapYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    public RotationPlan plan(float curYaw, float curPitch, float targetYaw, float targetPitch,
                             HumanizationProfile profile, SeededRng rng) {
        double yawDelta = wrapYaw(targetYaw - curYaw);
        double pitchDelta = targetPitch - curPitch;
        double distance = Math.hypot(yawDelta, pitchDelta);
        if (distance < EPSILON) return new RotationPlan(List.of(new float[]{targetYaw, targetPitch}));

        double maxSpeed = profile.rotationSpeedDegPerTick().sample(rng);
        int ticks = Math.max(2, (int) Math.ceil(distance / Math.max(maxSpeed * 0.72, EPSILON)));
        var families = profile.allowedTrajectoryFamilies().stream().sorted().toList();
        HumanizationProfile.TrajectoryFamily family = families.get(rng.nextInt(families.size()));
        List<double[]> desired = desiredPath(family, curYaw, curPitch, yawDelta, pitchDelta,
                ticks, profile.pathDeviationStdev(), rng);

        if (Distributions.maybe(rng, profile.overshootProbability())) {
            double magnitude = profile.overshootMagnitudeDeg().sample(rng);
            double scale = magnitude / Math.max(distance, EPSILON);
            desired.add(new double[]{curYaw + yawDelta * (1 + scale), curPitch + pitchDelta * (1 + scale)});
        }
        desired.add(new double[]{curYaw + yawDelta, targetPitch});

        List<double[]> bounded = constrain(desired, curYaw, curPitch, maxSpeed,
                profile.maxAngularAccelDegPerTick2());
        double[] last = bounded.getLast();
        settle(bounded, last[0], last[1], curYaw + yawDelta, targetPitch, maxSpeed,
                profile.maxAngularAccelDegPerTick2());

        List<float[]> result = new ArrayList<>(bounded.size());
        for (double[] point : bounded) {
            result.add(new float[]{wrapYaw((float) point[0]), (float) point[1]});
        }
        result.set(result.size() - 1, new float[]{wrapYaw(targetYaw), targetPitch});
        return new RotationPlan(result);
    }

    private static List<double[]> desiredPath(HumanizationProfile.TrajectoryFamily family,
                                               double y0, double p0, double dy, double dp,
                                               int ticks, double deviation, SeededRng rng) {
        List<double[]> path = new ArrayList<>();
        double length = Math.max(Math.hypot(dy, dp), EPSILON);
        double normalY = -dp / length;
        double normalP = dy / length;
        double c1 = rng.nextGaussian() * deviation;
        double c2 = rng.nextGaussian() * deviation;
        double noise = 0;
        for (int i = 1; i <= ticks; i++) {
            double t = (double) i / ticks;
            double progress;
            double offset = 0;
            switch (family) {
                case CUBIC_BEZIER -> {
                    double u = 1 - t;
                    progress = 3 * u * u * t * 0.30 + 3 * u * t * t * 0.72 + t * t * t;
                    offset = 3 * u * u * t * c1 + 3 * u * t * t * c2;
                }
                case MINIMUM_JERK -> progress = 10 * Math.pow(t, 3) - 15 * Math.pow(t, 4) + 6 * Math.pow(t, 5);
                case PIECEWISE_LINEAR -> {
                    progress = t;
                    noise = Distributions.nextCorrelated(rng, noise, 0.65, deviation);
                    offset = noise * Math.sin(Math.PI * t);
                }
                default -> throw new IllegalStateException("Unhandled trajectory family");
            }
            path.add(new double[]{y0 + dy * progress + normalY * offset,
                    p0 + dp * progress + normalP * offset});
        }
        return path;
    }

    private static List<double[]> constrain(List<double[]> desired, double yaw, double pitch,
                                             double maxSpeed, double maxAccel) {
        List<double[]> output = new ArrayList<>();
        double vy = 0, vp = 0;
        for (double[] point : desired) {
            double[] velocity = clampVector(point[0] - yaw, point[1] - pitch, maxSpeed);
            double[] acceleration = clampVector(velocity[0] - vy, velocity[1] - vp, maxAccel);
            velocity[0] = vy + acceleration[0];
            velocity[1] = vp + acceleration[1];
            velocity = clampVector(velocity[0], velocity[1], maxSpeed);
            yaw += velocity[0]; pitch += velocity[1];
            vy = velocity[0]; vp = velocity[1];
            output.add(new double[]{yaw, pitch});
        }
        return output;
    }

    private static void settle(List<double[]> points, double yaw, double pitch,
                               double targetYaw, double targetPitch, double maxSpeed, double maxAccel) {
        double[] previous = points.size() < 2 ? new double[]{0, 0} : new double[]{
                yaw - points.get(points.size() - 2)[0], pitch - points.get(points.size() - 2)[1]};
        for (int guard = 0; guard < 10_000; guard++) {
            double ry = targetYaw - yaw, rp = targetPitch - pitch;
            if (Math.hypot(ry, rp) < EPSILON) return;
            double[] exactAccel = {ry - previous[0], rp - previous[1]};
            if (Math.hypot(ry, rp) <= maxSpeed && Math.hypot(exactAccel[0], exactAccel[1]) <= maxAccel) {
                points.add(new double[]{targetYaw, targetPitch});
                return;
            }
            double distance = Math.hypot(ry, rp);
            double brakingSpeed = Math.sqrt(2 * maxAccel * distance);
            double[] wanted = clampVector(ry, rp, Math.min(maxSpeed, brakingSpeed));
            double[] accel = clampVector(wanted[0] - previous[0], wanted[1] - previous[1], maxAccel);
            double[] velocity = clampVector(previous[0] + accel[0], previous[1] + accel[1], maxSpeed);
            yaw += velocity[0]; pitch += velocity[1];
            points.add(new double[]{yaw, pitch});
            previous = velocity;
        }
        throw new IllegalStateException("rotation plan failed to converge");
    }

    private static double[] clampVector(double x, double y, double max) {
        double length = Math.hypot(x, y);
        if (length <= max || length == 0) return new double[]{x, y};
        double scale = max / length;
        return new double[]{x * scale, y * scale};
    }

    /** Converts MathHelper's [-180, 180) result to the documented (-180, 180] convention. */
    private static float wrapYaw(float degrees) {
        float wrapped = Mth.wrapDegrees(degrees);
        return wrapped == -180.0f ? 180.0f : wrapped;
    }

    public static final class RotationPlan {
        private final List<float[]> steps;
        private int cursor;

        private RotationPlan(List<float[]> steps) {
            List<float[]> copies = new ArrayList<>(steps.size());
            steps.forEach(step -> copies.add(step.clone()));
            this.steps = Collections.unmodifiableList(copies);
        }

        public boolean hasNext() { return cursor < steps.size(); }
        public float[] next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            return steps.get(cursor++).clone();
        }
        public List<float[]> steps() {
            return steps.stream().map(float[]::clone).toList();
        }
        public int size() { return steps.size(); }
    }
}
