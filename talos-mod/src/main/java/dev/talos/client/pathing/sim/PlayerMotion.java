package dev.talos.client.pathing.sim;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

/**
 * Deterministic, entity-free player movement approximation for planner rollouts.
 * It reads only the supplied world and immutable arguments and owns no mutable/static state.
 */
public final class PlayerMotion {
    private static final double EPSILON = 1.0E-7;
    // LivingEntity.jump() in the 1.21.11 named jar adds exactly 0.2 along facing yaw.
    private static final double SPRINT_JUMP_IMPULSE = 0.2;
    private PlayerMotion() {}

    /** Baseline-compatible overload for callers that do not yet have a live snapshot. */
    public static MotionState step(World world, MotionState state, Input input) {
        return step(world, state, input, MovementProfile.vanilla());
    }

    /** Advances one tick and records this tick's resolved ground/bump collision outcome. */
    public static MotionState step(World world, MotionState state, Input input,
            MovementProfile profile) {
        if (world == null || state == null || input == null || profile == null) {
            throw new IllegalArgumentException("step arguments must not be null");
        }

        Box box = state.box(state.position());
        MotionState.Fluid fluid = fluidAt(world, box);
        double slipperiness = slipperinessBelow(world, state.position());

        // Mirrors Entity.movementInputToVelocity(Vec3d,float,float): normalize input if its
        // squared length exceeds one, scale it, then rotate by yaw using x*cos-z*sin,
        // z*cos+x*sin. MathHelper.sin(double)/cos(double) were verified in the named jar.
        double acceleration;
        if (fluid != MotionState.Fluid.NONE) {
            acceleration = 0.02;
        } else if (state.onGround()) {
            acceleration = profile.movementSpeed() * Math.pow(0.6 / slipperiness, 3.0);
        } else {
            acceleration = 0.02;
        }
        if (input.sprint()) acceleration *= 1.3;
        if (input.sneak() || state.pose() == MotionState.Pose.CRAWL) {
            acceleration *= profile.sneakSlowFactor();
        }

        Vec3d velocity = state.velocity().add(inputVelocity(input, acceleration));

        // Vanilla LivingEntity jump velocity is 0.42 for an unmodified player. Entity's
        // sprint jump impulse is (-sin(yaw)*0.2, 0, cos(yaw)*0.2).
        if (input.jump() && state.onGround() && fluid == MotionState.Fluid.NONE) {
            velocity = new Vec3d(velocity.x, profile.jumpVelocity(), velocity.z);
            if (input.sprint()) {
                double radians = input.yaw() * (Math.PI / 180.0);
                velocity = velocity.add(-MathHelper.sin(radians) * SPRINT_JUMP_IMPULSE, 0.0,
                        MathHelper.cos(radians) * SPRINT_JUMP_IMPULSE);
            }
        } else if (input.jump() && fluid != MotionState.Fluid.NONE) {
            // Approximation of LivingEntity swimming ascent: the vanilla helper adds 0.04.
            velocity = velocity.add(0.0, 0.04, 0.0);
        }

        Vec3d requested = velocity;
        Vec3d resolved = collide(world, box, requested);
        // Vanilla can retry a horizontally blocked grounded move above STEP_HEIGHT. This lets
        // slabs emerge as ordinary walking while a full block (above the usual 0.6) still
        // requires the explicit held-jump STEP_UP rollout.
        if (state.onGround() && profile.stepHeight() > 0.0
                && (changed(requested.x, resolved.x) || changed(requested.z, resolved.z))) {
            Vec3d stepped = collide(world, box,
                    new Vec3d(requested.x, profile.stepHeight(), requested.z));
            Vec3d settled = collide(world, box.offset(stepped),
                    new Vec3d(0.0, -profile.stepHeight(), 0.0));
            stepped = stepped.add(settled);
            if (stepped.horizontalLengthSquared() > resolved.horizontalLengthSquared() + EPSILON) {
                resolved = stepped;
            }
        }
        boolean clampedX = changed(requested.x, resolved.x);
        boolean clampedY = changed(requested.y, resolved.y);
        boolean clampedZ = changed(requested.z, resolved.z);
        // A ceiling clips only Y. It must shorten the arc without paying the pathfinder's
        // horizontal obstruction penalty; only X/Z resolution is a horizontal bump.
        boolean bumped = clampedX || clampedZ;
        boolean onGround = clampedY && requested.y < 0.0;

        Vec3d newPosition = state.position().add(resolved);
        double vx = clampedX ? 0.0 : resolved.x;
        double vy = clampedY ? 0.0 : resolved.y;
        double vz = clampedZ ? 0.0 : resolved.z;

        // Mirrors vanilla travel's post-move drag/gravity ordering. Ground friction is the
        // block's Block.getSlipperiness() times 0.91; air uses 0.91. Water/lava are documented
        // Stage A approximations, with reduced gravity and their characteristic drag.
        if (fluid == MotionState.Fluid.WATER) {
            vx *= profile.waterHorizontalDrag();
            vz *= profile.waterHorizontalDrag();
            vy = (vy - 0.02) * profile.waterHorizontalDrag();
        } else if (fluid == MotionState.Fluid.LAVA) {
            vx *= profile.lavaDrag();
            vz *= profile.lavaDrag();
            vy = (vy - 0.02) * profile.lavaDrag();
        } else {
            double horizontalDrag = (onGround ? slipperiness : 1.0) * 0.91;
            vx *= horizontalDrag;
            vz *= horizontalDrag;
            if (profile.levitationLevel() > 0) {
                double target = 0.05 * profile.levitationLevel();
                vy += (target - vy) * 0.2;
            } else {
                double gravity = profile.gravity();
                if (profile.slowFalling() && vy <= 0.0) gravity = Math.min(gravity, 0.01);
                vy = (vy - gravity) * 0.98;
            }
        }

        // Mirrors the tail of Entity.move(): soul sand, honey, and similar blocks scale the
        // retained horizontal velocity. Slow ground therefore costs the planner real ticks —
        // the negative weight emerges from simulation instead of a hand-tuned block list.
        double velocityMultiplier = velocityMultiplierAt(world, newPosition);
        if (velocityMultiplier != 1.0) {
            vx *= velocityMultiplier;
            vz *= velocityMultiplier;
        }

        MotionState.Fluid resultingFluid = fluidAt(world, state.box(newPosition));
        return new MotionState(newPosition, new Vec3d(vx, vy, vz), onGround,
                state.pose(), resultingFluid, bumped);
    }

