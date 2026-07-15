package dev.talos.client.humanize;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HumanizerTest {
    @Test
    void humanModeOwnsHumanizedAim() {
        Humanizer humanizer = new Humanizer();

        assertFalse(humanizer.humanMode());
        assertFalse(humanizer.humanizedAim());

        humanizer.setHumanMode(true);
        assertTrue(humanizer.humanMode());
        assertTrue(humanizer.humanizedAim());

        humanizer.setHumanMode(false);
        assertFalse(humanizer.humanMode());
        assertFalse(humanizer.humanizedAim());
    }
}
