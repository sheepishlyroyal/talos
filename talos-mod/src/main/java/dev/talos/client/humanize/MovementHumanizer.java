package dev.talos.client.humanize;

/** Minimal v1 path wobble and sprint-cadence helper. */
public final class MovementHumanizer {
    private double previousDeviation;
    private double previousSprintImpulse;

    public double lateralDeviation(SeededRng rng, HumanizationProfile profile) {
        previousDeviation = Distributions.nextCorrelated(rng, previousDeviation,
                profile.timingJitterPhi(), profile.pathDeviationStdev());
        return previousDeviation;
    }

    public boolean shouldSprint(SeededRng rng, HumanizationProfile profile,
                                double urgency01, boolean canSprint) {
        if (!canSprint) return false;
        previousSprintImpulse = Distributions.nextCorrelated(rng, previousSprintImpulse,
                0.72, 0.18);
        double threshold = profile == HumanizationProfile.RAW ? 0.05 : 0.55;
        return Math.clamp(urgency01, 0, 1) + previousSprintImpulse > threshold;
    }
}
