package dev.glade.client.pathing.sim;

import dev.glade.client.GladeClient;
import dev.glade.client.pathing.PathResult;
import dev.glade.client.render.RenderQueue;
import dev.glade.client.task.GladeTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Closed-loop route follower. The route contains geometric intent, never a recorded input
 * schedule: every client tick starts from the real player state and runs fresh short rollouts.
 */
public final class SimFollowTask extends GladeTask {
    private static final double REACHED_SQUARED = 0.36;
    private static final int ROLLOUT_TICKS = 6;
    private static final int LANDING_ROLLOUT_TICKS = 40;
    private static final double OVERSHOOT_SLOP = 0.03;
    private static final double GROUND_DRAG = 0.6 * 0.91;
    private static final double FINAL_BRAKE_BUFFER = 0.35;
    private static final double BRAKE_RELEASE_SPEED = 0.075;
    // Vanilla pitch is positive down and negative up.
    private static final float SWIM_DIVE_PITCH = 35.0F;
    private static final float SWIM_SURFACE_PITCH = -35.0F;
    private static final int PREDICTION_COLOR = 0xFF9F1C;
    private static final int PREDICTION_TTL = 3;
    private static final int STUCK_TICKS = 100;

    private final MinecraftClient client;
    private final PlannedRoute route;
    private final Predicate<BlockPos> goal;
    private final CompletableFuture<PathResult> future;
    private int index;
    private int ticks;
    private int lastProgressTick;
    private double bestRemaining = Double.POSITIVE_INFINITY;
    private BlockPos breaking;
    private BlockPos lastPlacement;
    private int lastPlacementTick = Integer.MIN_VALUE;
    private String lastStatus = "";
    private Primitive committedPrimitive;
    private int committedStrafe;
    private int strafeCommitUntil;
    private boolean surfacingForAir;

    public SimFollowTask(MinecraftClient client, PlannedRoute route, Predicate<BlockPos> goal,
            CompletableFuture<PathResult> future) {
        this.client = client;
        this.route = route;
        this.goal = goal;
        this.future = future;
        this.index = route.waypoints().size() > 1 ? 1 : route.waypoints().size();
    }

    @Override public void initialize() { }
    @Override public boolean condition() { return !future.isDone(); }
    @Override public void increment() { ticks++; }

    @Override
    public void body() {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            finish(false, "World unloaded while navigating");
            return;
        }
        if (!GladeClient.tickBudget().hasBudgetRemaining()) {
            scheduleDelay();
            return;
        }

        advance(player);
        if (goal.test(player.getBlockPos())) {
            finish(true, "Arrived");
            return;
        }
        if (index >= route.waypoints().size()) {
            finish(false, "Nodes ended before the goal was reached");
            return;
        }

        PlannedRoute.Waypoint waypoint = route.waypoints().get(index);
        updateProgress(new Vec3d(player.getX(), player.getY(), player.getZ()), waypoint.position());
        if (ticks - lastProgressTick > STUCK_TICKS) {
            finish(false, "Pathing is stuck near route waypoint " + index);
            return;
        }

        if (waypoint.via() == Primitive.MINE && mine(player, waypoint.position())) {
            scheduleDelay();
            return;
        }
        if (waypoint.via() == Primitive.PLACE && place(player, waypoint.position())) {
            scheduleDelay();
            return;
        }

