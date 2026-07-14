package dev.talos.client.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Validates that {@link RaycastMath#local} builds the exact caret ({@code ^left ^up ^forward})
 * frame vanilla uses — pure geometry, no world needed. Minecraft yaw 0 faces +Z (south), yaw 90
 * faces -X (west); pitch is positive downward.
 */
class RaycastMathTest {
    private static final Vec3 ORIGIN = new Vec3(0, 0, 0);
    private static final double EPS = 1.0E-6;

    private static void assertVec(Vec3 actual, double x, double y, double z) {
        assertEquals(x, actual.x, EPS, "x");
        assertEquals(y, actual.y, EPS, "y");
        assertEquals(z, actual.z, EPS, "z");
    }

    @Test
    void forwardFacingSouthIsPositiveZ() {
        // yaw 0, pitch 0 → looking south (+Z). ^ ^ 5 is 5 blocks along +Z.
        assertVec(RaycastMath.local(ORIGIN, 0f, 0f, 0, 0, 5), 0, 0, 5);
    }

    @Test
    void leftFacingSouthIsPositiveX() {
        // Facing south, your left hand points east (+X). ^5 ^ ^ is 5 to the left.
        assertVec(RaycastMath.local(ORIGIN, 0f, 0f, 5, 0, 0), 5, 0, 0);
    }

    @Test
    void upFacingSouthIsPositiveY() {
        assertVec(RaycastMath.local(ORIGIN, 0f, 0f, 0, 5, 0), 0, 5, 0);
    }

    @Test
    void forwardFacingWestIsNegativeX() {
        // yaw 90 → looking west (-X).
        assertVec(RaycastMath.local(ORIGIN, 90f, 0f, 0, 0, 5), -5, 0, 0);
    }

    @Test
    void forwardLookingStraightDownIsNegativeY() {
        // pitch 90 → looking straight down; forward follows the gaze into the ground.
        assertVec(RaycastMath.local(ORIGIN, 0f, 90f, 0, 0, 5), 0, -5, 0);
    }

    @Test
    void negativeForwardIsBehind() {
        // ^ ^ -3 while facing south is 3 blocks behind (−Z).
        assertVec(RaycastMath.local(ORIGIN, 0f, 0f, 0, 0, -3), 0, 0, -3);
    }

    @Test
    void originOffsetIsAdded() {
        Vec3 eye = new Vec3(10, 64, -20);
        assertVec(RaycastMath.local(eye, 0f, 0f, 0, 0, 5), 10, 64, -15);
    }
}
