package dev.talos.client.humanize;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mutable user tuning applied on top of the selected base {@link HumanizationProfile}:
 * per-knob overrides, an optional trajectory-family restriction, and a global
 * intensity scalar (0 = near-robotic, 1 = profile as authored, up to 3 = exaggerated).
 *
 * <p>Every value is clamped into a documented safe range so no combination of user
 * input can produce an invalid profile (the {@link HumanizationProfile} constructor
 * validation can never fire from here). Thread-safe via synchronization; mutations
 * happen on the game thread, reads may come from anywhere.
 */
public final class HumanizationOverrides {

    /** Name-keyed tunable knobs with their clamp ranges (the documented safe bounds). */
    public enum Knob {
        REACTION_MEDIAN_MS("reaction_median_ms", 1, 5000),
        REACTION_SIGMA("reaction_sigma", 0, 2),
        ROTATION_SPEED_MIN("rotation_speed_min", 0.5, 360),
        ROTATION_SPEED_MAX("rotation_speed_max", 0.5, 360),
        MAX_ACCEL("max_accel", 0.5, 360),
        OVERSHOOT_PROB("overshoot_prob", 0, 1),
        OVERSHOOT_MIN("overshoot_min", 0, 30),
        OVERSHOOT_MAX("overshoot_max", 0, 30),
        JITTER_PHI("jitter_phi", 0, 0.95),
        PATH_DEVIATION("path_deviation", 0, 2),
        VISIBILITY_CHECK("visibility_check", 0, 1);

        public final String key;
        public final double min;
        public final double max;

        Knob(String key, double min, double max) {
            this.key = key;
            this.min = min;
            this.max = max;
        }

        public double clamp(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Value must be finite");
            return Math.min(max, Math.max(min, value));
        }

        public static Knob byKey(String key) {
            String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
            for (Knob knob : values()) {
                if (knob.key.equals(k)) return knob;
            }
            StringBuilder names = new StringBuilder();
            for (Knob knob : values()) {
                if (names.length() > 0) names.append(", ");
                names.append(knob.key);
            }
            throw new IllegalArgumentException("Unknown humanization knob '" + key
                    + "'. Valid knobs: " + names);
        }
    }

    private static final double MAX_INTENSITY = 3.0;

    private final EnumMap<Knob, Double> values = new EnumMap<>(Knob.class);
    private double intensity = 1.0;
    private Set<HumanizationProfile.TrajectoryFamily> families; // null = profile default

    public synchronized void set(Knob knob, double value) {
        values.put(knob, knob.clamp(value));
    }

    public synchronized void setIntensity(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Intensity must be finite");
        intensity = Math.min(MAX_INTENSITY, Math.max(0.0, value));
    }

    public synchronized double intensity() {
        return intensity;
    }

    /** Restrict trajectory families ({@code null} restores the profile default). */
    public synchronized void setFamilies(Set<HumanizationProfile.TrajectoryFamily> selected) {
        if (selected != null && selected.isEmpty())
            throw new IllegalArgumentException("At least one trajectory family is required");
        families = selected == null ? null : Set.copyOf(selected);
    }

    /**
     * Parses a comma-separated family list. Accepts enum names and the friendly
     * aliases used from Python: bezier, min_jerk, linear. Blank/null restores default.
     */
    public synchronized void setFamilies(String csv) {
        if (csv == null || csv.isBlank()) {
            families = null;
            return;
        }
        java.util.EnumSet<HumanizationProfile.TrajectoryFamily> parsed =
                java.util.EnumSet.noneOf(HumanizationProfile.TrajectoryFamily.class);
        for (String raw : csv.split(",")) {
            String token = raw.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;
            switch (token) {
                case "bezier", "cubic_bezier" -> parsed.add(HumanizationProfile.TrajectoryFamily.CUBIC_BEZIER);
                case "min_jerk", "minimum_jerk" -> parsed.add(HumanizationProfile.TrajectoryFamily.MINIMUM_JERK);
                case "linear", "piecewise_linear" -> parsed.add(HumanizationProfile.TrajectoryFamily.PIECEWISE_LINEAR);
                default -> throw new IllegalArgumentException("Unknown trajectory family '" + token
                        + "'. Valid: bezier, min_jerk, linear");
            }
        }
        if (parsed.isEmpty()) throw new IllegalArgumentException("At least one trajectory family is required");
        families = Set.copyOf(parsed);
    }

