package dev.glade.client.humanize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DistributionsTest {
    @Test
    void helpersAreDeterministicAndClamped() {
        SeededRng a = new SeededRng(17L);
        SeededRng b = new SeededRng(17L);
        for (int i = 0; i < 1_000; i++) {
            double x = Distributions.gaussianClamped(a, 4, 20, 1, 7);
            double y = Distributions.gaussianClamped(b, 4, 20, 1, 7);
            assertEquals(x, y);
            assertTrue(x >= 1 && x <= 7);
            assertEquals(Distributions.maybe(a, 0.37), Distributions.maybe(b, 0.37));
        }
    }

    @Test
    void logNormalIsPositiveAndRightSkewed() {
        SeededRng rng = new SeededRng(91L);
        int count = 40_001;
        double[] samples = new double[count];
        double sum = 0;
        for (int i = 0; i < count; i++) {
            samples[i] = Distributions.logNormal(rng, 200, 0.8);
            assertTrue(samples[i] > 0);
            sum += samples[i];
        }
        java.util.Arrays.sort(samples);
        assertTrue(sum / count > samples[count / 2]);
    }

    @Test
    void nextCorrelatedProducesMeasurableAr1Correlation() {
        double correlated = lagOneCorrelation(0.82, 73L);
        double baseline = lagOneCorrelation(0, 73L);
        assertTrue(correlated > 0.72, "expected strong positive autocorrelation");
        assertTrue(Math.abs(baseline) < 0.08, "phi=0 baseline should be nearly uncorrelated");
        assertTrue(correlated > baseline + 0.6);
    }

    private static double lagOneCorrelation(double phi, long seed) {
        SeededRng rng = new SeededRng(seed);
        int n = 20_000;
        double[] values = new double[n];
        for (int i = 1; i < n; i++) values[i] = Distributions.nextCorrelated(rng, values[i - 1], phi, 1);
        double meanX = 0, meanY = 0;
        for (int i = 1; i < n; i++) { meanX += values[i - 1]; meanY += values[i]; }
        meanX /= n - 1; meanY /= n - 1;
        double covariance = 0, varianceX = 0, varianceY = 0;
        for (int i = 1; i < n; i++) {
            double x = values[i - 1] - meanX, y = values[i] - meanY;
            covariance += x * y; varianceX += x * x; varianceY += y * y;
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }
}
