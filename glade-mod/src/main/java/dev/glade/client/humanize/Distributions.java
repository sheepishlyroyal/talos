package dev.glade.client.humanize;

/** Pure sampling helpers; all mutable random state is supplied by the caller. */
public final class Distributions {
    private Distributions() {
    }

    public static double gaussianClamped(SeededRng rng, double mean, double stdev,
                                         double min, double max) {
        if (stdev < 0 || min > max) {
            throw new IllegalArgumentException("stdev must be non-negative and min <= max");
        }
        return Math.clamp(mean + rng.nextGaussian() * stdev, min, max);
    }

    /** Log-normal with {@code medianMs == exp(mu)}. */
    public static double logNormal(SeededRng rng, double medianMs, double sigma) {
        if (!(medianMs > 0) || sigma < 0) {
            throw new IllegalArgumentException("median must be positive and sigma non-negative");
        }
        return Math.exp(Math.log(medianMs) + sigma * rng.nextGaussian());
    }

    public static boolean maybe(SeededRng rng, double probability) {
        if (probability < 0 || probability > 1) {
            throw new IllegalArgumentException("probability must be in [0, 1]");
        }
        return rng.nextDouble() < probability;
    }

    /** One AR(1) step: previous value times phi plus zero-mean Gaussian innovation. */
    public static double nextCorrelated(SeededRng rng, double prevValue, double phi, double stdev) {
        if (Math.abs(phi) >= 1 || stdev < 0) {
            throw new IllegalArgumentException("abs(phi) must be < 1 and stdev non-negative");
        }
        return prevValue * phi + rng.nextGaussian() * stdev;
    }
}
