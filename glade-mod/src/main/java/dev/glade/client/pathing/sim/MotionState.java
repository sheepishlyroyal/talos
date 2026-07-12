package dev.glade.client.pathing.sim;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Immutable input/output snapshot for one invocation of {@link PlayerMotion#step}. */
public record MotionState(
        Vec3d position,
        Vec3d velocity,
        boolean onGround,
        Pose pose,
        Fluid fluid,
        boolean bumpedHorizontally) {

    public MotionState {
        if (position == null || velocity == null || pose == null || fluid == null) {
            throw new IllegalArgumentException("MotionState fields must not be null");
        }
    }

    /** Convenience constructor for a state before any simulated collision outcome exists. */
    public MotionState(Vec3d position, Vec3d velocity, boolean onGround, Pose pose) {
        this(position, velocity, onGround, pose, Fluid.NONE, false);
    }

    public boolean inFluid() {
        return fluid != Fluid.NONE;
    }

    /** The exact player collision box, with {@code pos} interpreted as feet/bottom-center. */
    public Box box(Vec3d pos) {
        return box(pose, pos);
    }

    public static Box box(Pose pose, Vec3d pos) {
        // PlayerEntity dimensions are deliberately hardcoded: Stage A exposes only these poses.
        double height = pose == Pose.STAND ? 1.8 : 0.6;
        return new Box(pos.x - 0.3, pos.y, pos.z - 0.3,
                pos.x + 0.3, pos.y + height, pos.z + 0.3);
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
