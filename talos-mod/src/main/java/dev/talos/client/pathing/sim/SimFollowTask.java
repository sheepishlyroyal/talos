package dev.talos.client.pathing.sim;

import dev.talos.client.TalosClient;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.RotationHumanizer;
import dev.talos.client.humanize.SeededRng;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.render.RenderQueue;
import dev.talos.client.task.TalosTask;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Closed-loop route follower. The route contains geometric intent, never a recorded input
 * schedule: every client tick starts from the real player state and runs fresh short rollouts.
 */
public final class SimFollowTask extends TalosTask {
    private static final double REACHED_SQUARED = 0.36;
    private static final int ROLLOUT_TICKS = 6;
    private static final int LANDING_ROLLOUT_TICKS = 40;
    private static final double OVERSHOOT_SLOP = 0.03;
    private static final double FINAL_BRAKE_BUFFER = 0.35;
    private static final double BRAKE_RELEASE_SPEED = 0.075;
    // The swim hitbox is 0.6 tall; holding the feet this far under the surface keeps the
    // top of the box in air (breathing) while the rest stays in water (sprint-swimming).
    private static final double SURFACE_FEET_DEPTH = 0.45;
    private static final int PREDICTION_COLOR = 0xFF9F1C;
    private static final int PREDICTION_TTL = 3;
    private static final int AIM_TARGET_COLOR = 0xFFE14D; // yellow: where we WANT to look
    private static final int AIM_GAZE_COLOR = 0xFF3333;   // red: where we look right now
    private static final int STUCK_TICKS = 100;

    private final MinecraftClient client;
    private PlannedRoute route;
    private final Predicate<BlockPos> goal;
    private final CompletableFuture<PathResult> future;
    private Runnable replanRequest;
    private boolean replanRequested;
    private int index;
    private int ticks;
    private int lastProgressTick;
    private double bestRemaining = Double.POSITIVE_INFINITY;
    private BlockPos breaking;
    private BlockPos pillarOrigin;
    private BlockPos lastPlacement;
    private int lastPlacementTick = Integer.MIN_VALUE;
    private String lastStatus = "";
    private Primitive committedPrimitive;
    private int committedStrafe;
    private int strafeCommitUntil;
    private boolean surfacingForAir;
    // Orbit breaker: waypoints we brushed past but never technically "reached" (slopes,
    // jump arcs) must not trap the follower in circles around them.
    private double closestApproach = Double.POSITIVE_INFINITY;
    private int recedeTicks;
    // Humanized steering state: the view follows the route with bounded, eased turns
    // plus profile-scaled noise instead of snapping.
    private final SeededRng steerRng = new SeededRng(System.nanoTime());
    private double yawVelocity;
    private double maxTurnSpeed;
    private double pitchWander;
    private int pitchWanderTicks;
    // The random look noise is PRE-SAMPLED: slot 0 is applied to the real view this tick,
    // slot i feeds simulated tick i of every rollout/prediction. Random to an observer,
    // fully known to the simulator — so predictions include the wobble before it happens.
    private final double[] plannedJitter = new double[ROLLOUT_TICKS * 2];
    private boolean jitterSeeded;

    public SimFollowTask(MinecraftClient client, PlannedRoute route, Predicate<BlockPos> goal,
            CompletableFuture<PathResult> future) {
        this.client = client;
        this.route = route;
        this.goal = goal;
        this.future = future;
        this.index = route.waypoints().size() > 1 ? 1 : route.waypoints().size();
    }

    /** Engine callback fired once per route when the remaining partial route runs short. */
    public void setReplanRequest(Runnable request) { this.replanRequest = request; }

    public boolean isActive() { return !future.isDone(); }

