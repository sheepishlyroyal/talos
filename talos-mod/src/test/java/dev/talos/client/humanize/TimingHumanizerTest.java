package dev.talos.client.humanize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimingHumanizerTest {
    @Test
    void sameSeedAndInputsAreDeterministic() {
        TimingHumanizer a = new TimingHumanizer();
        TimingHumanizer b = new TimingHumanizer();
        SeededRng rngA = new SeededRng(2024L);
        SeededRng rngB = new SeededRng(2024L);
        for (int i = 0; i < 500; i++) {
            assertEquals(a.sampleDelayTicks(HumanizationProfile.NATURAL, 0.4, rngA),
                    b.sampleDelayTicks(HumanizationProfile.NATURAL, 0.4, rngB));
        }
    }

    @Test
    void meanDelayScalesWithDifficulty() {
        double easy = meanForDifficulty(0, 811L);
        double hard = meanForDifficulty(1, 811L);
        assertTrue(hard > easy * 1.7, "higher difficulty should materially increase delay");
    }

    private static double meanForDifficulty(double difficulty, long seed) {
        TimingHumanizer timing = new TimingHumanizer();
        SeededRng rng = new SeededRng(seed);
        long sum = 0;
        int count = 20_000;
        for (int i = 0; i < count; i++) {
            sum += timing.sampleDelayTicks(HumanizationProfile.NATURAL, difficulty, rng);
        }
        return (double) sum / count;
    }
}
