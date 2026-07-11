package dev.glade.client.humanize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeededRngTest {
    @Test
    void sameSeedProducesSameSequence() {
        SeededRng a = new SeededRng(123456789L);
        SeededRng b = new SeededRng(123456789L);
        for (int i = 0; i < 100; i++) {
            assertEquals(a.nextDouble(), b.nextDouble());
            assertEquals(a.nextGaussian(), b.nextGaussian());
            assertEquals(a.nextInt(37), b.nextInt(37));
        }
    }

    @Test
    void forkIsDeterministicAndDiffersFromParent() {
        SeededRng parentA = new SeededRng(42L);
        SeededRng parentB = new SeededRng(42L);
        SeededRng childA = parentA.fork();
        SeededRng childB = parentB.fork();
        double parentValue = parentA.nextDouble();
        double childValue = childA.nextDouble();
        assertEquals(childValue, childB.nextDouble());
        assertEquals(parentValue, parentB.nextDouble());
        assertNotEquals(parentValue, childValue);
    }
}
