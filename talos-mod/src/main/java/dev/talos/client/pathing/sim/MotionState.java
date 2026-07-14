package dev.talos.client.pathing.sim;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Immutable input/output snapshot for one invocation of {@link PlayerMotion#step}. */
public record MotionState(
        Vec3 position,
        Vec3 velocity,
        boolean onGround,
        Pose pose,
        Fluid fluid,
        boolean bumpedHorizontally,
        int jumpCooldown) {

    public MotionState {
        if (position == null || velocity == null || pose == null || fluid == null) {
            throw new IllegalArgumentException("MotionState fields must not be null");
        }
        if (jumpCooldown < 0) throw new IllegalArgumentException("jumpCooldown must be >= 0");
    }

    /** Convenience constructor for a state before any simulated collision outcome exists. */
    public MotionState(Vec3 position, Vec3 velocity, boolean onGround, Pose pose) {
        this(position, velocity, onGround, pose, Fluid.NONE, false, 0);
    }

    /**
     * Cooldown-free constructor kept for callers snapshotting the live player, whose vanilla
     * jumpingCooldown is not readable client-side; starting at 0 errs one hop early at most.
     */
    public MotionState(Vec3 position, Vec3 velocity, boolean onGround, Pose pose,
            Fluid fluid, boolean bumpedHorizontally) {
        this(position, velocity, onGround, pose, fluid, bumpedHorizontally, 0);
    }

    public boolean inFluid() {
        return fluid != Fluid.NONE;
    }

    /** The exact player collision box, with {@code pos} interpreted as feet/bottom-center. */
    public AABB box(Vec3 pos) {
        return box(pose, pos);
    }

    public static AABB box(Pose pose, Vec3 pos) {
        // PlayerEntity dimensions are deliberately hardcoded: Stage A exposes only these poses.
        double height = pose == Pose.STAND ? 1.8 : 0.6;
        return new AABB(pos.x() - 0.3, pos.y, pos.z() - 0.3,
                pos.x() + 0.3, pos.y + height, pos.z() + 0.3);
    }

    public enum Pose {
        STAND,
        CRAWL,
        SWIM
    }

    /** Fluid classification retained in the state so subsequent ticks are self-contained. */
    public enum Fluid {
        NONE,
        WATER,
        LAVA
    }
}
