package dev.talos.client.humanize;

/** Per-action correlated reaction-delay sampler. */
public final class TimingHumanizer {
    private double previousJitter;

    public long sampleDelayTicks(HumanizationProfile profile, double difficulty01, SeededRng rng) {
        double difficulty = Math.clamp(difficulty01, 0, 1);
        previousJitter = Distributions.nextCorrelated(rng, previousJitter,
                profile.timingJitterPhi(), 0.12 + profile.reactionSigma() * 0.10);
        double milliseconds = Distributions.logNormal(rng, profile.reactionMedianMs(),
                profile.reactionSigma());
        milliseconds *= (0.65 + 0.85 * difficulty) * Math.exp(previousJitter);
        if (Distributions.maybe(rng, 0.018)) milliseconds *= 2.5 + rng.nextDouble() * 3.5;
        return Math.max(0, Math.round(milliseconds / 50.0));
    }
}
