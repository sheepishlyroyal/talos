package dev.talos.client.pathing.sim;

import dev.talos.client.TalosClient;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.RotationHumanizer;
import dev.talos.client.humanize.SeededRng;
import dev.talos.client.log.TalosLog;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.render.RenderQueue;
import dev.talos.client.task.TalosTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closed-loop route follower. The route contains geometric intent, never a recorded input
 * schedule: every client tick starts from the real player state and runs fresh short rollouts.
 */
public final class SimFollowTask extends TalosTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimFollowTask.class);
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
    // Desync watchdog: single-tick spike threshold, EWMA decay, and the sustained-drift
    // trip. The score's steady state is drift/(1-decay), so 0.6 means ~0.15 blocks of
    // unexplained motion EVERY tick — small individually, but the simulator is normally
    // millimeter-accurate, so sustained centi-block error is a real model/world mismatch.
    private static final double DRIFT_SPIKE = 0.35;
    private static final double DRIFT_DECAY = 0.75;
    private static final double DRIFT_SCORE_HIGH = 0.6;
    private static final int DRIFT_HIGH_TICKS = 10;
    private static final int DRIFT_LOG_INTERVAL = 40;
    private static final int DRIFT_SUPPRESS_TICKS = 5;
    private static final int CORRECTION_HOLD_TICKS = 5;
    // Waiting on chunk streaming is not "stuck", but a dead frontier must still fail:
    // 15 seconds is longer than any healthy server takes to stream one more chunk ring.
    private static final int CHUNK_WAIT_LIMIT_TICKS = 300;
    // Per-tick scorer complaints, ringed over the same window the stuck timer watches.
    private static final int CAUSE_BUMP = 1;
    private static final int CAUSE_JUMP = 2;
    private static final int CAUSE_FIT = 4;
    private static final int CAUSE_BRAKE = 8;

    private final Minecraft client;
    private PlannedRoute route;
    private volatile Predicate<BlockPos> goal;
    private final CompletableFuture<PathResult> future;
    private Runnable replanRequest;
    private boolean replanRequested;
    private int index;
    private int ticks;
    private int lastProgressTick;
    private double bestRemaining = Double.POSITIVE_INFINITY;
    private BlockPos breaking;
    private BlockPos pillarOrigin;
    private String lastStatus = "";
    private Primitive committedPrimitive;
    private int committedStrafe;
    private int strafeCommitUntil;
    private boolean surfacingForAir;
    // Orbit breaker: waypoints we brushed past but never technically "reached" (slopes,
    // jump arcs) must not trap the follower in circles around them.
    private double closestApproach = Double.POSITIVE_INFINITY;
    private int recedeTicks;
    private int lastExtensionTick = -1000;
    // Humanized steering state: the view follows the route with bounded, eased turns
    // plus profile-scaled noise instead of snapping.
    private final SeededRng steerRng = new SeededRng(System.nanoTime());
    private double yawVelocity;
    private double maxTurnSpeed;
    private double pitchWander;
    private int pitchWanderTicks;
    // Navigation cube-aim session: the gaze target is not the waypoint itself but a random
    // spot (the red mark) on a visible face of a full-block yellow cube, itself centered on
    // a random center-biased point of the aim anchor's standing hitbox. Resampled whenever
    // the anchor strays, so the gaze wanders the way a walking player's does.
    private Vec3 navAnchor;
    private Vec3 navCubeCenter;
    private Vec3 navMark;
    // The random look noise is PRE-SAMPLED: slot 0 is applied to the real view this tick,
    // slot i feeds simulated tick i of every rollout/prediction. Random to an observer,
    // fully known to the simulator — so predictions include the wobble before it happens.
    private final double[] plannedJitter = new double[ROLLOUT_TICKS * 2];
    private boolean jitterSeeded;
    // Desync watchdog: last tick's 1-step predicted position (null when no rollout ran),
    // an exponentially-decayed drift score, and suppression windows around events where a
    // prediction/actual mismatch is EXPECTED (server corrections, route swaps).
    private static volatile SimFollowTask activeFollower;
    private Vec3 predictedNext;
    private double driftScore;
    private int driftHighTicks;
    private int lastDriftLogTick = -1000;
    private int driftSuppressedUntil;
    private int correctionHoldTicks;
    private int recalibratingUntil;
    private int chunkWaitTicks;
    // What the candidate scorer complained about each tick, as bit-flags over the stuck
    // window: turns "stuck near waypoint N" into "stuck ... - likely cause: <plain words>".
    private final byte[] causeRing = new byte[STUCK_TICKS];
    private int causeCursor;

    public SimFollowTask(Minecraft client, PlannedRoute route, Predicate<BlockPos> goal,
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

    /** The route currently being followed (full resolution, immutable). */
    public PlannedRoute currentRoute() { return route; }

    /** Waypoints left in front of the follower; the engine cuts searches early when low. */
    public int remainingWaypoints() { return Math.max(0, route.waypoints().size() - index); }

    /**
     * An extension search finished WITHOUT producing a swappable route. Re-arm the request
     * flag so the adaptive re-request cadence (5 ticks when starving) keeps firing; leaving
     * it latched silenced extensions exactly while the route was fraying.
     */
    public void extensionSettled() { replanRequested = false; }

    /** Moving-goal retarget: refresh the arrival test so we stop at the NEW target. */
    public void setGoal(Predicate<BlockPos> fresh) {
        if (fresh != null) {
            this.goal = fresh;
            TalosLog.trace("movement", "follower retargeted at waypoint " + index);
        }
    }

    /** Hot-swap in a fresher plan without releasing keys; passed waypoints self-advance. */
    public void swapRoute(PlannedRoute replacement) {
        if (replacement == null || replacement.waypoints().size() <= 1) return;
        // Momentum guard: stitched extensions are LONGER than the current route and always
        // welcome. A replacement that neither reaches the goal nor extends what we already
        // have would only reset the course — discard it and allow a later attempt.
        if (!replacement.reachedGoal()
                && replacement.waypoints().size() <= route.waypoints().size()
                && route.waypoints().size() - index > 16) {
            replanRequested = false;
            return;
        }
        // The search began from a snapshot several ticks old, so the route's early waypoints
        // are typically behind the player by now. Fast-forward to the first waypoint AFTER
        // the nearest one, or the follower would steer backwards onto the stale prefix.
        // For a STITCHED route (identical shared prefix — same first waypoint object) the
        // scan is floored just behind the current index: a route that folds back near the
        // player must not teleport progress onto a spatially-close but distant leg.
        int nearest = 0;
        LocalPlayer player = client.player;
        if (player != null) {
            List<PlannedRoute.Waypoint> waypoints = replacement.waypoints();
            int floor = !route.waypoints().isEmpty() && !waypoints.isEmpty()
                    && waypoints.getFirst() == route.waypoints().getFirst()
                    ? Math.max(0, Math.min(index, waypoints.size() - 1) - 4) : 0;
            nearest = floor;
            Vec3 feet = new Vec3(player.getX(), player.getY(), player.getZ());
            double best = Double.MAX_VALUE;
            for (int i = floor; i < waypoints.size(); i++) {
                double distance = waypoints.get(i).position().distanceToSqr(feet);
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
        // A swapped route legitimately changes the chosen input mid-prediction; give the
        // watchdog a few ticks before trusting drift numbers again.
        this.predictedNext = null;
        this.driftSuppressedUntil = ticks + DRIFT_SUPPRESS_TICKS;
        TalosLog.trace("movement", "route swapped; resumed at waypoint " + index
                + "/" + replacement.waypoints().size());
    }

    /**
     * Rubberband hook, called on the client thread from the PlayerPositionLookS2CPacket
     * handler mixin: the server just force-set our position, so every in-flight prediction
     * is fiction. The active follower releases keys for a few ticks, suppresses the desync
     * watchdog, and resets its progress trackers — the closed loop then resumes naturally
     * from the corrected position.
     */
    public static void onServerCorrection() {
        SimFollowTask follower = activeFollower;
        if (follower != null && follower.isActive()) follower.serverCorrection();
    }

    private void serverCorrection() {
        correctionHoldTicks = CORRECTION_HOLD_TICKS;
        predictedNext = null;
        driftScore = 0.0;
        driftHighTicks = 0;
        driftSuppressedUntil = ticks + CORRECTION_HOLD_TICKS + DRIFT_SUPPRESS_TICKS;
        bestRemaining = Double.POSITIVE_INFINITY;
        lastProgressTick = ticks;
        closestApproach = Double.POSITIVE_INFINITY;
        recedeTicks = 0;
        status("resyncing (server correction)");
    }

    @Override public void initialize() {
        activeFollower = this;
        TalosLog.trace("movement", "segment start with " + route.waypoints().size()
                + " waypoints");
    }
    @Override public boolean condition() { return !future.isDone(); }
    @Override public void increment() { ticks++; }

    @Override
    public void body() {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            finish(false, "World unloaded while navigating");
            return;
        }
        if (!TalosClient.tickBudget().hasBudgetRemaining()) {
            predictedNext = null; // skipped tick: the 1-step prediction no longer lines up
            scheduleDelay();
            return;
        }
        // A server correction teleported us: hold everything for a few ticks so the client
        // finishes settling on the corrected position, then let the closed loop re-decide
        // from live state (it replans from wherever we actually are, no special path).
        if (correctionHoldTicks > 0) {
            correctionHoldTicks--;
            status("resyncing (server correction)");
            releaseInputs();
            lastProgressTick = ticks; // the teleport is not the follower's lack of progress
            scheduleDelay();
            return;
        }
        // Human mode micro-break: stand still and hold no keys for the break's duration, the
        // way a real player pauses mid-run. The stuck timer is bypassed — a break isn't a
        // failure to make progress — and arrival is still checked so a break ON the goal ends.
        if (TalosClient.humanizer().sessionArc().onBreak()) {
            advance(player);
            if (goal.test(player.blockPosition())) { finish(true, "Arrived"); return; }
            status("idle (break)");
            releaseInputs();
            lastProgressTick = ticks;
            scheduleDelay();
            return;
        }
        checkPredictionDrift(player);

        advance(player);
        if (goal.test(player.blockPosition())) {
            finish(true, "Arrived");
            return;
        }
        // A PARTIAL route means the planner ran out of budget, not that the route is good:
        // extend in the background while moving. Rate-limited so back-to-back searches
        // don't churn plans faster than the follower can consume them — except when the
        // remaining route is short, where a fresh extension is the difference between
        // flowing onward and grinding to a halt at the frayed end.
        int remaining = route.waypoints().size() - index;
        if (!route.reachedGoal() && replanRequest != null && !replanRequested
                && ticks - lastExtensionTick >= (remaining <= 12 ? 5 : 25)) {
            replanRequested = true;
            lastExtensionTick = ticks;
            replanRequest.run();
        }
        if (index >= route.waypoints().size()) {
            if (!route.reachedGoal()) {
                // A dried-up PARTIAL route is not a failure: the extension search is already
                // running. Hold here and let the stitched continuation swap in — failing the
                // segment forced a full stop and a from-scratch replan, the visible stutter.
                status("extending route");
                releaseInputs();
                if (ticks - lastProgressTick > STUCK_TICKS) {
                    finish(false, "Route ran out and no extension arrived" + dominantCause());
                }
                scheduleDelay();
                return;
            }
            finish(false, "Nodes ended before the goal was reached" + dominantCause());
            return;
        }

        // Keep the checkpoint boxes alive for the WHOLE run: the engine's plan-time draw
        // had a fixed TTL, so any goto outliving it (and every later replan gap) went
        // visually dark. Re-drawing the remaining route once a second is the fix.
        if (ticks % 20 == 0) RouteRenderer.render(route, index);

        PlannedRoute.Waypoint waypoint = route.waypoints().get(index);
        // Long routes can outrun the server's chunk streaming: a waypoint in an unloaded
        // chunk means every rollout would read air and steer into fiction. Hold at the
        // frontier — genuinely waiting is not "stuck" — but bound the wait so a stalled
        // stream still fails with a message that names what actually happened.
        if (!client.level.hasChunkAt(BlockPos.containing(waypoint.position()))) {
            if (++chunkWaitTicks > CHUNK_WAIT_LIMIT_TICKS) {
                finish(false, "Waited 15s for chunks to load near waypoint " + index
                        + " and gave up");
                return;
            }
            status("waiting for chunks");
            releaseInputs();
            lastProgressTick = ticks; // stuck timer generously bypassed while waiting
            scheduleDelay();
            return;
        }
        chunkWaitTicks = 0;
        updateProgress(new Vec3(player.getX(), player.getY(), player.getZ()), waypoint.position());
        if (ticks - lastProgressTick > STUCK_TICKS) {
            TalosLog.trace("movement", "segment stalled near waypoint " + index);
            finish(false, "Pathing is stuck near route waypoint " + index + dominantCause());
            return;
        }

        // Think ahead before committing to an edit: near the frayed end of a PARTIAL route,
        // a MINE waypoint may be a dig toward a dead end the planner never saw past (caves).
        // The extension request above has already fired — hold position until the fresher,
        // deeper plan is swapped in rather than tunneling blind. A fixed 6-waypoint margin:
        // the old size/4 rule froze long routes a quarter of the way from their end.
        boolean nearRouteEnd = route.waypoints().size() - index <= 6;
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
        Vec3 feet = new Vec3(player.getX(), player.getY(), player.getZ());
        Vec3 anchor = aimPoint(feet, waypoint);
        Vec3 aim = navAim(player, anchor);
        renderAim(player, aim);
        // The gaze pursues the randomized mark, but the WALKING bearing must stay honest:
        // cap the mark's pull to a small deviation from the route bearing so a sideways
        // mark at close range can never steer the body off the line the rollouts verify.
        float routeYaw = yawTo(feet, anchor);
        float markPull = net.minecraft.util.Mth.wrapDegrees(
                yawTo(feet, aim) - routeYaw);
        float yaw = routeYaw + Math.max(-10.0F, Math.min(10.0F, markPull));
        Input selected = chooseInput(live, profile, waypoint, yaw);
        if (live.fluid() == MotionState.Fluid.WATER) {
            updateAirMode(player);
            player.setXRot(swimPitch(player, waypoint));
        }
        apply(player, selected, live, waypoint);
        // Predict AFTER the humanized view is applied: the trail starts from the yaw the
        // player really faces and keeps turning through the same view model each tick, so
        // orange shows where the body will truly be — friction, effects, momentum, AND the
        // natural eye movement included.
        renderPrediction(live, selected, profile, player.getYRot());
        status(ticks < recalibratingUntil ? "recalibrating (prediction drift)"
                : live.inFluid()
                ? live.fluid() == MotionState.Fluid.LAVA ? "swimming (lava)" : "swimming"
                : isBrake(selected) ? "braking (final approach)"
                : selected.sprint() && selected.jump() ? "sprint-jumping"
                : selected.jump() ? "jumping"
                : selected.sprint() ? "sprinting" : label(waypoint.via()));
        scheduleDelay();
    }

    /** Entity coordinates are feet/bottom-center, exactly the origin MotionState.box uses. */
    public static MotionState liveState(LocalPlayer player) {
        MotionState.Pose pose = player.isVisuallyCrawling() ? MotionState.Pose.CRAWL
                : (player.isUnderWater() || player.isSwimming()
                        || player.isVisuallySwimming())
                        ? MotionState.Pose.SWIM : MotionState.Pose.STAND;
        // Classify fluid from the same exact AABB used by simulation, rather than trusting
        // entity flags whose update may trail the current client-world collision state.
        MotionState.Fluid fluid = MotionState.Fluid.NONE;
        var box = MotionState.box(pose, new Vec3(player.getX(), player.getY(), player.getZ()));
        for (BlockPos cell : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7))) {
            var state = player.level().getFluidState(cell);
            if (state.is(FluidTags.LAVA)) { fluid = MotionState.Fluid.LAVA; break; }
            if (state.is(FluidTags.WATER)) fluid = MotionState.Fluid.WATER;
        }
        return new MotionState(new Vec3(player.getX(), player.getY(), player.getZ()), player.getDeltaMovement(), player.onGround(), pose,
                fluid, false);
    }

    /**
     * Desync watchdog: compare the ACTUAL position this tick against the 1-step prediction
     * captured last tick (the first rollout step of the applied input). The simulator is
     * normally millimeter-accurate, so real drift means the model and the world disagree —
     * server-side knockback, an unmodeled block, latency — and the honest response is to
     * stop trusting the current plan and recalibrate from live state. Cost per tick is one
     * Vec3d distance.
     */
    private void checkPredictionDrift(LocalPlayer player) {
        Vec3 expected = predictedNext;
        predictedNext = null; // re-armed only when a fresh rollout runs this tick
        if (expected == null || ticks < driftSuppressedUntil) return;
        Vec3 actual = new Vec3(player.getX(), player.getY(), player.getZ());
        double drift = actual.distanceTo(expected);
        driftScore = driftScore * DRIFT_DECAY + drift;
        driftHighTicks = driftScore > DRIFT_SCORE_HIGH ? driftHighTicks + 1 : 0;
        if (drift < DRIFT_SPIKE && driftHighTicks < DRIFT_HIGH_TICKS) return;
        if (ticks - lastDriftLogTick >= DRIFT_LOG_INTERVAL) {
            lastDriftLogTick = ticks;
            LOGGER.warn("Prediction drift {} blocks (decayed score {}): actual {} vs "
                    + "predicted {}; underfoot {}, pose {}, touching water {}, in lava {}",
                    String.format("%.3f", drift), String.format("%.2f", driftScore),
                    actual, expected,
                    client.level.getBlockState(player.blockPosition().below()),
                    player.getPose(), player.isInWater(), player.isInLava());
        }
        recalibratingUntil = ticks + 20;
        driftScore = 0.0;
        driftHighTicks = 0;
        driftSuppressedUntil = ticks + DRIFT_SUPPRESS_TICKS;
        forceRecalibrate();
    }

    /**
     * Recalibrate from live reality: forget stale progress bookkeeping (the drifted route
     * position poisoned bestRemaining) and fire the engine's EXISTING extension machinery
     * outside its normal cooldown — the fresh route is stitched/swap-anchored to wherever
     * the player really is, so no new pathing mechanism is invented here.
     */
    private void forceRecalibrate() {
        bestRemaining = Double.POSITIVE_INFINITY;
        lastProgressTick = ticks;
        if (replanRequest != null && !replanRequested) {
            replanRequested = true;
            lastExtensionTick = ticks;
            replanRequest.run();
        }
    }

    /** One byte of "what the scorer saw" per tick; the ring spans the whole stuck window. */
    private void recordCauses(boolean bumped, boolean misfit, boolean jumpsOffRoute,
            boolean brakeOvershoot) {
        causeRing[causeCursor] = (byte) ((bumped ? CAUSE_BUMP : 0) | (misfit ? CAUSE_FIT : 0)
                | (jumpsOffRoute ? CAUSE_JUMP : 0) | (brakeOvershoot ? CAUSE_BRAKE : 0));
        causeCursor = (causeCursor + 1) % causeRing.length;
    }

    /**
     * The scorer's dominant complaint over the recent window, in plain words — or "" when
     * nothing persistent stood out. A quarter of the window is the noise floor: a couple of
     * incidental bumps on a doorframe must not masquerade as the reason a route died.
     */
    private String dominantCause() {
        int bump = 0, jump = 0, fit = 0, brake = 0;
        for (byte bits : causeRing) {
            if ((bits & CAUSE_BUMP) != 0) bump++;
            if ((bits & CAUSE_JUMP) != 0) jump++;
            if ((bits & CAUSE_FIT) != 0) fit++;
            if ((bits & CAUSE_BRAKE) != 0) brake++;
        }
        int top = Math.max(Math.max(bump, jump), Math.max(fit, brake));
        if (top < STUCK_TICKS / 4) return "";
        String cause = top == bump ? "path blocked (persistent horizontal collision)"
                : top == jump ? "every jump candidate lands off-route"
                : top == fit ? "the hitbox does not fit along the predicted line"
                : "braking keeps overshooting the target";
        return " - likely cause: " + cause;
    }

    /** Enumerate compact controls, simulate each from reality, and keep the safest progress. */
    private Input chooseInput(MotionState live, MovementProfile profile,
            PlannedRoute.Waypoint waypoint, float yaw) {
        // Fluid movement deliberately has one committed control mode. Mixing land walk,
        // jump-release, brake, and strafe candidates made the selected keys thrash each tick.
        // Vertical position in water is owned entirely by the pitch controller (swimPitch);
        // jump is reserved for climbing out onto a bank at the end of the swim.
        if (live.fluid() == MotionState.Fluid.WATER) {
            LocalPlayer player = client.player;
            // Climb out whenever the waypoint is a bank ABOVE us (even one block), not only
            // when it sits above the measured surface — the strict check left the follower
            // swimming against a 1-high ledge forever.
            boolean exitClimb = player != null
                    && (waypoint.position().y >= waterSurfaceY(player) - 0.25
                            || waypoint.position().y > player.getY() + 0.3)
                    && horizontalDistance(live.position(), waypoint.position()) < 3.5;
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
        Vec3 from = index > 0 ? route.waypoints().get(index - 1).position() : live.position();
        Vec3 finalTarget = route.waypoints().getLast().position();
        Vec3 finalFrom = route.waypoints().size() > 1
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
                PlayerMotion.slipperinessBelow(client.level, live.position()) * 0.91);
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
        // open ground, so during plain travel the follower offers a HELD-jump variant and
        // lets the rollout prove it is safe and faster here. Because the jump key stays
        // held in the simulation, the rollout literally plays out spam-jumping — including
        // under low ceilings, where the head-bonk shortens the arc and the ticks tell the
        // truth about whether hopping gains anything. The physics decide, not a heuristic.
        if ((primitive == Primitive.SPRINT || primitive == Primitive.WALK)
                && live.onGround() && !brakeNeeded && !approachingFinal) {
            candidates.add(new Input(1, 0, true, primitive == Primitive.SPRINT, false, yaw));
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
        if (hardBrake) {
            recordCauses(false, false, false, true);
            return brake;
        }

        Input best = candidates.getFirst();
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean bestBumped = false;
        boolean bestMisfit = false;
        int jumpCandidates = 0;
        int jumpDisqualified = 0;
        for (Input candidate : candidates) {
            if (!TalosClient.tickBudget().hasBudgetRemaining() && bestScore > Double.NEGATIVE_INFINITY) {
                break;
            }
            MotionState state = live;
            double score = 0.0;
            boolean rolloutBumped = false;
            boolean rolloutMisfit = false;
            double oldDistance = horizontalDistance(state.position(), waypoint.position());
            // The rollout turns its head like the real player will: yaw evolves through the
            // eased view model each tick instead of snapping to the candidate heading.
            double simYaw = client.player.getYRot();
            double simVel = yawVelocity;
            double accel = turnAccel();
            // Ice's consequences play out over dozens of ticks; a 6-tick window scored the
            // slide as fine because the overshoot hadn't HAPPENED yet inside the window.
            int horizon = drag > 0.62 ? ROLLOUT_TICKS * 3 : ROLLOUT_TICKS;
            for (int tick = 0; tick < horizon; tick++) {
                double[] view = steerStep(simYaw, simVel, candidate.yaw(), accel, jitterAt(tick));
                simYaw = view[0];
                simVel = view[1];
                state = PlayerMotion.step(client.level, state, withYaw(candidate, (float) simYaw), profile);
                double distance = horizontalDistance(state.position(), waypoint.position());
                score += (oldDistance - distance) * 12.0; // primary route progress
                score -= distanceToSegment(state.position(), from, waypoint.position()) * 1.8;
                if (state.bumpedHorizontally()) { score -= 7.0; rolloutBumped = true; }
                if (state.fluid() == MotionState.Fluid.LAVA) score -= 100.0;
                if (isHazard(BlockPos.containing(state.position()))) score -= 60.0;
                if (!PlayerMotion.hitboxFits(client.level, state.pose(), state.position())) {
                    score -= 1_000.0;
                    rolloutMisfit = true;
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
                jumpCandidates++;
                if (landing == null) {
                    score -= 400.0;
                    jumpDisqualified++;
                } else {
                    boolean landsInHole =
                            landing.position().y < Math.min(from.y, waypoint.position().y) - 2.5;
                    if (landsInHole) score -= 300.0;
                    double offRoute =
                            distanceToSegment(landing.position(), from, waypoint.position());
                    score -= offRoute * 6.0;
                    if (landsInHole || offRoute > 1.5) jumpDisqualified++;
                }
            }
            if (landing != null && approachingFinal) {
                score -= overshoot(landing.position(), finalFrom, finalTarget) * 220.0;
            }
            // Eagerness bias: a sprint-jump whose landing proved safe wins ties (and
            // near-ties) against a plain run. The hop is marginally faster and reads like
            // a player who WANTS to be moving; every safety veto above still applies.
            if (candidate.jump() && candidate.sprint() && !brakeNeeded && !approachingFinal
                    && !rolloutMisfit && !(live.onGround() && landing == null)) {
                score += 5.0;
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
            if (score > bestScore + margin) {
                bestScore = score;
                best = candidate;
                bestBumped = rolloutBumped;
                bestMisfit = rolloutMisfit;
            }
        }
        recordCauses(bestBumped, bestMisfit,
                jumpCandidates > 0 && jumpDisqualified == jumpCandidates, false);
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
        Vec3 to = waypoint.position().subtract(live.position());
        double length = Math.hypot(to.x, to.z);
        Vec3 velocity = live.velocity();
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
        float correction = net.minecraft.util.Mth.wrapDegrees(compensated - base);
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
            predicted = PlayerMotion.step(client.level, predicted,
                    withYaw(selected, (float) simYaw), profile);
            // The first rollout step from the APPLIED input is exactly where the player
            // should stand next tick; the desync watchdog compares it against reality.
            if (i == 0) predictedNext = predicted.position();
            RenderQueue.add("talos-sim-tick:" + i,
                    predicted.box(predicted.position()), PREDICTION_COLOR, PREDICTION_TTL);
        }
    }

    private static boolean isBrake(Input input) {
        return !input.jump() && !input.sprint() && input.sneak();
    }

    private boolean isHazard(BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }

    private boolean mine(LocalPlayer player, Vec3 target) {
        BlockPos origin = player.blockPosition();
        BlockPos destination = BlockPos.containing(target);
        int dx = Integer.compare(destination.getX(), origin.getX());
        int dz = Integer.compare(destination.getZ(), origin.getZ());
        BlockPos block = null;
        if (dx == 0 && dz == 0 && destination.getY() < origin.getY()) {
            // Vertical shaft: dig the block directly underfoot.
            if (blocking(origin.below())) block = origin.below();
        }
        // MINE waypoints describe the post-edit cell, so inspect the intervening tunnel.
        for (int step = 1; step <= 3 && block == null && (dx != 0 || dz != 0); step++) {
            BlockPos feet = origin.offset(dx * step, 0, dz * step);
            if (blocking(feet)) block = feet;
            else if (blocking(feet.above())) block = feet.above();
            if (feet.getX() == destination.getX() && feet.getZ() == destination.getZ()) break;
        }
        if (block == null) {
            // The tunnel scan assumes a standing approach. From a swim (or any odd angle),
            // fall back to the first blocking cell along the eye-to-waypoint line — this is
            // what un-sticks "swimming" against a wall that a MINE waypoint says to dig.
            Vec3 eye = player.getEyePosition();
            Vec3 toTarget = target.add(0.0, 0.5, 0.0).subtract(eye);
            double length = toTarget.length();
            if (length > 1.0E-3 && length <= 6.0) {
                Vec3 direction = toTarget.scale(1.0 / length);
                for (double d = 0.5; d <= Math.min(length, 4.5); d += 0.25) {
                    BlockPos cell = BlockPos.containing(eye.add(direction.scale(d)));
                    if (blocking(cell)) { block = cell; break; }
                }
            }
        }
        if (block == null) { breaking = null; return false; }
        status("mining");
        // Same aim language as the action cube-aim: yellow cube = the block being worked,
        // red X = the face point under attack.
        RenderQueue.add("talos-aim-cube", new AABB(block).inflate(0.002),
                AIM_TARGET_COLOR, PREDICTION_TTL * 3);
        Vec3 face = Vec3.atCenterOf(block).add(0.0, 0.5, 0.0);
        RenderQueue.add("talos-aim-mark",
                new AABB(face.x - 0.07, face.y - 0.07, face.z - 0.07,
                        face.x + 0.07, face.y + 0.07, face.z + 0.07),
                AIM_GAZE_COLOR, PREDICTION_TTL * 3);
        releaseInputs();
        // Mining while afloat: hold jump so buoyancy doesn't sink the player away from a
        // dig line at or above them while the aim converges and the block breaks.
        if (player.isInWater() && target.y >= player.getY() - 0.5) {
            client.options.keyJump.setDown(true);
        }
        // Face the block with the eased look before a single swing: converge this tick,
        // dig on the tick the crosshair actually rests on it.
        if (!steerLook(player, Vec3.atCenterOf(block), 12.0)) return true;
        BlockState state = client.level.getBlockState(block);
        int best = player.getInventory().getSelectedSlot();
        float speed = player.getInventory().getItem(best).getDestroySpeed(state);
        for (int slot = 0; slot < 9; slot++) {
            float candidate = player.getInventory().getItem(slot).getDestroySpeed(state);
            if (candidate > speed) { best = slot; speed = candidate; }
        }
        player.getInventory().setSelectedSlot(best);
        if (!block.equals(breaking)) {
            client.gameMode.startDestroyBlock(block, Direction.UP);
            breaking = block.immutable();
        } else {
            client.gameMode.continueDestroyBlock(block, Direction.UP);
        }
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * PLACE waypoints are pillar cells only — horizontal bridging was removed from the
     * planner (unreliable in execution; scripts can still place blocks explicitly). Any
     * non-vertical PLACE waypoint is stale and is walked like an ordinary node.
     */
    private boolean place(LocalPlayer player, Vec3 target) {
        BlockPos destination = BlockPos.containing(target);
        BlockPos feet = player.blockPosition();
        if (destination.getX() == feet.getX() && destination.getZ() == feet.getZ()
                && destination.getY() > feet.getY()) {
            return pillar(player);
        }
        return false;
    }

    /** Nerdpole: hold jump and place under the feet once they clear the origin cell. */
    private boolean pillar(LocalPlayer player) {
        status("pillaring up");
        int slot = findBlockSlot(player);
        if (slot < 0) { finish(false, "Ran out of pillar blocks"); return true; }
        releaseInputs();
        client.options.keyJump.setDown(true);
        if (pillarOrigin == null || player.onGround()) {
            pillarOrigin = player.blockPosition().immutable();
        }
        BlockPos anchor = pillarOrigin.below();
        Vec3 hit = Vec3.atCenterOf(anchor).add(0.0, 0.5, 0.0);
        // Nerdpole gaze: ease the view down onto the anchor while airborne, like a player.
        boolean aimed = steerLook(player, hit, 22.0);
        player.getInventory().setSelectedSlot(slot);
        // Jump input applies after this tick; place only once the feet clear the target cell,
        // since same-tick jump+place is rejected by the placement collision check.
        if (aimed && player.getY() >= pillarOrigin.getY() + 0.42) {
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, anchor, false));
            player.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private void advance(LocalPlayer player) {
        while (index < route.waypoints().size()) {
            Vec3 target = route.waypoints().get(index).position();
            Vec3 previous = route.waypoints().get(index - 1).position();
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
    private void orbitBreaker(LocalPlayer player) {
        if (index >= route.waypoints().size() - 1) return; // never skip the final goal
        Vec3 target = route.waypoints().get(index).position();
        Vec3 feet = new Vec3(player.getX(), player.getY(), player.getZ());
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

    private void updateProgress(Vec3 position, Vec3 target) {
        double remaining = position.distanceToSqr(target);
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
    private Vec3 aimPoint(Vec3 feet, PlannedRoute.Waypoint waypoint) {
        Vec3 target = waypoint.position();
        if (index + 1 >= route.waypoints().size()) return target;
        double distance = horizontalDistance(feet, target);
        if (distance >= 1.6) return target;
        double blend = Math.min(0.6, (1.6 - distance) / 1.6);
        return target.lerp(route.waypoints().get(index + 1).position(), blend);
    }

    /**
     * Navigation gaze target, cube-aim language (same as the action aim): around the
     * blended look-ahead anchor a FULL-BLOCK yellow cube is placed, centered on a random
     * center-biased point of the anchor's standing hitbox; the red mark is a random spot on
     * one of the cube's player-visible faces, weighted by visible surface area. The session
     * holds while the anchor drifts a little and resamples once it strays — so the gaze
     * hops between plausible rest points instead of tracking a mathematical line.
     */
    private Vec3 navAim(LocalPlayer player, Vec3 anchor) {
        if (navAnchor == null || anchor.distanceToSqr(navAnchor) > 0.6 * 0.6
                || navMark == null) {
            navAnchor = anchor;
            // Triangular [-1,1] offsets: sloppy but center-biased, like the action cube.
            double u = steerRng.nextDouble() + steerRng.nextDouble() - 1.0;
            double v = steerRng.nextDouble() + steerRng.nextDouble() - 1.0;
            double w = steerRng.nextDouble() + steerRng.nextDouble() - 1.0;
            // Anchor is a FEET position; the stand hitbox is 0.6 wide and 1.8 tall.
            navCubeCenter = anchor.add(u * 0.3, 0.9 + v * 0.45, w * 0.3);
            navMark = dev.talos.client.action.AimController.markOn(
                    navCubeCenter, player.getEyePosition(), steerRng);
        }
        return navMark;
    }

    /**
     * Always-visible aim readout while navigating: yellow full-block cube = the aim cube on
     * the look-ahead target, red mark = the random face spot steering wants to face, red
     * dot = where the crosshair points right now at that same depth. The red dot easing
     * onto the red mark IS the humanized look, made visible.
     */
    private void renderAim(LocalPlayer player, Vec3 mark) {
        if (navCubeCenter == null) return;
        RenderQueue.add("talos-goto-aim",
                new AABB(navCubeCenter.x - 0.5, navCubeCenter.y - 0.5, navCubeCenter.z - 0.5,
                        navCubeCenter.x + 0.5, navCubeCenter.y + 0.5, navCubeCenter.z + 0.5),
                AIM_TARGET_COLOR, PREDICTION_TTL);
        RenderQueue.add("talos-goto-mark",
                new AABB(mark.x - 0.07, mark.y - 0.07, mark.z - 0.07,
                        mark.x + 0.07, mark.y + 0.07, mark.z + 0.07),
                AIM_GAZE_COLOR, PREDICTION_TTL);
        Vec3 eye = player.getEyePosition();
        Vec3 gaze = eye.add(player.getForward()
                .scale(Math.max(eye.distanceTo(mark), 1.0)));
        RenderQueue.add("talos-goto-gaze",
                new AABB(gaze.x - 0.05, gaze.y - 0.05, gaze.z - 0.05,
                        gaze.x + 0.05, gaze.y + 0.05, gaze.z + 0.05),
                AIM_GAZE_COLOR, PREDICTION_TTL);
    }

    private void apply(LocalPlayer player, Input input, MotionState live,
            PlannedRoute.Waypoint waypoint) {
        // Set the complete desired state directly. Releasing first created a real per-tick
        // false/true pulse that broke continuous sprint-jump holds and bunny-hop momentum.
        client.options.keyUp.setDown(input.forward() > .1F);
        client.options.keyDown.setDown(input.forward() < -.1F);
        client.options.keyLeft.setDown(input.strafe() > .1F);
        client.options.keyRight.setDown(input.strafe() < -.1F);
        client.options.keyJump.setDown(input.jump());
        client.options.keySprint.setDown(input.sprint());
        client.options.keyShift.setDown(input.sneak());
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
    private boolean steerLook(LocalPlayer player, Vec3 point, double toleranceDegrees) {
        float[] desired = RotationHumanizer.yawPitchTo(player.getEyePosition(), point);
        steerYaw(player, desired[0]);
        double pitchStep = (desired[1] - player.getXRot()) * 0.55;
        pitchStep = Math.max(-14.0, Math.min(14.0, pitchStep));
        player.setXRot(net.minecraft.util.Mth.clamp(
                (float) (player.getXRot() + pitchStep + steerRng.nextGaussian() * 0.15),
                -90.0F, 90.0F));
        double yawError = Math.abs(net.minecraft.util.Mth.wrapDegrees(
                desired[0] - player.getYRot()));
        double pitchError = Math.abs(desired[1] - player.getXRot());
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
        double delta = net.minecraft.util.Mth.wrapDegrees(targetYaw - yaw);
        double cap = Math.abs(delta) > 60.0 ? Math.max(maxTurnSpeed * 2.5, 28.0) : maxTurnSpeed;
        double stopCap = Math.sqrt(2.0 * accel * Math.abs(delta));
        double limit = Math.min(cap, stopCap);
        double desired = Math.max(-limit, Math.min(limit, delta * 0.45));
        velocity += Math.max(-accel, Math.min(accel, desired - velocity));
        double applied = Math.abs(delta) > 1.0 ? jitter : 0.0;
        return new double[]{yaw + velocity + applied, velocity};
    }

    private void steerYaw(LocalPlayer player, float targetYaw) {
        double accel = turnAccel();
        double delta = net.minecraft.util.Mth.wrapDegrees(targetYaw - player.getYRot());
        double[] next = steerStep(player.getYRot(), yawVelocity, targetYaw, accel, jitterAt(0));
        yawVelocity = next[1];
        float yaw = (float) next[0];
        if (Math.abs(delta) < 1.2 && Math.abs(yawVelocity) < 1.2) {
            yaw = (float) (player.getYRot() + delta * 0.6); // settle without a visible snap
            yawVelocity *= 0.5;
        }
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
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
    private void steerPitch(LocalPlayer player, PlannedRoute.Waypoint waypoint) {
        if (--pitchWanderTicks <= 0) {
            pitchWanderTicks = 25 + steerRng.nextInt(35);
            pitchWander = steerRng.nextGaussian() * 3.5;
        }
        Vec3 eye = player.getEyePosition();
        // Gaze rests on the red mark of the navigation aim cube, so yaw and pitch converge
        // on the SAME randomized point; the waypoint head is only the sessionless fallback.
        Vec3 target = navMark != null ? navMark : waypoint.position().add(0.0, 1.62, 0.0);
        double horizontal = Math.max(horizontalDistance(eye, target), 3.0);
        double toward = Math.toDegrees(Math.atan2(eye.y - target.y, horizontal));
        double desired = Math.max(-30.0, Math.min(35.0, toward + pitchWander));
        double step = Math.max(-2.5, Math.min(2.5, desired - player.getXRot()));
        player.setXRot((float) (player.getXRot() + step
                + steerRng.nextGaussian() * 0.12));
    }

    private void updateAirMode(LocalPlayer player) {
        int maxAir = player.getMaxAirSupply();
        if (player.getAirSupply() <= Math.max(20, maxAir / 5)) surfacingForAir = true;
        else if (player.getAirSupply() >= maxAir * 3 / 4) surfacingForAir = false;
    }

    /**
     * Swimming is held AT the water/air interface: a proportional pitch controller keeps the
     * compact hitbox straddling the surface (in water, head in air) so it breathes while
     * sprint-swimming. Only a genuinely deep waypoint — with air to spare — dives off it.
     */
    private float swimPitch(LocalPlayer player, PlannedRoute.Waypoint waypoint) {
        double surface = waterSurfaceY(player);
        Vec3 target = waypoint.position();
        Vec3 feet = new Vec3(player.getX(), player.getY(), player.getZ());
        // Exiting onto a bank: look UP at it. The surface-hold controller otherwise pins the
        // gaze down at the waterline, which fights the climb-out.
        if (target.y > feet.y + 0.3 && horizontalDistance(feet, target) < 3.5) {
            return -25.0F;
        }
        if (!surfacingForAir && target.y < surface - 2.0) {
            double horizontal = Math.max(horizontalDistance(feet, target), 0.001);
            return (float) Math.max(-60.0, Math.min(60.0,
                    Math.toDegrees(Math.atan2(feet.y - target.y, horizontal))));
        }
        double error = feet.y - (surface - SURFACE_FEET_DEPTH);
        return (float) Math.max(-45.0, Math.min(45.0, error * 120.0));
    }

    /** Y of the top of the water column at the player's position. */
    private double waterSurfaceY(LocalPlayer player) {
        int x = player.getBlockX(), z = player.getBlockZ();
        int y = player.getBlockY();
        int limit = y + 64;
        while (y < limit
                && client.level.getFluidState(new BlockPos(x, y + 1, z)).is(FluidTags.WATER)) {
            y++;
        }
        BlockPos top = new BlockPos(x, y, z);
        var fluid = client.level.getFluidState(top);
        double height = fluid.is(FluidTags.WATER) ? fluid.getHeight(client.level, top) : 0.9;
        return y + height;
    }

    private boolean blocking(BlockPos pos) {
        return !client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty();
    }

    private static int findBlockSlot(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).getItem() instanceof BlockItem) return slot;
        }
        return -1;
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double length = dx * dx + dz * dz;
        if (length < 1.0E-8) return horizontalDistance(point, b);
        double t = Math.max(0.0, Math.min(1.0,
                ((point.x - a.x) * dx + (point.z - a.z) * dz) / length));
        return Math.hypot(point.x - (a.x + dx * t), point.z - (a.z + dz * t));
    }

    /** Horizontal distance beyond {@code target}, measured along this route edge. */
    private static double overshoot(Vec3 point, Vec3 from, Vec3 target) {
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
        double simYaw = client.player == null ? input.yaw() : client.player.getYRot();
        double simVel = yawVelocity;
        double accel = turnAccel();
        for (int tick = 0; tick < LANDING_ROLLOUT_TICKS; tick++) {
            double[] view = steerStep(simYaw, simVel, input.yaw(), accel, jitterAt(tick));
            simYaw = view[0];
            simVel = view[1];
            state = PlayerMotion.step(client.level, state, withYaw(input, (float) simYaw), profile);
            if (!PlayerMotion.hitboxFits(client.level, state.pose(), state.position())) return null;
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
            case PLACE -> "pillaring";
        };
    }

    private void status(String mode) {
        if (mode.equals(lastStatus)) return;
        lastStatus = mode;
        if (client.player != null) client.player.sendOverlayMessage(
                Component.literal("§bTalos §7» §f" + mode));
    }

    public void cancel() { finish(false, "Pathing cancelled"); _break(); }

    private void finish(boolean success, String detail) {
        TalosLog.trace("movement", (success ? "segment success: " : "segment fail: ") + detail);
        RouteRenderer.clear();
        releaseInputs();
        if (client.player != null) client.player.sendOverlayMessage(Component.literal(
                (success ? "§aTalos §7» §f" : "§cTalos §7» §f") + detail));
        future.complete(new PathResult(success, detail));
    }

    private void releaseInputs() {
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyShift.setDown(false);
    }

    @Override public void onCompleted() {
        if (activeFollower == this) activeFollower = null;
        releaseInputs();
        if (!future.isDone()) future.complete(new PathResult(false, "Navigation was interrupted"));
    }

    @Override public Set<Object> getMutexKeys() { return Set.of("talos-player-movement"); }
}
