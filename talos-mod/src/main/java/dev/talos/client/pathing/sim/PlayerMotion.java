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
        // Vanilla applies the sprint boost only on land (water sprint changes drag instead),
        // and applies the sneak factor to the INPUT before normalization, not to the speed.
        if (input.sprint() && fluid == MotionState.Fluid.NONE) acceleration *= 1.3;
        double inputFactor = input.sneak() || state.pose() == MotionState.Pose.CRAWL
                ? profile.sneakSlowFactor() : 1.0;

        Vec3d velocity = state.velocity().add(inputVelocity(input, acceleration, inputFactor));

        // Vanilla LivingEntity jump velocity is 0.42 for an unmodified player, scaled by the
        // block's jump multiplier (honey/soul-sand-family jumps are stunted) with Jump Boost
        // added AFTER the multiplier. Entity's sprint jump impulse is (-sin*0.2, 0, cos*0.2).
        // Holding jump does NOT re-jump every grounded tick: vanilla enforces a 10-tick
        // jumpingCooldown, without which rollouts over-predicted hop cadence when cornered.
        boolean jumped = input.jump() && state.onGround() && state.jumpCooldown() == 0
                && fluid == MotionState.Fluid.NONE;
        if (jumped) {
            velocity = new Vec3d(velocity.x,
                    profile.jumpVelocity() * jumpMultiplier(world, state.position())
                            + profile.jumpBoost(), velocity.z);
            if (input.sprint()) {
                double radians = input.yaw() * (Math.PI / 180.0);
                velocity = velocity.add(-MathHelper.sin(radians) * SPRINT_JUMP_IMPULSE, 0.0,
                        MathHelper.cos(radians) * SPRINT_JUMP_IMPULSE);
            }
        } else if (input.jump() && fluid != MotionState.Fluid.NONE) {
            // Approximation of LivingEntity swimming ascent: the vanilla helper adds 0.04.
            velocity = velocity.add(0.0, 0.04, 0.0);
        }
        int jumpCooldown = jumped ? 10 : Math.max(0, state.jumpCooldown() - 1);

        // Vanilla adjustMovementForSneaking: a sneaking grounded player cannot walk off a
        // ledge — horizontal movement is clamped at the edge. The follower's sneak-brake
        // inputs rely on this exact stickiness near endpoints and drops.
        Vec3d requested = input.sneak() && state.onGround()
                ? clipAtLedge(world, box, velocity, profile.stepHeight()) : velocity;
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
        // Velocity retention mirrors vanilla move(): collisions zero an axis, but the sneak
        // ledge clamp does not — the retained velocity stays the pre-clamp value and is
        // simply re-clamped next tick.
        double vx = clampedX ? 0.0 : velocity.x;
        double vy = clampedY ? 0.0 : velocity.y;
        double vz = clampedZ ? 0.0 : velocity.z;

        // Mirrors Entity.move()'s tail: the standing block's velocity multiplier bleeds
        // speed every tick (soul sand / honey 0.4x) BEFORE the friction pass — without it
        // the sim predicted full-speed sprints across soul sand.
        if (fluid == MotionState.Fluid.NONE) {
            double multiplier = velocityMultiplier(world, newPosition);
            vx *= multiplier;
            vz *= multiplier;
        }

        // Mirrors vanilla travel's post-move drag/gravity ordering. Ground friction is the
        // block's Block.getSlipperiness() times 0.91; air uses 0.91. Water/lava are documented
        // Stage A approximations, with reduced gravity and their characteristic drag.
        if (fluid == MotionState.Fluid.WATER) {
            // Vanilla water: sprint-swimming switches horizontal drag to 0.9 (there is no
            // x1.3 accel boost in fluids); vertical drag is a flat 0.8 with the fluid
            // gravity pull of gravity/16 = 0.005 applied after it.
            double waterDrag = input.sprint()
                    ? Math.max(0.9, profile.waterHorizontalDrag())
                    : profile.waterHorizontalDrag();
            vx *= waterDrag;
            vz *= waterDrag;
            vy = vy * 0.8 - 0.005;
        } else if (fluid == MotionState.Fluid.LAVA) {
            vx *= profile.lavaDrag();
            vz *= profile.lavaDrag();
            vy = (vy - 0.02) * profile.lavaDrag();
        } else {
            // Vanilla travel() computes friction from the PREVIOUS tick's onGround (it is
            // read at the top of the tick, before move() updates it). Using the fresh value
            // applied air friction on the jump-launch tick — 0.91 retained instead of
            // 0.6*0.91 — which made every predicted jump arc reach ~1.67x too much launch
            // speed and approved gap-jumps the real player cannot make.
            double horizontalDrag = (state.onGround() ? slipperiness : 1.0) * 0.91;
            vx *= horizontalDrag;
            vz *= horizontalDrag;
            if (profile.levitationLevel() > 0) {
                double target = 0.05 * profile.levitationLevel();
                vy += (target - vy) * 0.2;
                vy *= 0.98; // vanilla's *0.98 drag applies to the levitation branch too
            } else {
                double gravity = profile.gravity();
                if (profile.slowFalling() && vy <= 0.0) gravity = Math.min(gravity, 0.01);
                vy = (vy - gravity) * 0.98;
            }
        }

        MotionState.Fluid resultingFluid = fluidAt(world, state.box(newPosition));
        return new MotionState(newPosition, new Vec3d(vx, vy, vz), onGround,
                state.pose(), resultingFluid, bumped, jumpCooldown);
    }

    /**
     * Vanilla Entity.adjustMovementForSneaking: while sneaking on the ground, horizontal
     * movement is shrunk in 0.05 steps until the moved box would still be supported
     * (no walking off ledges). {@code stepHeight} is the depth probed for support.
     */
    private static Vec3d clipAtLedge(World world, Box box, Vec3d movement, double stepHeight) {
        double probe = Math.max(stepHeight, 0.6);
        double x = movement.x;
        double z = movement.z;
        while (x != 0.0 && unsupported(world, box.offset(x, -probe, 0.0))) x = shrinkStep(x);
        while (z != 0.0 && unsupported(world, box.offset(0.0, -probe, z))) z = shrinkStep(z);
        while (x != 0.0 && z != 0.0 && unsupported(world, box.offset(x, -probe, z))) {
            x = shrinkStep(x);
            z = shrinkStep(z);
        }
        return new Vec3d(x, movement.y, z);
    }

    private static double shrinkStep(double value) {
        if (value < 0.05 && value > -0.05) return 0.0;
        return value > 0.0 ? value - 0.05 : value + 0.05;
    }

    /** True when the offset box overlaps no block collision — i.e. it hangs over a drop. */
    private static boolean unsupported(World world, Box box) {
        for (VoxelShape shape : world.getBlockCollisions(null, box)) {
            if (!shape.isEmpty()) return false;
        }
        return true;
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

    /** Sneak/crawl scales the raw input BEFORE normalization, exactly as vanilla does. */
    private static Vec3d inputVelocity(Input input, double speed, double inputFactor) {
        double x = input.strafe() * inputFactor;
        double z = input.forward() * inputFactor;
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

    /**
     * Vanilla Entity.getVelocityMultiplier(): the block AT the feet governs (soul sand,
     * honey, cobweb-family = 0.4-ish); only when it is neutral does the block below apply.
     */
    private static double velocityMultiplier(World world, Vec3d position) {
        BlockPos feet = BlockPos.ofFloored(position);
        float at = world.getBlockState(feet).getBlock().getVelocityMultiplier();
        if (at != 1.0F) return at;
        BlockPos below = BlockPos.ofFloored(position.x, position.y - 0.5000001, position.z);
        return world.getBlockState(below).getBlock().getVelocityMultiplier();
    }

    /** Vanilla LivingEntity jump scaling: honey and friends stunt the jump to half height. */
    private static double jumpMultiplier(World world, Vec3d position) {
        BlockPos feet = BlockPos.ofFloored(position);
        float at = world.getBlockState(feet).getBlock().getJumpVelocityMultiplier();
        if (at != 1.0F) return at;
        BlockPos below = BlockPos.ofFloored(position.x, position.y - 0.5000001, position.z);
        return world.getBlockState(below).getBlock().getJumpVelocityMultiplier();
    }

    /** Slipperiness of the block under these feet (0.6 normal ground, 0.98 ice). */
    public static double slipperinessBelow(World world, Vec3d position) {
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
