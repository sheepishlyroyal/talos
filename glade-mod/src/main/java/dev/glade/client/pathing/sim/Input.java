package dev.glade.client.pathing.sim;

/** Immutable controls applied for exactly one simulated tick. */
public record Input(float forward, float strafe, boolean jump, boolean sprint,
                    boolean sneak, float yaw) {
    public Input {
        forward = clamp(forward);
        strafe = clamp(strafe);
    }

    private static float clamp(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }
}