        MotionState live = liveState(player);
        MovementProfile profile = MovementProfile.capture(player); // Effects/attributes are live.
        float yaw = yawTo(new Vec3d(player.getX(), player.getY(), player.getZ()), waypoint.position());
        Input selected = chooseInput(live, profile, waypoint, yaw);
        renderPrediction(live, selected, profile);
        if (live.fluid() == MotionState.Fluid.WATER) {
            updateAirMode(player);
            player.setPitch(surfacingForAir ? SWIM_SURFACE_PITCH : SWIM_DIVE_PITCH);
        }
        apply(player, selected);
        status(live.inFluid()
                ? live.fluid() == MotionState.Fluid.LAVA ? "swimming (lava)" : "swimming"
                : isBrake(selected) ? "braking (final approach)"
                : selected.sprint() && selected.jump() ? "sprint-jumping"
                : selected.jump() ? "jumping"
                : selected.sprint() ? "sprinting" : label(waypoint.via()));
        scheduleDelay();
    }

    /** Entity coordinates are feet/bottom-center, exactly the origin MotionState.box uses. */
    public static MotionState liveState(ClientPlayerEntity player) {
        MotionState.Pose pose = player.isCrawling() ? MotionState.Pose.CRAWL
                : (player.isSubmergedInWater() || player.isSwimming()
                        || player.isInSwimmingPose())
                        ? MotionState.Pose.SWIM : MotionState.Pose.STAND;
        // Classify fluid from the same exact AABB used by simulation, rather than trusting
        // entity flags whose update may trail the current client-world collision state.
        MotionState.Fluid fluid = MotionState.Fluid.NONE;
        var box = MotionState.box(pose, new Vec3d(player.getX(), player.getY(), player.getZ()));
        for (BlockPos cell : BlockPos.iterate(
                BlockPos.ofFloored(box.minX, box.minY, box.minZ),
                BlockPos.ofFloored(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7))) {
            var state = player.getEntityWorld().getFluidState(cell);
            if (state.isIn(FluidTags.LAVA)) { fluid = MotionState.Fluid.LAVA; break; }
            if (state.isIn(FluidTags.WATER)) fluid = MotionState.Fluid.WATER;
        }
        return new MotionState(new Vec3d(player.getX(), player.getY(), player.getZ()), player.getVelocity(), player.isOnGround(), pose,
                fluid, false);
    }

    /** Enumerate compact controls, simulate each from reality, and keep the safest progress. */
    private Input chooseInput(MotionState live, MovementProfile profile,
            PlannedRoute.Waypoint waypoint, float yaw) {
        // Fluid movement deliberately has one committed control mode. Mixing land walk,
        // jump-release, brake, and strafe candidates made the selected keys thrash each tick.
        // Sprint-swim is driven by view pitch. Holding jump continuously prevents a clean dive;
        // emit only a short upward impulse at genuinely low air, while pitch remains upward
        // until breathing has restored a safe reserve.
        if (live.fluid() == MotionState.Fluid.WATER) {
            ClientPlayerEntity player = client.player;
            boolean lowAirImpulse = player != null
                    && player.getAir() <= Math.max(20, player.getMaxAir() / 5);
            return new Input(1, 0, lowAirImpulse, true, false, yaw);
        }
        if (live.fluid() == MotionState.Fluid.LAVA) {
            return new Input(1, 0, false, true, false, yaw);
        }

        Primitive primitive = waypoint.via();
        boolean wantsSprint = primitive == Primitive.SPRINT || primitive == Primitive.SPRINT_JUMP;
        boolean wantsJump = primitive == Primitive.SPRINT_JUMP || primitive == Primitive.STEP_UP
                || primitive == Primitive.SWIM;
        boolean wantsSneak = primitive == Primitive.CRAWL || primitive == Primitive.PLACE;
        boolean continuousRun = primitive == Primitive.SPRINT_JUMP;
        Vec3d from = index > 0 ? route.waypoints().get(index - 1).position() : live.position();
        Vec3d finalTarget = route.waypoints().getLast().position();
        Vec3d finalFrom = route.waypoints().size() > 1
                ? route.waypoints().get(route.waypoints().size() - 2).position()
                : live.position();
        boolean approachingFinal = route.reachedGoal()
                && index + 1 == route.waypoints().size();

        // Evaluate the control the primitive would hold, on every tick and for every primitive.
        // The geometric tail is v + v*d + ... = v/(1-d), with vanilla ground drag d=.6*.91.
        Input held = new Input(1, 0, wantsJump, wantsSprint, wantsSneak, yaw);
        MotionState heldLanding = approachingFinal ? predictLanding(live, held, profile) : null;
        double speed = Math.hypot(live.velocity().x, live.velocity().z);
        double stoppingDistance = speed / (1.0 - GROUND_DRAG);
        double targetDistance = horizontalDistance(live.position(), waypoint.position());
        double landingTravel = heldLanding == null ? 0.0
                : horizontalDistance(live.position(), heldLanding.position());
        boolean landingOvershoots = approachingFinal && heldLanding != null
                && overshoot(heldLanding.position(), finalFrom, finalTarget) > OVERSHOOT_SLOP;
        boolean insideStoppingEnvelope = approachingFinal && targetDistance
                <= Math.max(stoppingDistance, landingTravel) + FINAL_BRAKE_BUFFER;
        boolean brakeNeeded = approachingFinal && (landingOvershoots || insideStoppingEnvelope);
        boolean hardBrake = brakeNeeded && (!live.onGround() || speed > BRAKE_RELEASE_SPEED);
        Input brake = new Input(0, 0, false, false, true, yaw);
        if (primitive != committedPrimitive) {
            committedPrimitive = primitive;
            committedStrafe = 0;
            strafeCommitUntil = ticks;
        }
        List<Input> candidates = new ArrayList<>();
        // Intermediate nodes are pass-through guides: they never receive a brake/stop option.
        // The final goal alone may trade progress for deliberate deceleration.
        if (approachingFinal) candidates.add(brake);
        boolean precisionLaunch = brakeNeeded && live.onGround()
                && speed <= BRAKE_RELEASE_SPEED;
        candidates.add(new Input(1, 0, wantsJump,
                wantsSprint && (!brakeNeeded || precisionLaunch), wantsSneak, yaw));
        // Never offer a one-tick jump release during a committed sprint-jump or ledge climb.
        if ((!continuousRun || brakeNeeded) && primitive != Primitive.STEP_UP) {
            candidates.add(new Input(1, 0, false, false, wantsSneak, yaw));
        }
        if (committedStrafe != 0 && ticks < strafeCommitUntil) {
            candidates.add(new Input(1, committedStrafe * .35F,
                    wantsJump, wantsSprint && (!brakeNeeded || precisionLaunch), wantsSneak, yaw));
        } else {
            candidates.add(new Input(1, .35F, wantsJump,
                    wantsSprint && (!brakeNeeded || precisionLaunch), wantsSneak, yaw));
            candidates.add(new Input(1, -.35F, wantsJump,
                    wantsSprint && (!brakeNeeded || precisionLaunch), wantsSneak, yaw));
        }
        if (approachingFinal) candidates.add(new Input(0, 0, false, false, false, yaw));

        // Once an unsafe landing is imminent, scoring cannot trade the mark for short-term
        // progress. First bleed momentum; at release speed rollout scoring chooses the least
        // forceful safe launch, while retaining sprint-jump only when a gap requires it.
        if (hardBrake) return brake;

        Input best = candidates.getFirst();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Input candidate : candidates) {
            if (!GladeClient.tickBudget().hasBudgetRemaining() && bestScore > Double.NEGATIVE_INFINITY) {
                break;
            }
            MotionState state = live;
            double score = 0.0;
            double oldDistance = horizontalDistance(state.position(), waypoint.position());
            for (int tick = 0; tick < ROLLOUT_TICKS; tick++) {
                state = PlayerMotion.step(client.world, state, candidate, profile);
                double distance = horizontalDistance(state.position(), waypoint.position());
                score += (oldDistance - distance) * 12.0; // primary route progress
                score -= distanceToSegment(state.position(), from, waypoint.position()) * 1.8;
                if (state.bumpedHorizontally()) score -= 7.0;
                if (state.fluid() == MotionState.Fluid.LAVA) score -= 100.0;
                if (isHazard(BlockPos.ofFloored(state.position()))) score -= 60.0;
                if (!PlayerMotion.hitboxFits(client.world, state.pose(), state.position())) {
                    score -= 1_000.0;
                    break;
                }
                if (state.position().y < Math.min(from.y, waypoint.position().y) - 2.5) score -= 80.0;
                if (approachingFinal) {
                    score -= overshoot(state.position(), finalFrom, finalTarget) * 140.0;
                }
                oldDistance = distance;
            }
            MotionState landing = approachingFinal && (!live.onGround() || candidate.jump())
                    ? predictLanding(live, candidate, profile) : null;
            if (landing != null && approachingFinal) {
                score -= overshoot(landing.position(), finalFrom, finalTarget) * 220.0;
            }
            if (brakeNeeded) {
                if (isBrake(candidate)) score += 18.0;
                if (candidate.sprint() && !precisionLaunch) score -= 40.0;
                if (candidate.jump() && !precisionLaunch) score -= 40.0;
            }
            // Precision endpoints prefer braking over flying through the waypoint.
            boolean precise = approachingFinal
                    || primitive == Primitive.PLACE || primitive == Primitive.MINE;
            if (precise) score -= horizontalDistance(state.position(), waypoint.position()) * 3.0;
            if (ticks < strafeCommitUntil
                    && Float.compare(candidate.strafe(), 0.0F) == committedStrafe) {
                score += 0.8; // Four-tick hysteresis, still overridable by a clearly safer line.
            }
            // Stable ordering is the deterministic tie-break: forward is first. Strafing must
            // beat it materially, preventing close scores from alternating left/right.
            double margin = candidate.strafe() == 0.0F ? 0.0 : 0.75;
            if (score > bestScore + margin) { bestScore = score; best = candidate; }
        }
        int chosenStrafe = Float.compare(best.strafe(), 0.0F);
        if (chosenStrafe != 0) {
            committedStrafe = chosenStrafe;
            strafeCommitUntil = ticks + 4;
        } else if (ticks >= strafeCommitUntil) {
            committedStrafe = 0;
        }
        return best;
    }

    /** Refresh a short-lived, exact-hitbox trail for the input selected from this live state. */
    private void renderPrediction(MotionState live, Input selected, MovementProfile profile) {
        MotionState predicted = live;
        for (int i = 0; i < ROLLOUT_TICKS; i++) {
            predicted = PlayerMotion.step(client.world, predicted, selected, profile);
            RenderQueue.add("glade-sim-tick:" + i,
                    predicted.box(predicted.position()), PREDICTION_COLOR, PREDICTION_TTL);
        }
    }

    private static boolean isBrake(Input input) {
        return !input.jump() && !input.sprint() && input.sneak();
    }

    private boolean isHazard(BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private boolean mine(ClientPlayerEntity player, Vec3d target) {
        BlockPos origin = player.getBlockPos();
        BlockPos destination = BlockPos.ofFloored(target);
        int dx = Integer.compare(destination.getX(), origin.getX());
        int dz = Integer.compare(destination.getZ(), origin.getZ());
        BlockPos block = null;
        // MINE waypoints describe the post-edit cell, so inspect the intervening tunnel.
        for (int step = 1; step <= 3 && block == null; step++) {
            BlockPos feet = origin.add(dx * step, 0, dz * step);
            if (blocking(feet)) block = feet;
            else if (blocking(feet.up())) block = feet.up();
            if (feet.getX() == destination.getX() && feet.getZ() == destination.getZ()) break;
        }
        if (block == null) { breaking = null; return false; }
        status("mining");
        releaseInputs();
        BlockState state = client.world.getBlockState(block);
        int best = player.getInventory().getSelectedSlot();
        float speed = player.getInventory().getStack(best).getMiningSpeedMultiplier(state);
        for (int slot = 0; slot < 9; slot++) {
            float candidate = player.getInventory().getStack(slot).getMiningSpeedMultiplier(state);
            if (candidate > speed) { best = slot; speed = candidate; }
        }
        player.getInventory().setSelectedSlot(best);
        if (!block.equals(breaking)) {
            client.interactionManager.attackBlock(block, Direction.UP);
            breaking = block.toImmutable();
        } else {
            client.interactionManager.updateBlockBreakingProgress(block, Direction.UP);
        }
        player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    /** Place the missing support from an existing solid neighbor while sneak-guarding the lip. */
    private boolean place(ClientPlayerEntity player, Vec3d target) {
        BlockPos destination = BlockPos.ofFloored(target);
        BlockPos feet = player.getBlockPos();
        int dx = Integer.compare(destination.getX(), feet.getX());
        int dz = Integer.compare(destination.getZ(), feet.getZ());
        // PLACE waypoints are normally the far-side landing; the missing support is the
        // first cell across the lip, not necessarily destination.down().
        BlockPos support = feet.add(dx, 0, dz).down();
        if (!client.world.getBlockState(support).getCollisionShape(client.world, support).isEmpty()) {
            support = destination.down();
        }
        if (!client.world.getBlockState(support).getCollisionShape(client.world, support).isEmpty()) {
            return false;
        }
        status("bridging");
        releaseInputs();
        client.options.sneakKey.setPressed(true);
        int slot = findBlockSlot(player);
        if (slot < 0) { finish(false, "Ran out of bridge blocks"); return true; }
        for (Direction side : Direction.values()) {
            BlockPos anchor = support.offset(side.getOpposite());
            if (client.world.getBlockState(anchor).getCollisionShape(client.world, anchor).isEmpty()) continue;
            if (support.equals(lastPlacement) && ticks - lastPlacementTick < 2) return true;
            Vec3d hit = Vec3d.ofCenter(anchor).add(side.getOffsetX() * .5,
                    side.getOffsetY() * .5, side.getOffsetZ() * .5);
            player.getInventory().setSelectedSlot(slot);
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                    new BlockHitResult(hit, side, anchor, false));
            player.swingHand(Hand.MAIN_HAND);
            lastPlacement = support.toImmutable();
            lastPlacementTick = ticks;
            return true;
        }
        finish(false, "No solid face is available for bridging");
        return true;
    }

    private void advance(ClientPlayerEntity player) {
        while (index < route.waypoints().size()) {
            Vec3d target = route.waypoints().get(index).position();
            double dx = player.getX() - target.x, dz = player.getZ() - target.z;
            boolean reached = dx * dx + dz * dz < REACHED_SQUARED
                    && Math.abs(player.getY() - target.y) <= .75;
            Vec3d previous = route.waypoints().get(index - 1).position();
            double edgeX = target.x - previous.x, edgeZ = target.z - previous.z;
            boolean passed = (player.getX() - target.x) * edgeX
                    + (player.getZ() - target.z) * edgeZ >= 0.0;
            if (!reached && !passed) break;
            index++;
            bestRemaining = Double.POSITIVE_INFINITY;
            lastProgressTick = ticks;
        }
    }

    private void updateProgress(Vec3d position, Vec3d target) {
        double remaining = position.squaredDistanceTo(target);
        if (remaining + .04 < bestRemaining) {
            bestRemaining = remaining;
            lastProgressTick = ticks;
        }
    }

    private void apply(ClientPlayerEntity player, Input input) {
        // Set the complete desired state directly. Releasing first created a real per-tick
        // false/true pulse that broke continuous sprint-jump holds and bunny-hop momentum.
        client.options.forwardKey.setPressed(input.forward() > .1F);
        client.options.backKey.setPressed(input.forward() < -.1F);
        client.options.leftKey.setPressed(input.strafe() > .1F);
        client.options.rightKey.setPressed(input.strafe() < -.1F);
        client.options.jumpKey.setPressed(input.jump());
        client.options.sprintKey.setPressed(input.sprint());
        client.options.sneakKey.setPressed(input.sneak());
        player.setYaw(input.yaw());
        player.setHeadYaw(input.yaw());
        player.setBodyYaw(input.yaw());
    }

    private void updateAirMode(ClientPlayerEntity player) {
        int maxAir = player.getMaxAir();
        if (player.getAir() <= Math.max(20, maxAir / 5)) surfacingForAir = true;
        else if (player.getAir() >= maxAir * 3 / 4) surfacingForAir = false;
    }

    private boolean blocking(BlockPos pos) {
        return !client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty();
    }

    private static int findBlockSlot(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).getItem() instanceof BlockItem) return slot;
        }
        return -1;
    }

    private static float yawTo(Vec3d from, Vec3d to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static double distanceToSegment(Vec3d point, Vec3d a, Vec3d b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double length = dx * dx + dz * dz;
        if (length < 1.0E-8) return horizontalDistance(point, b);
        double t = Math.max(0.0, Math.min(1.0,
                ((point.x - a.x) * dx + (point.z - a.z) * dz) / length));
        return Math.hypot(point.x - (a.x + dx * t), point.z - (a.z + dz * t));
    }

    /** Horizontal distance beyond {@code target}, measured along this route edge. */
    private static double overshoot(Vec3d point, Vec3d from, Vec3d target) {
        double dx = target.x - from.x, dz = target.z - from.z;
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-8) return 0.0;
        return Math.max(0.0,
                ((point.x - target.x) * dx + (point.z - target.z) * dz) / length);
    }

    /** Continue a fixed input until the arc lands, with a hard cooperative tick bound. */
    private MotionState predictLanding(MotionState start, Input input,
            MovementProfile profile) {
        MotionState state = start;
        boolean airborne = !start.onGround();
        for (int tick = 0; tick < LANDING_ROLLOUT_TICKS; tick++) {
            state = PlayerMotion.step(client.world, state, input, profile);
            if (!PlayerMotion.hitboxFits(client.world, state.pose(), state.position())) return null;
            airborne |= !state.onGround();
            if (airborne && state.onGround()) return state;
            // A non-jumping grounded control is already at its next grounded sample. Its
            // longer deceleration tail is represented separately by stoppingDistance.
            if (tick == 0 && start.onGround() && !input.jump() && state.onGround()) return state;
        }
        return null;
    }

    private static String label(Primitive primitive) {
        if (primitive == null) return "walking";
        return switch (primitive) {
            case WALK, DROP, STEP_UP -> "walking";
            case SPRINT -> "sprinting";
            case SPRINT_JUMP -> "sprint-jump";
            case SWIM -> "swimming";
            case CRAWL -> "crawling";
            case MINE -> "mining";
            case PLACE -> "bridging";
        };
    }

    private void status(String mode) {
        if (mode.equals(lastStatus)) return;
        lastStatus = mode;
        if (client.player != null) client.player.sendMessage(
                Text.literal("§bGlade §7» §f" + mode), true);
    }

    public void cancel() { finish(false, "Pathing cancelled"); _break(); }

    private void finish(boolean success, String detail) {
        releaseInputs();
        if (client.player != null) client.player.sendMessage(Text.literal(
                (success ? "§aGlade §7» §f" : "§cGlade §7» §f") + detail), true);
        future.complete(new PathResult(success, detail));
    }

    private void releaseInputs() {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }

    @Override public void onCompleted() {
        releaseInputs();
        if (!future.isDone()) future.complete(new PathResult(false, "Navigation was interrupted"));
    }

    @Override public Set<Object> getMutexKeys() { return Set.of("glade-player-movement"); }
}
