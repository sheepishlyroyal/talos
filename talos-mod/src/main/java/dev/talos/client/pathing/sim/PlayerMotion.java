package dev.talos.client.pathing.sim;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.CollisionView;

/**
 * Deterministic, entity-free player movement approximation for planner rollouts.
 * It reads only the supplied world view and immutable arguments and owns no mutable/static
 * state. Depending on {@link CollisionView} (not World) keeps it runnable in unit tests.
 */
public final class PlayerMotion {
    private static final double EPSILON = 1.0E-7;
    // LivingEntity.jump() in the 1.21.11 named jar adds exactly 0.2 along facing yaw.
    private static final double SPRINT_JUMP_IMPULSE = 0.2;
    private static final double WATER_FLOW_PUSH = 0.014;   // Entity.updateMovementInFluid
    private static final double LAVA_FLOW_PUSH = 0.0023333333333333335;
    private PlayerMotion() {}

    /** Baseline-compatible overload for callers that do not yet have a live snapshot. */
    public static MotionState step(CollisionView world, MotionState state, Input input) {
        return step(world, state, input, MovementProfile.vanilla());
    }

    /** Advances one tick and records this tick's resolved ground/bump collision outcome. */
    public static MotionState step(CollisionView world, MotionState state, Input input,
            MovementProfile profile) {
        if (world == null || state == null || input == null || profile == null) {
            throw new IllegalArgumentException("step arguments must not be null");
        }

        Box box = state.box(state.position());
        Environment env = scanEnvironment(world, box);
        MotionState.Fluid fluid = env.fluid();
        boolean climbing = fluid == MotionState.Fluid.NONE && climbable(world.getBlockState(
                BlockPos.ofFloored(state.position().x, state.position().y + 1.0E-4,
                        state.position().z)));
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

        // Flowing-fluid push (river currents): vanilla Entity.updateMovementInFluid adds the
        // averaged, normalized flow of every overlapped fluid cell to the velocity each tick.
        if (env.flow().lengthSquared() > 1.0E-8) {
            velocity = velocity.add(env.flow().normalize().multiply(
                    fluid == MotionState.Fluid.LAVA ? LAVA_FLOW_PUSH : WATER_FLOW_PUSH));
        }

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

        // Vanilla applyClimbingSpeed (ladders/vines/scaffolding): horizontal clamped to
        // +-0.15, descent clamped to -0.15, and a sneaking climber holds position.
        if (climbing) {
            double cy = Math.max(velocity.y, -0.15);
            if (input.sneak() && cy < 0.0) cy = 0.0;
            velocity = new Vec3d(MathHelper.clamp(velocity.x, -0.15, 0.15), cy,
                    MathHelper.clamp(velocity.z, -0.15, 0.15));
        }

        // Vanilla adjustMovementForSneaking: a sneaking grounded player cannot walk off a
        // ledge — horizontal movement is clamped at the edge. The follower's sneak-brake
        // inputs rely on this exact stickiness near endpoints and drops.
        Vec3d requested = input.sneak() && state.onGround()
                ? clipAtLedge(world, box, velocity, profile.stepHeight()) : velocity;
        // Cobweb / sweet berry / powder snow: vanilla scales the MOVE by the block's
        // movement multiplier and zeroes the velocity after it — you wade, never coast.
        boolean smothered = env.slowFactor() != null;
        if (smothered) {
            requested = new Vec3d(requested.x * env.slowFactor().x,
                    requested.y * env.slowFactor().y, requested.z * env.slowFactor().z);
        }
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
        // simply re-clamped next tick. A movement-multiplier block zeroes ALL of it.
        double vx = clampedX || smothered ? 0.0 : velocity.x;
        double vy = clampedY || smothered ? 0.0 : velocity.y;
        double vz = clampedZ || smothered ? 0.0 : velocity.z;
        // Slime bounce: landing (not sneaking) inverts the fall velocity, vanilla
        // SlimeBlock.bounce. onGround stays true for the landing tick, as in vanilla.
        if (onGround && !input.sneak() && !smothered && velocity.y < 0.0
                && world.getBlockState(BlockPos.ofFloored(newPosition.x,
                        newPosition.y - 0.5000001, newPosition.z)).isOf(Blocks.SLIME_BLOCK)) {
            vy = -velocity.y;
        }
        // Vanilla: pressing into a climbable (or jumping on one) climbs at 0.2 pre-drag.
        if (climbing && (bumped || input.jump())) {
            vy = 0.2;
        }

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

        // Bubble columns adjust the velocity every tick they overlap the hitbox (vanilla
        // Entity.onBubbleColumnCollision, submerged variant): up +0.06 capped 0.7,
        // whirlpool -0.03 capped -0.3.
        if (env.bubble() > 0) vy = Math.min(0.7, vy + 0.06);
        else if (env.bubble() < 0) vy = Math.max(-0.3, vy - 0.03);

        MotionState.Fluid resultingFluid = scanEnvironment(world, state.box(newPosition)).fluid();
        return new MotionState(newPosition, new Vec3d(vx, vy, vz), onGround,
                state.pose(), resultingFluid, bumped, jumpCooldown);
    }

    /** Everything the step needs to know about the blocks the hitbox currently overlaps. */
    private record Environment(MotionState.Fluid fluid, Vec3d slowFactor, Vec3d flow,
            int bubble) {}