    /** Entity.getVelocityMultiplier(): the occupied block, else the velocity-affecting block. */
    private static double velocityMultiplierAt(World world, Vec3d position) {
        BlockPos feet = BlockPos.ofFloored(position.x, position.y + 1.0E-4, position.z);
        float multiplier = world.getBlockState(feet).getBlock().getVelocityMultiplier();
        if ((double) multiplier != 1.0) return multiplier;
        BlockPos affecting = BlockPos.ofFloored(position.x, position.y - 0.5000001, position.z);
        return world.getBlockState(affecting).getBlock().getVelocityMultiplier();
    }

    /** True iff the exact pose AABB has no intersection with a block collision shape. */
    public static boolean hitboxFits(World world, MotionState.Pose pose, Vec3d pos) {
        Box box = MotionState.box(pose, pos);
        // CollisionView.getBlockCollisions(Entity,Box) explicitly accepts null and then uses
        // ShapeContext.absent(); its named-jar signature returns Iterable<VoxelShape>.
        for (VoxelShape shape : world.getBlockCollisions(null, box)) {
            if (!shape.isEmpty()) return false;
        }
        return true;
    }

    private static Vec3d inputVelocity(Input input, double speed) {
        double x = input.strafe();
        double z = input.forward();
        double lengthSquared = x * x + z * z;
        if (lengthSquared < EPSILON) return new Vec3d(0.0, 0.0, 0.0);
        if (lengthSquared > 1.0) {
            double inverseLength = 1.0 / Math.sqrt(lengthSquared);
            x *= inverseLength;
            z *= inverseLength;
        }
        x *= speed;
        z *= speed;
        double radians = input.yaw() * (Math.PI / 180.0);
        float sin = MathHelper.sin(radians);
        float cos = MathHelper.cos(radians);
        return new Vec3d(x * cos - z * sin, 0.0, z * cos + x * sin);
    }

    private static Vec3d collide(World world, Box box, Vec3d movement) {
        List<VoxelShape> collisions = new ArrayList<>();
        // Box.stretch(Vec3d) is the swept volume. Query once because the iterable is lazy,
        // then mirror Entity.adjustMovementForCollisions(Vec3d,Box,List).
        for (VoxelShape shape : world.getBlockCollisions(null, box.stretch(movement))) {
            collisions.add(shape);
        }
        Vec3d result = new Vec3d(0.0, 0.0, 0.0);
        // 1.21.11 vanilla does not use fixed Y/X/Z: Direction.getCollisionOrder(Vec3d)
        // selects an axis order from movement, then VoxelShapes.calculateMaxOffset clamps it.
        for (Direction.Axis axis : Direction.getCollisionOrder(movement)) {
            double amount = movement.getComponentAlongAxis(axis);
            if (amount != 0.0) {
                amount = VoxelShapes.calculateMaxOffset(axis, box.offset(result), collisions, amount);
                result = result.withAxis(axis, amount);
            }
        }
        return result;
    }

    private static double slipperinessBelow(World world, Vec3d position) {
        BlockPos below = BlockPos.ofFloored(position.x, position.y - 0.001, position.z);
        BlockState state = world.getBlockState(below);
        // AbstractBlock.AbstractBlockState.getBlock() and Block.getSlipperiness() are public.
        return state.getBlock().getSlipperiness();
    }

    private static MotionState.Fluid fluidAt(World world, Box box) {
        // FluidState has no box query. Inspect every cell touched by the exact AABB; subtracting
        // epsilon at maxima prevents a face merely touching the next cell from counting.
        int minX = floor(box.minX);
        int minY = floor(box.minY);
        int minZ = floor(box.minZ);
        int maxX = floor(box.maxX - EPSILON);
        int maxY = floor(box.maxY - EPSILON);
        int maxZ = floor(box.maxZ - EPSILON);
        MotionState.Fluid found = MotionState.Fluid.NONE;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    FluidState fluid = world.getFluidState(new BlockPos(x, y, z));
                    // FluidState.isIn(TagKey<Fluid>) and FluidTags.WATER/LAVA are named APIs.
                    if (fluid.isIn(FluidTags.LAVA)) return MotionState.Fluid.LAVA;
                    if (fluid.isIn(FluidTags.WATER)) found = MotionState.Fluid.WATER;
                }
            }
        }
        return found;
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static boolean changed(double requested, double resolved) {
        return Math.abs(requested - resolved) > EPSILON;
    }
}
