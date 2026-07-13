package dev.talos.client.humanize;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** A category of humanization distributions and physical rotation limits. */
public record HumanizationProfile(
        String name,
        double reactionMedianMs,
        double reactionSigma,
        Range rotationSpeedDegPerTick,
        double maxAngularAccelDegPerTick2,
        double overshootProbability,
        Range overshootMagnitudeDeg,
        double timingJitterPhi,
        double pathDeviationStdev,
        Set<TrajectoryFamily> allowedTrajectoryFamilies,
        boolean alwaysVisibilityChecked) {

    public enum TrajectoryFamily { CUBIC_BEZIER, MINIMUM_JERK, PIECEWISE_LINEAR }

    public record Range(double min, double max) {
        public Range {
            if (min < 0 || min > max) throw new IllegalArgumentException("invalid range");
        }

        public double sample(SeededRng rng) {
            return min + (max - min) * rng.nextDouble();
        }
    }

    public static final HumanizationProfile RAW = new HumanizationProfile(
            "raw", 1, 0, new Range(180, 180), 360, 0, new Range(0, 0),
            0, 0, Set.of(TrajectoryFamily.MINIMUM_JERK), false);
    public static final HumanizationProfile NATURAL = new HumanizationProfile(
            "natural", 185, 0.38, new Range(8, 16), 5.5, 0.13, new Range(0.25, 1.8),
            0.62, 0.22, Set.of(TrajectoryFamily.values()), false);
    public static final HumanizationProfile PARANOID = new HumanizationProfile(
            "paranoid", 310, 0.58, new Range(4, 10), 3.0, 0.25, new Range(0.35, 2.5),
            0.78, 0.38, Set.of(TrajectoryFamily.values()), true);

    public HumanizationProfile {
        Objects.requireNonNull(name);
        Objects.requireNonNull(rotationSpeedDegPerTick);
        Objects.requireNonNull(overshootMagnitudeDeg);
        allowedTrajectoryFamilies = Set.copyOf(allowedTrajectoryFamilies);
        if (reactionMedianMs <= 0 || reactionSigma < 0 || maxAngularAccelDegPerTick2 <= 0
                || overshootProbability < 0 || overshootProbability > 1
                || Math.abs(timingJitterPhi) >= 1 || pathDeviationStdev < 0
                || allowedTrajectoryFamilies.isEmpty()) {
            throw new IllegalArgumentException("invalid humanization profile");
        }
    }

    public static HumanizationProfile byName(String name) {
        return switch (Objects.requireNonNull(name).toLowerCase(Locale.ROOT)) {
            case "raw" -> RAW;
            case "natural" -> NATURAL;
            case "paranoid" -> PARANOID;
            default -> throw new IllegalArgumentException("Unknown humanization profile: " + name);
        };
    }
}
