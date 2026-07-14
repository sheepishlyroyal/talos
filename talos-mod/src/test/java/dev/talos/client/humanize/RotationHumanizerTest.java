package dev.talos.client.humanize;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class RotationHumanizerTest {
    private final RotationHumanizer humanizer = new RotationHumanizer();

    @Test
    void reachesTargetWithinEpsilon() {
        List<float[]> steps = humanizer.plan(12, -8, 117, 36,
                HumanizationProfile.NATURAL, new SeededRng(5L)).steps();
        float[] last = steps.getLast();
        assertEquals(117, last[0], 1.0e-4);
        assertEquals(36, last[1], 1.0e-4);
    }

    @Test
    void noStepExceedsAngularVelocityBound() {
        HumanizationProfile profile = fixedSpeedProfile(7);
        List<float[]> steps = humanizer.plan(-40, 15, 130, -35, profile, new SeededRng(8L)).steps();
        float yaw = -40, pitch = 15;
        for (float[] step : steps) {
            double dy = wrap(step[0] - yaw);
            double dp = step[1] - pitch;
            assertTrue(Math.hypot(dy, dp) <= 7.0001, "velocity bound exceeded");
            yaw = step[0]; pitch = step[1];
        }
    }

    @Test
    void seamWrapTakesShorterYawPath() {
        List<float[]> steps = humanizer.plan(179, 0, -179, 0,
                fixedSpeedProfile(1), new SeededRng(12L)).steps();
        double traveled = 0;
        float previous = 179;
        for (float[] step : steps) {
            double delta = wrap(step[0] - previous);
            assertTrue(delta >= -1.0e-5, "should travel positive two-degree route");
            traveled += Math.abs(delta);
            previous = step[0];
        }
        assertEquals(2, traveled, 1.0e-3);
    }

    private static HumanizationProfile fixedSpeedProfile(double speed) {
        return new HumanizationProfile("test", 100, 0.2,
                new HumanizationProfile.Range(speed, speed), speed,
                0, new HumanizationProfile.Range(0, 0), 0.5, 0,
                java.util.Set.of(HumanizationProfile.TrajectoryFamily.MINIMUM_JERK), false);
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped <= -180) wrapped += 360;
        if (wrapped > 180) wrapped -= 360;
        return wrapped;
    }
}