    public synchronized void reset() {
        values.clear();
        intensity = 1.0;
        families = null;
    }

    public synchronized boolean isIdentity() {
        return values.isEmpty() && intensity == 1.0 && families == null;
    }

    /** Knob overrides only (not intensity/families), keyed by knob name — for persistence/UI. */
    public synchronized Map<String, Double> snapshot() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Knob, Double> e : values.entrySet()) out.put(e.getKey().key, e.getValue());
        return out;
    }

    /** Comma-separated friendly family names, or "" when the profile default applies. */
    public synchronized String familiesCsv() {
        if (families == null) return "";
        StringBuilder sb = new StringBuilder();
        for (HumanizationProfile.TrajectoryFamily f : families) {
            if (sb.length() > 0) sb.append(",");
            sb.append(switch (f) {
                case CUBIC_BEZIER -> "bezier";
                case MINIMUM_JERK -> "min_jerk";
                case PIECEWISE_LINEAR -> "linear";
            });
        }
        return sb.toString();
    }

    /**
     * Base profile + per-knob overrides, then intensity scaling. Always yields a
     * valid profile: overrides are pre-clamped and the scaled results are clamped
     * again, so aim can be tuned but never broken.
     */
    public synchronized HumanizationProfile apply(HumanizationProfile base) {
        if (isIdentity()) return base;

        double reaction = value(Knob.REACTION_MEDIAN_MS, base.reactionMedianMs());
        double sigma = value(Knob.REACTION_SIGMA, base.reactionSigma());
        double rotMin = value(Knob.ROTATION_SPEED_MIN, base.rotationSpeedDegPerTick().min());
        double rotMax = value(Knob.ROTATION_SPEED_MAX, base.rotationSpeedDegPerTick().max());
        double accel = value(Knob.MAX_ACCEL, base.maxAngularAccelDegPerTick2());
        double overshootProb = value(Knob.OVERSHOOT_PROB, base.overshootProbability());
        double overshootMin = value(Knob.OVERSHOOT_MIN, base.overshootMagnitudeDeg().min());
        double overshootMax = value(Knob.OVERSHOOT_MAX, base.overshootMagnitudeDeg().max());
        double jitter = value(Knob.JITTER_PHI, base.timingJitterPhi());
        double deviation = value(Knob.PATH_DEVIATION, base.pathDeviationStdev());
        boolean visibility = values.containsKey(Knob.VISIBILITY_CHECK)
                ? values.get(Knob.VISIBILITY_CHECK) >= 0.5
                : base.alwaysVisibilityChecked();

        double i = intensity;
        // "Humanness" knobs scale with intensity; rotation gets faster as intensity drops.
        reaction = Math.max(1, 1 + (reaction - 1) * i);
        overshootProb = Math.min(1, overshootProb * i);
        overshootMin = Math.min(30, overshootMin * i);
        overshootMax = Math.min(30, overshootMax * i);
        jitter = Math.min(0.95, jitter * i);
        deviation = Math.min(2, deviation * i);
        double speedScale = 1.0 / Math.max(0.2, i);
        rotMin = clamp(rotMin * speedScale, 0.5, 360);
        rotMax = clamp(rotMax * speedScale, 0.5, 360);
        accel = clamp(accel * speedScale, 0.5, 360);

        if (rotMin > rotMax) rotMin = rotMax;
        if (overshootMin > overshootMax) overshootMin = overshootMax;

        Set<HumanizationProfile.TrajectoryFamily> fams =
                families != null ? families : base.allowedTrajectoryFamilies();

        return new HumanizationProfile(base.name(), reaction, sigma,
                new HumanizationProfile.Range(rotMin, rotMax), accel, overshootProb,
                new HumanizationProfile.Range(overshootMin, overshootMax), jitter, deviation,
                fams, visibility);
    }

    private double value(Knob knob, double fallback) {
        Double v = values.get(knob);
        return v != null ? v : fallback;
    }

    private static double clamp(double v, double min, double max) {
        return Math.min(max, Math.max(min, v));
    }
}