    private static Environment scanEnvironment(CollisionView world, Box box) {
        int minX = floor(box.minX);
        int minY = floor(box.minY);
        int minZ = floor(box.minZ);
        int maxX = floor(box.maxX - EPSILON);
        int maxY = floor(box.maxY - EPSILON);
        int maxZ = floor(box.maxZ - EPSILON);
        MotionState.Fluid fluid = MotionState.Fluid.NONE;
        Vec3d slow = null;
        Vec3d flow = Vec3d.ZERO;
        int flowCells = 0;
        int bubble = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState block = world.getBlockState(pos);
                    FluidState fluidState = block.getFluidState();
                    if (isLava(fluidState)) {
                        fluid = MotionState.Fluid.LAVA;
                    } else if (isWater(fluidState) && fluid == MotionState.Fluid.NONE) {
                        fluid = MotionState.Fluid.WATER;
                    }
                    if (!fluidState.isEmpty()) {
                        Vec3d cellFlow = fluidState.getVelocity(world, pos);
                        if (cellFlow.lengthSquared() > 1.0E-8) {
                            flow = flow.add(cellFlow);
                            flowCells++;
                        }
                    }
                    Vec3d factor = slowFactor(block);
                    if (factor != null) {
                        slow = slow == null ? factor
                                : new Vec3d(Math.min(slow.x, factor.x),
                                        Math.min(slow.y, factor.y), Math.min(slow.z, factor.z));
                    }
                    if (block.isOf(Blocks.BUBBLE_COLUMN)) {
                        bubble = block.get(net.minecraft.block.BubbleColumnBlock.DRAG) ? -1 : 1;
                    }
                }
            }
        }
        if (flowCells > 1) flow = flow.multiply(1.0 / flowCells);
        return new Environment(fluid, slow, flow, bubble);
    }

    /** Vanilla Entity.slowMovement callers: cobweb, sweet berry bush, powder snow. */
    private static Vec3d slowFactor(BlockState state) {
        if (state.isOf(Blocks.COBWEB)) return new Vec3d(0.25, 0.05, 0.25);
        if (state.isOf(Blocks.SWEET_BERRY_BUSH)) return new Vec3d(0.8, 0.75, 0.8);
        if (state.isOf(Blocks.POWDER_SNOW)) return new Vec3d(0.9, 1.5, 0.9);
        return null;
    }

    /** Tag check with explicit vanilla fallbacks so tag-less unit tests behave correctly. */
    static boolean climbable(BlockState state) {
        return state.isIn(BlockTags.CLIMBABLE) || state.isOf(Blocks.LADDER)
                || state.isOf(Blocks.VINE) || state.isOf(Blocks.SCAFFOLDING);
    }

    static boolean isWater(FluidState state) {
        return state.isIn(FluidTags.WATER) || state.getFluid() == Fluids.WATER
                || state.getFluid() == Fluids.FLOWING_WATER;
    }

    static boolean isLava(FluidState state) {
        return state.isIn(FluidTags.LAVA) || state.getFluid() == Fluids.LAVA
                || state.getFluid() == Fluids.FLOWING_LAVA;
    }

    /**
     * Vanilla Entity.adjustMovementForSneaking: while sneaking on the ground, horizontal
     * movement is shrunk in 0.05 steps until the moved box would still be supported
     * (no walking off ledges). {@code stepHeight} is the depth probed for support.
     */
    private static Vec3d clipAtLedge(CollisionView world, Box box, Vec3d movement, double stepHeight) {
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
    private static boolean unsupported(CollisionView world, Box box) {
        for (VoxelShape shape : world.getBlockCollisions(null, box)) {
            if (!shape.isEmpty()) return false;
        }
        return true;
    }

    /** True iff the exact pose AABB has no intersection with a block collision shape. */
    public static boolean hitboxFits(CollisionView world, MotionState.Pose pose, Vec3d pos) {
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

    private static Vec3d collide(CollisionView world, Box box, Vec3d movement) {
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
    private static double velocityMultiplier(CollisionView world, Vec3d position) {
        BlockPos feet = BlockPos.ofFloored(position);
        float at = world.getBlockState(feet).getBlock().getVelocityMultiplier();
        if (at != 1.0F) return at;
        BlockPos below = BlockPos.ofFloored(position.x, position.y - 0.5000001, position.z);
        return world.getBlockState(below).getBlock().getVelocityMultiplier();
    }

    /** Vanilla LivingEntity jump scaling: honey and friends stunt the jump to half height. */
    private static double jumpMultiplier(CollisionView world, Vec3d position) {
        BlockPos feet = BlockPos.ofFloored(position);
        float at = world.getBlockState(feet).getBlock().getJumpVelocityMultiplier();
        if (at != 1.0F) return at;
        BlockPos below = BlockPos.ofFloored(position.x, position.y - 0.5000001, position.z);
        return world.getBlockState(below).getBlock().getJumpVelocityMultiplier();
    }

    /** Slipperiness of the block under these feet (0.6 normal ground, 0.98 ice). */
    public static double slipperinessBelow(CollisionView world, Vec3d position) {
        BlockPos below = BlockPos.ofFloored(position.x, position.y - 0.001, position.z);
        BlockState state = world.getBlockState(below);
        // AbstractBlock.AbstractBlockState.getBlock() and Block.getSlipperiness() are public.
        return state.getBlock().getSlipperiness();
    }


    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static boolean changed(double requested, double resolved) {
        return Math.abs(requested - resolved) > EPSILON;
    }
}
