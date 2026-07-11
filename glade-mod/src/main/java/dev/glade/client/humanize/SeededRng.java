package dev.glade.client.humanize;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** A deterministic, action-scoped random stream. This class is not thread-safe. */
public final class SeededRng {
    private final RandomGenerator generator;

    public SeededRng(long seed) {
        this(new SplittableRandom(seed));
    }

    private SeededRng(RandomGenerator generator) {
        this.generator = generator;
    }

    public double nextDouble() {
        return generator.nextDouble();
    }

    public double nextGaussian() {
        return generator.nextGaussian();
    }

    public int nextInt(int bound) {
        return generator.nextInt(bound);
    }

    /** Consumes one parent value and uses it as the deterministic child seed. */
    public SeededRng fork() {
        return new SeededRng(generator.nextLong());
    }
}