    /** Hot-swap in a fresher plan without releasing keys; passed waypoints self-advance. */
    public void swapRoute(PlannedRoute replacement) {
        if (replacement == null || replacement.waypoints().size() <= 1) return;
        // The search began from a snapshot several ticks old, so the route's early waypoints
        // are typically behind the player by now. Fast-forward to the first waypoint AFTER
        // the nearest one, or the follower would steer backwards onto the stale prefix.
        int nearest = 0;
        ClientPlayerEntity player = client.player;
        if (player != null) {
            Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
            double best = Double.MAX_VALUE;
            List<PlannedRoute.Waypoint> waypoints = replacement.waypoints();
            for (int i = 0; i < waypoints.size(); i++) {
                double distance = waypoints.get(i).position().squaredDistanceTo(feet);
                if (distance < best) { best = distance; nearest = i; }
            }
        }
        this.route = replacement;
        this.index = Math.max(1, Math.min(nearest + 1, replacement.waypoints().size() - 1));
        this.bestRemaining = Double.POSITIVE_INFINITY;
        this.lastProgressTick = ticks;
        this.replanRequested = false;
        this.committedPrimitive = null;
        this.closestApproach = Double.POSITIVE_INFINITY;
        this.recedeTicks = 0;
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
        if (!TalosClient.tickBudget().hasBudgetRemaining()) {
            scheduleDelay();
            return;
        }

        advance(player);
        if (goal.test(player.getBlockPos())) {
            finish(true, "Arrived");
            return;
        }
        // A PARTIAL route means the planner ran out of budget, not that the route is good:
        // start extending IMMEDIATELY in the background — waiting until the route nearly
        // ran out was the visible walk-a-few-steps-then-stop-and-plan stutter.
        if (!route.reachedGoal() && replanRequest != null && !replanRequested) {
            replanRequested = true;
            replanRequest.run();
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

        // Think ahead before committing to an edit: near the frayed end of a PARTIAL route,
        // a MINE waypoint may be a dig toward a dead end the planner never saw past (caves).
        // The extension request above has already fired — hold position until the fresher,
        // deeper plan is swapped in rather than tunneling blind.
        boolean nearRouteEnd = route.waypoints().size() - index
                <= Math.max(6, route.waypoints().size() / 4);
        if (waypoint.via() == Primitive.MINE && !route.reachedGoal() && nearRouteEnd) {
            status("planning ahead (mining)");
            releaseInputs();
            scheduleDelay();
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
        advanceJitter(); // pre-sampled look noise: slot 0 is THIS tick, the rest feed rollouts
        Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d aim = aimPoint(feet, waypoint);
        renderAim(player, aim);
        float yaw = yawTo(feet, aim);
        Input selected = chooseInput(live, profile, waypoint, yaw);
        if (live.fluid() == MotionState.Fluid.WATER) {
            updateAirMode(player);
            player.setPitch(swimPitch(player, waypoint));
        }
        apply(player, selected, live, waypoint);
        // Predict AFTER the humanized view is applied: the trail starts from the yaw the
        // player really faces and keeps turning through the same view model each tick, so
        // orange shows where the body will truly be — friction, effects, momentum, AND the
        // natural eye movement included.
        renderPrediction(live, selected, profile, player.getYaw());
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
        // Vertical position in water is owned entirely by the pitch controller (swimPitch);
        // jump is reserved for climbing out onto a bank at the end of the swim.
        if (live.fluid() == MotionState.Fluid.WATER) {
            ClientPlayerEntity player = client.player;
            boolean exitClimb = player != null
                    && waypoint.position().y >= waterSurfaceY(player) - 0.25
                    && horizontalDistance(live.position(), waypoint.position()) < 2.5;
            return new Input(1, 0, exitClimb, true, false, yaw);
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
        // The geometric tail is v + v*d + ... = v/(1-d), with d = slipperiness*.91 read from
        // the actual block underfoot: on ice (0.98) the stop takes ~4x normal ground, and a
        // fixed d=.6*.91 made the follower brake far too late, overshoot, and oscillate.
        Input held = new Input(1, 0, wantsJump, wantsSprint, wantsSneak, yaw);
        MotionState heldLanding = approachingFinal ? predictLanding(live, held, profile) : null;
        double speed = Math.hypot(live.velocity().x, live.velocity().z);
        double drag = Math.min(0.985,
                PlayerMotion.slipperinessBelow(client.world, live.position()) * 0.91);
        double stoppingDistance = speed / (1.0 - drag);
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
        // Steering is part of the prediction, not a fixed bearing: on slick ground (or with
        // sideways momentum) also offer drift-compensated headings, and let the physics
        // rollouts — which model live speed, effects, and per-block friction — pick the yaw
        // whose PREDICTED positions stay on the route. Aiming straight at the waypoint while
        // sliding was what made ice turns overshoot and double back.
        for (float heading : steeringYaws(live, waypoint, yaw, drag)) {
            candidates.add(new Input(1, 0, wantsJump,
                    wantsSprint && (!brakeNeeded || precisionLaunch), wantsSneak, heading));
        }
        // Opportunistic hop, decided on the go: plans no longer contain SPRINT_JUMP for
        // open ground, so during a plain sprint the follower may offer a jump variant and
        // let the rollout prove it is safe and faster here (a gap edge, a slope lip).
        if (primitive == Primitive.SPRINT && live.onGround() && !brakeNeeded && !approachingFinal) {
            candidates.add(new Input(1, 0, true, true, false, yaw));
        }
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
            if (!TalosClient.tickBudget().hasBudgetRemaining() && bestScore > Double.NEGATIVE_INFINITY) {
                break;
            }
            MotionState state = live;
            double score = 0.0;
            double oldDistance = horizontalDistance(state.position(), waypoint.position());
            // The rollout turns its head like the real player will: yaw evolves through the
            // eased view model each tick instead of snapping to the candidate heading.
            double simYaw = client.player.getYaw();
            double simVel = yawVelocity;
            double accel = turnAccel();
            // Ice's consequences play out over dozens of ticks; a 6-tick window scored the
            // slide as fine because the overshoot hadn't HAPPENED yet inside the window.
            int horizon = drag > 0.62 ? ROLLOUT_TICKS * 3 : ROLLOUT_TICKS;
            for (int tick = 0; tick < horizon; tick++) {
                double[] view = steerStep(simYaw, simVel, candidate.yaw(), accel, jitterAt(tick));
                simYaw = view[0];
                simVel = view[1];
                state = PlayerMotion.step(client.world, state, withYaw(candidate, (float) simYaw), profile);
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
            MotionState landing = (candidate.jump() && live.onGround())
                    || (approachingFinal && !live.onGround())
                    ? predictLanding(live, candidate, profile) : null;
            // A jump is committed the tick it launches: it must be simulated THROUGH its
            // landing before being chosen. No safe landing within the horizon, a landing in
            // a hole, or a landing far off the route line disqualifies the launch — this is
            // what stops jumps the player cannot actually make.
            if (candidate.jump() && live.onGround()) {
                if (landing == null) {
                    score -= 400.0;
                } else {
                    if (landing.position().y < Math.min(from.y, waypoint.position().y) - 2.5) {
                        score -= 300.0;
                    }
                    score -= distanceToSegment(landing.position(), from, waypoint.position()) * 6.0;
                }
            }
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
                score += 1.2; // Commit-window hysteresis, still overridable by a clearly safer line.
            }
            // Stable ordering is the deterministic tie-break: forward is first. Strafing must
            // beat it materially, preventing close scores from alternating left/right — the
            // visible symptom of a thin margin was sideways wiggle on hills.
            double margin = candidate.strafe() == 0.0F ? 0.0 : 1.4;
            if (score > bestScore + margin) { bestScore = score; best = candidate; }
        }
        int chosenStrafe = Float.compare(best.strafe(), 0.0F);
        if (chosenStrafe != 0) {
            committedStrafe = chosenStrafe;
            strafeCommitUntil = ticks + 7;
        } else if (ticks >= strafeCommitUntil) {
            committedStrafe = 0;
        }
        return best;
    }

    /**
     * Candidate headings for the rollout scorer. The first is the plain bearing; on
     * low-friction ground with real sideways momentum, drift-compensated headings are added:
     * the aim direction is the route direction minus the lateral velocity component scaled
     * by the drag tail (d/(1-d)), i.e. steer INTO the slide roughly as hard as the ice will
     * keep pushing. The simulation, not this heuristic, makes the final choice.
     */
    private float[] steeringYaws(MotionState live, PlannedRoute.Waypoint waypoint,
            float base, double drag) {
        Vec3d to = waypoint.position().subtract(live.position());
        double length = Math.hypot(to.x, to.z);
        Vec3d velocity = live.velocity();
        double speed = Math.hypot(velocity.x, velocity.z);
        if (length < 1.0E-6 || speed < 0.06) return new float[]{base};
        double tx = to.x / length, tz = to.z / length;
        double along = velocity.x * tx + velocity.z * tz;
        double lateralX = velocity.x - tx * along, lateralZ = velocity.z - tz * along;
        double lateral = Math.hypot(lateralX, lateralZ);
        // Normal ground with an aligned velocity needs no compensation candidates.
        if (drag <= 0.62 && lateral < 0.05) return new float[]{base};
        double gain = Math.min(3.5, drag / (1.0 - drag) * 0.35) / Math.max(speed, 0.15);
        double steerX = tx - lateralX * gain, steerZ = tz - lateralZ * gain;
        float compensated = (float) Math.toDegrees(Math.atan2(-steerX, steerZ));
        float correction = net.minecraft.util.math.MathHelper.wrapDegrees(compensated - base);
        if (Math.abs(correction) < 3.0F) return new float[]{base};
        correction = Math.max(-55.0F, Math.min(55.0F, correction));
        return new float[]{base, base + correction, base + correction * 0.5F};
    }

    /** Refresh a short-lived, exact-hitbox trail for the input selected from this live state. */
    private void renderPrediction(MotionState live, Input selected, MovementProfile profile,
            float appliedYaw) {
        MotionState predicted = live;
        double simYaw = appliedYaw;
        double simVel = yawVelocity;
        double accel = turnAccel();
        for (int i = 0; i < ROLLOUT_TICKS * 2; i++) {
            double[] view = steerStep(simYaw, simVel, selected.yaw(), accel, jitterAt(i));
            simYaw = view[0];
            simVel = view[1];
            predicted = PlayerMotion.step(client.world, predicted,
                    withYaw(selected, (float) simYaw), profile);
            RenderQueue.add("talos-sim-tick:" + i,
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
        if (dx == 0 && dz == 0 && destination.getY() < origin.getY()) {
            // Vertical shaft: dig the block directly underfoot.
            if (blocking(origin.down())) block = origin.down();
        }
        // MINE waypoints describe the post-edit cell, so inspect the intervening tunnel.
        for (int step = 1; step <= 3 && block == null && (dx != 0 || dz != 0); step++) {
            BlockPos feet = origin.add(dx * step, 0, dz * step);
            if (blocking(feet)) block = feet;
            else if (blocking(feet.up())) block = feet.up();
            if (feet.getX() == destination.getX() && feet.getZ() == destination.getZ()) break;
        }
        if (block == null) { breaking = null; return false; }
        status("mining");
        // Same aim language as the action cube-aim: yellow cube = the block being worked,
        // red X = the face point under attack.
        RenderQueue.add("talos-aim-cube", new Box(block).expand(0.002),
                AIM_TARGET_COLOR, PREDICTION_TTL * 3);
        Vec3d face = Vec3d.ofCenter(block).add(0.0, 0.5, 0.0);
        RenderQueue.add("talos-aim-mark",
                new Box(face.x - 0.07, face.y - 0.07, face.z - 0.07,
                        face.x + 0.07, face.y + 0.07, face.z + 0.07),
                AIM_GAZE_COLOR, PREDICTION_TTL * 3);
        releaseInputs();
        // Face the block with the eased look before a single swing: converge this tick,
        // dig on the tick the crosshair actually rests on it.
        if (!steerLook(player, Vec3d.ofCenter(block), 12.0)) return true;
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
        if (destination.getX() == feet.getX() && destination.getZ() == feet.getZ()
                && destination.getY() > feet.getY()) {
            return pillar(player);
        }
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
            // Look at the actual anchor face (eased) before the click lands on it.
            if (!steerLook(player, hit, 14.0)) return true;
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

    /** Nerdpole: hold jump and place under the feet once they clear the origin cell. */
    private boolean pillar(ClientPlayerEntity player) {
        status("pillaring up");
        int slot = findBlockSlot(player);
        if (slot < 0) { finish(false, "Ran out of pillar blocks"); return true; }
        releaseInputs();
        client.options.jumpKey.setPressed(true);
        if (pillarOrigin == null || player.isOnGround()) {
            pillarOrigin = player.getBlockPos().toImmutable();
        }
        BlockPos anchor = pillarOrigin.down();
        Vec3d hit = Vec3d.ofCenter(anchor).add(0.0, 0.5, 0.0);
        // Nerdpole gaze: ease the view down onto the anchor while airborne, like a player.
        boolean aimed = steerLook(player, hit, 22.0);
        player.getInventory().setSelectedSlot(slot);
        // Jump input applies after this tick; place only once the feet clear the target cell,
        // since same-tick jump+place is rejected by the placement collision check.
        if (aimed && player.getY() >= pillarOrigin.getY() + 0.42) {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, anchor, false));
            player.swingHand(Hand.MAIN_HAND);
        }
        return true;
    }

    private void advance(ClientPlayerEntity player) {
        while (index < route.waypoints().size()) {
            Vec3d target = route.waypoints().get(index).position();
            Vec3d previous = route.waypoints().get(index - 1).position();
            double edgeX = target.x - previous.x, edgeZ = target.z - previous.z;
            boolean horizontalEdge = edgeX * edgeX + edgeZ * edgeZ > 1.0E-6;
            double dx = player.getX() - target.x, dz = player.getZ() - target.z;
            // Slopes and jump arcs put the player up to a block above/below the waypoint
            // while clearly passing it; only vertical edges (pillar/shaft) need the exact Y.
            double yTolerance = horizontalEdge ? 1.25 : 0.75;
            boolean reached = dx * dx + dz * dz < REACHED_SQUARED
                    && Math.abs(player.getY() - target.y) <= yTolerance;
            // A vertical edge has no horizontal direction to be "past"; it can only be
            // completed by actually reaching the waypoint.
            boolean passed = horizontalEdge
                    && (player.getX() - target.x) * edgeX
                    + (player.getZ() - target.z) * edgeZ >= 0.0;
            if (!reached && !passed) break;
            waypointAdvanced();
        }
        orbitBreaker(player);
    }

    private void waypointAdvanced() {
        index++;
        bestRemaining = Double.POSITIVE_INFINITY;
        lastProgressTick = ticks;
        closestApproach = Double.POSITIVE_INFINITY;
        recedeTicks = 0;
    }

    /**
     * Circling an intermediate waypoint we brushed but never "reached" (typical on hills,
     * where the Y check used to fail forever) shows up as the horizontal distance growing
     * again after a close pass. Give up on that waypoint and steer for the next one.
     */
    private void orbitBreaker(ClientPlayerEntity player) {
        if (index >= route.waypoints().size() - 1) return; // never skip the final goal
        Vec3d target = route.waypoints().get(index).position();
        Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
        double horizontal = horizontalDistance(feet, target);
        if (horizontal < closestApproach - 1.0E-3) {
            closestApproach = horizontal;
            recedeTicks = 0;
        } else if (closestApproach < 0.9) {
            recedeTicks++;
        }
        if (recedeTicks >= 4 && Math.abs(player.getY() - target.y) <= 1.5) {
            waypointAdvanced();
        }
    }

    private void updateProgress(Vec3d position, Vec3d target) {
        double remaining = position.squaredDistanceTo(target);
        if (remaining + .04 < bestRemaining) {
            bestRemaining = remaining;
            lastProgressTick = ticks;
        }
    }

    /**
     * Steering ahead of a corner: once the current waypoint is close, the gaze (and with it
     * the walk direction) starts blending toward the next one, so turns are carved as smooth
     * arcs the way a player steers, instead of pivoting on top of every node.
     */
    private Vec3d aimPoint(Vec3d feet, PlannedRoute.Waypoint waypoint) {
        Vec3d target = waypoint.position();
        if (index + 1 >= route.waypoints().size()) return target;
        double distance = horizontalDistance(feet, target);
        if (distance >= 1.6) return target;
        double blend = Math.min(0.6, (1.6 - distance) / 1.6);
        return target.lerp(route.waypoints().get(index + 1).position(), blend);
    }

    /**
     * Always-visible aim readout while navigating: yellow marker = the point steering wants
     * to face (the blended look-ahead target), red dot = where the crosshair points right
     * now at that same depth. The red dot easing onto the yellow marker IS the humanized
     * look, made visible.
     */
    private void renderAim(ClientPlayerEntity player, Vec3d aim) {
        Vec3d eyeTarget = aim.add(0.0, 1.62, 0.0); // head of the next hitbox, where gaze rests
        RenderQueue.add("talos-goto-aim",
                new Box(eyeTarget.x - 0.09, eyeTarget.y - 0.09, eyeTarget.z - 0.09,
                        eyeTarget.x + 0.09, eyeTarget.y + 0.09, eyeTarget.z + 0.09),
                AIM_TARGET_COLOR, PREDICTION_TTL);
        Vec3d eye = player.getEyePos();
        Vec3d gaze = eye.add(player.getRotationVecClient()
                .multiply(Math.max(eye.distanceTo(eyeTarget), 1.0)));
        RenderQueue.add("talos-goto-gaze",
                new Box(gaze.x - 0.05, gaze.y - 0.05, gaze.z - 0.05,
                        gaze.x + 0.05, gaze.y + 0.05, gaze.z + 0.05),
                AIM_GAZE_COLOR, PREDICTION_TTL);
    }

    private void apply(ClientPlayerEntity player, Input input, MotionState live,
            PlannedRoute.Waypoint waypoint) {
        // Set the complete desired state directly. Releasing first created a real per-tick
        // false/true pulse that broke continuous sprint-jump holds and bunny-hop momentum.
        client.options.forwardKey.setPressed(input.forward() > .1F);
        client.options.backKey.setPressed(input.forward() < -.1F);
        client.options.leftKey.setPressed(input.strafe() > .1F);
        client.options.rightKey.setPressed(input.strafe() < -.1F);
        client.options.jumpKey.setPressed(input.jump());
        client.options.sprintKey.setPressed(input.sprint());
        client.options.sneakKey.setPressed(input.sneak());
        steerYaw(player, input.yaw());
        // Water pitch is owned by the swim controller; on land the gaze is humanized.
        if (live.fluid() == MotionState.Fluid.NONE) steerPitch(player, waypoint);
    }

    /**
     * Humanized view control: turn speed is sampled from the active humanization profile,
     * approached with bounded angular acceleration and eased out near the target, with
     * profile-scaled noise while turning. Small corrections drift; big course changes are
     * quick flicks that decelerate — never a snap. The closed rollout loop re-decides from
     * the real state every tick, so the lagging view stays accurate.
     */
    /**
     * Ease the whole view (yaw AND pitch) onto a world point with the same smoothed turn
     * machinery used for walking, and report when the crosshair is close enough to act.
     * Every block the follower breaks or places gates its interaction on this, so edits
     * only ever happen to blocks the player is visibly looking at.
     */
    private boolean steerLook(ClientPlayerEntity player, Vec3d point, double toleranceDegrees) {
        float[] desired = RotationHumanizer.yawPitchTo(player.getEyePos(), point);
        steerYaw(player, desired[0]);
        double pitchStep = (desired[1] - player.getPitch()) * 0.55;
        pitchStep = Math.max(-14.0, Math.min(14.0, pitchStep));
        player.setPitch(net.minecraft.util.math.MathHelper.clamp(
                (float) (player.getPitch() + pitchStep + steerRng.nextGaussian() * 0.15),
                -90.0F, 90.0F));
        double yawError = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(
                desired[0] - player.getYaw()));
        double pitchError = Math.abs(desired[1] - player.getPitch());
        return yawError <= toleranceDegrees && pitchError <= toleranceDegrees;
    }

    /** Samples the turn-speed model lazily and returns the angular acceleration bound. */
    private double turnAccel() {
        HumanizationProfile profile = TalosClient.humanizer().defaultProfile();
        if (maxTurnSpeed <= 0.0) {
            maxTurnSpeed = Math.max(6.0, profile.rotationSpeedDegPerTick().sample(steerRng));
        }
        return Math.max(2.0, profile.maxAngularAccelDegPerTick2());
    }

    /** Rolls the pre-sampled look noise forward one real tick. Call once per game tick. */
    private void advanceJitter() {
        double stdev = TalosClient.humanizer().defaultProfile().pathDeviationStdev() * 1.5;
        if (!jitterSeeded) {
            for (int i = 0; i < plannedJitter.length; i++) {
                plannedJitter[i] = steerRng.nextGaussian() * stdev;
            }
            jitterSeeded = true;
            return;
        }
        System.arraycopy(plannedJitter, 1, plannedJitter, 0, plannedJitter.length - 1);
        plannedJitter[plannedJitter.length - 1] = steerRng.nextGaussian() * stdev;
    }

    private double jitterAt(int tick) {
        return plannedJitter[Math.min(tick, plannedJitter.length - 1)];
    }

    /**
     * One tick of the eased view model, noise included: returns {yaw, velocity}.
     * Anticipatory braking (v^2 <= 2*a*remaining) means the view stops ON the heading
     * instead of over-turning past it. This exact model — with the SAME pre-sampled
     * jitter — runs both on the real view and inside every rollout, landing prediction,
     * and the orange trail, so simulated physics always see the yaw the player will
     * REALLY have that tick, natural eye movement and all.
     */
    private double[] steerStep(double yaw, double velocity, float targetYaw, double accel,
            double jitter) {
        double delta = net.minecraft.util.math.MathHelper.wrapDegrees(targetYaw - yaw);
        double cap = Math.abs(delta) > 60.0 ? Math.max(maxTurnSpeed * 2.5, 28.0) : maxTurnSpeed;
        double stopCap = Math.sqrt(2.0 * accel * Math.abs(delta));
        double limit = Math.min(cap, stopCap);
        double desired = Math.max(-limit, Math.min(limit, delta * 0.45));
        velocity += Math.max(-accel, Math.min(accel, desired - velocity));
        double applied = Math.abs(delta) > 1.0 ? jitter : 0.0;
        return new double[]{yaw + velocity + applied, velocity};
    }

    private void steerYaw(ClientPlayerEntity player, float targetYaw) {
        double accel = turnAccel();
        double delta = net.minecraft.util.math.MathHelper.wrapDegrees(targetYaw - player.getYaw());
        double[] next = steerStep(player.getYaw(), yawVelocity, targetYaw, accel, jitterAt(0));
        yawVelocity = next[1];
        float yaw = (float) next[0];
        if (Math.abs(delta) < 1.2 && Math.abs(yawVelocity) < 1.2) {
            yaw = (float) (player.getYaw() + delta * 0.6); // settle without a visible snap
            yawVelocity *= 0.5;
        }
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
    }

    private static Input withYaw(Input input, float yaw) {
        return new Input(input.forward(), input.strafe(), input.jump(), input.sprint(),
                input.sneak(), yaw);
    }

    /**
     * Land pitch never affects movement, so the gaze is free to look where a player would:
     * gently toward the route ahead, with a slowly wandering offset so the view is alive
     * instead of locked to the horizon.
     */
    private void steerPitch(ClientPlayerEntity player, PlannedRoute.Waypoint waypoint) {
        if (--pitchWanderTicks <= 0) {
            pitchWanderTicks = 25 + steerRng.nextInt(35);
            pitchWander = steerRng.nextGaussian() * 3.5;
        }
        Vec3d eye = player.getEyePos();
        Vec3d target = waypoint.position();
        double horizontal = Math.max(horizontalDistance(eye, target), 3.0);
        // Gaze rests on the HEAD of the next hitbox (eye height above its feet), so climbs
        // and jumps are sighted at the level the body actually has to arrive at.
        double toward = Math.toDegrees(Math.atan2(eye.y - (target.y + 1.62), horizontal));
        double desired = Math.max(-30.0, Math.min(35.0, toward + pitchWander));
        double step = Math.max(-2.5, Math.min(2.5, desired - player.getPitch()));
        player.setPitch((float) (player.getPitch() + step
                + steerRng.nextGaussian() * 0.12));
    }

    private void updateAirMode(ClientPlayerEntity player) {
        int maxAir = player.getMaxAir();
        if (player.getAir() <= Math.max(20, maxAir / 5)) surfacingForAir = true;
        else if (player.getAir() >= maxAir * 3 / 4) surfacingForAir = false;
    }

    /**
     * Swimming is held AT the water/air interface: a proportional pitch controller keeps the
     * compact hitbox straddling the surface (in water, head in air) so it breathes while
     * sprint-swimming. Only a genuinely deep waypoint — with air to spare — dives off it.
     */
    private float swimPitch(ClientPlayerEntity player, PlannedRoute.Waypoint waypoint) {
        double surface = waterSurfaceY(player);
        Vec3d target = waypoint.position();
        Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
        if (!surfacingForAir && target.y < surface - 2.0) {
            double horizontal = Math.max(horizontalDistance(feet, target), 0.001);
            return (float) Math.max(-60.0, Math.min(60.0,
                    Math.toDegrees(Math.atan2(feet.y - target.y, horizontal))));
        }
        double error = feet.y - (surface - SURFACE_FEET_DEPTH);
        return (float) Math.max(-45.0, Math.min(45.0, error * 120.0));
    }

    /** Y of the top of the water column at the player's position. */
    private double waterSurfaceY(ClientPlayerEntity player) {
        int x = player.getBlockX(), z = player.getBlockZ();
        int y = player.getBlockY();
        int limit = y + 64;
        while (y < limit
                && client.world.getFluidState(new BlockPos(x, y + 1, z)).isIn(FluidTags.WATER)) {
            y++;
        }
        BlockPos top = new BlockPos(x, y, z);
        var fluid = client.world.getFluidState(top);
        double height = fluid.isIn(FluidTags.WATER) ? fluid.getHeight(client.world, top) : 0.9;
        return y + height;
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

    /** Continue a held input until the arc lands, with the view turning realistically. */
    private MotionState predictLanding(MotionState start, Input input,
            MovementProfile profile) {
        MotionState state = start;
        boolean airborne = !start.onGround();
        double simYaw = client.player == null ? input.yaw() : client.player.getYaw();
        double simVel = yawVelocity;
        double accel = turnAccel();
        for (int tick = 0; tick < LANDING_ROLLOUT_TICKS; tick++) {
            double[] view = steerStep(simYaw, simVel, input.yaw(), accel, jitterAt(tick));
            simYaw = view[0];
            simVel = view[1];
            state = PlayerMotion.step(client.world, state, withYaw(input, (float) simYaw), profile);
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
                Text.literal("§bTalos §7» §f" + mode), true);
    }

    public void cancel() { finish(false, "Pathing cancelled"); _break(); }

    private void finish(boolean success, String detail) {
        releaseInputs();
        if (client.player != null) client.player.sendMessage(Text.literal(
                (success ? "§aTalos §7» §f" : "§cTalos §7» §f") + detail), true);
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

    @Override public Set<Object> getMutexKeys() { return Set.of("talos-player-movement"); }
}
