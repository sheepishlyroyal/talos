package dev.talos.client.pathing.talos;

import dev.talos.client.TalosClient;
import dev.talos.client.action.AimController;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.task.TalosTask;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** One cooperative tick loop for waypoint movement, immediate looking, and route actions. */
public final class NavigateAndActTask extends TalosTask {
    public interface RouteAction {
        /** Current world-space target, or null when there is no target to act on. */
        @Nullable Vec3 target();
        double reach();
        /** Performs at most one tick of work; true means the action is complete. */
        boolean perform(Minecraft client);
    }

    private static final double NODE_DISTANCE_SQUARED = 0.36;
    private static final long MIN_SPAM_JUMP_INTERVAL_NANOS = 1_000_000_000L / 11L;
    private static final long MAX_SPAM_JUMP_INTERVAL_NANOS = 1_000_000_000L / 7L;
    private static final int PLAN_LOOKAHEAD_NODES = 6;
    private static final int BRAKING_NODES = 3;
    private static final int PRECISE_GOAL_EDGES = 2;
    private static final double SHARP_TURN_COSINE = -0.4226182617; // cos(115 degrees)
    private static final double WALL_CLEARANCE = 0.50;
    private static final double OPEN_SIDE_LIMIT = 0.70;
    private static final int MIN_MODE_DWELL_TICKS = 8;
    private enum PlannedMode { SPRINT_JUMP, WALK, SPRINT, SPAM_JUMP, SWIM, CRAWL }
    private final Minecraft client;
    private final List<BlockPos> nodes;
    private final List<Vec3> steeringNodes;
    private final Predicate<BlockPos> goal;
    private final CompletableFuture<PathResult> future;
    private final @Nullable RouteAction action;
    private final boolean allowMining;
    private final AimController aim;
    private int index;
    private int ticks;
    private BlockPos breaking;
    private BlockPos pillarOrigin;
    private BlockPos lastBridgeTarget;
    private int lastBridgePlaceTick = Integer.MIN_VALUE;
    private final Random jumpRandom = new Random();
    private long nextSpamJumpNanos;
    private boolean spamJumpWasGrounded;
    private BlockPos sprintJumpLanding;
    private boolean sprintJumpWasAirborne;
    private boolean sprintJumpUsesSprint = true;
    private PlannedMode committedMode = PlannedMode.SPRINT_JUMP;
    private int committedModeSinceTick;
    private String lastStatus = "";

    /** Unobtrusive action-bar readout so the current movement mode is visible in-game. */
    private void status(String mode) {
        if (mode.equals(lastStatus)) return;
        lastStatus = mode;
        if (client.player != null) {
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§bTalos §7» §f" + mode));
        }
    }

    public NavigateAndActTask(Minecraft client, List<BlockPos> nodes,
                              Predicate<BlockPos> goal, @Nullable RouteAction action,
                              CompletableFuture<PathResult> future) {
        this(client, nodes, goal, action, future, true);
    }

    public NavigateAndActTask(Minecraft client, List<BlockPos> nodes,
                              Predicate<BlockPos> goal, @Nullable RouteAction action,
                              CompletableFuture<PathResult> future, boolean allowMining) {
        this(client, nodes, buildSteeringNodes(client, nodes), goal, action, future, allowMining);
    }

    public NavigateAndActTask(Minecraft client, List<BlockPos> nodes,
                              List<Vec3> steeringNodes, Predicate<BlockPos> goal,
                              @Nullable RouteAction action, CompletableFuture<PathResult> future,
                              boolean allowMining) {
        if (nodes.size() != steeringNodes.size()) {
            throw new IllegalArgumentException("Block and steering node counts must match");
        }
        this.client = client;
        this.nodes = List.copyOf(nodes);
        this.steeringNodes = List.copyOf(steeringNodes);
        this.goal = goal;
        this.action = action;
        this.allowMining = allowMining;
        this.future = future;
        this.index = nodes.size() > 1 ? 1 : nodes.size();
        this.aim = new AimController(client, TalosClient.humanizer().rotation(),
                TalosClient.humanizer().defaultProfile(), System.nanoTime());
    }

    @Override public void initialize() { }
    @Override public boolean condition() { return !future.isDone(); }
    @Override public void increment() { ticks++; }

    @Override public void body() {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) { finish(false, "World unloaded while navigating"); return; }
        advancePastReachedOrPassed(player);
        if (sprintJumpLanding != null) {
            sprintJumpWasAirborne |= !player.onGround();
            if (!(sprintJumpWasAirborne && player.onGround())) {
                // An overshot landing must never remain the aim lock. While airborne,
                // move the lock forward with the path as soon as its old node is passed.
                if (index < nodes.size() && !nodes.get(index).equals(sprintJumpLanding)) {
                    sprintJumpLanding = nodes.get(index).immutable();
                }
                continueSprintJump(player);
                scheduleDelay();
                return;
            }
            sprintJumpLanding = null;
            sprintJumpWasAirborne = false;
        }
        if (goal.test(player.blockPosition())) { finish(true, "Arrived"); return; }
        // Landing may have carried us over several short nodes. Discard all of them
        // before selecting a new steering target, rather than aiming back at one.
        advancePastReachedOrPassed(player);
        if (index >= nodes.size()) { finish(false, "Nodes ended before the goal was reached"); return; }

        BlockPos node = nodes.get(index);
        if (handleTraversal(player, node)) { scheduleDelay(); return; }
        updateCommittedMode();
        Vec3 actionTarget = action == null ? null : action.target();
        if (actionTarget != null && player.getEyePosition().distanceToSqr(actionTarget)
                <= action.reach() * action.reach()) {
            releaseInputs();
            aim.aimAt(actionTarget);
            aim.tick();
            if (aim.isAimed() && TalosClient.tickBudget().hasBudgetRemaining()) action.perform(client);
        } else {
            Vec3 followTarget = followTarget(player, node);
            // Recompute steering without flickering sprint/jump off for part of every
            // client tick. Normal travel keeps both inputs continuously held.
            releaseDirectionalInputs();
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(true);
            int horizontalDistance = Math.max(Math.abs(node.getX() - player.getBlockX()),
                    Math.abs(node.getZ() - player.getBlockZ()));
            boolean jumpEdge = horizontalDistance > 1;
            boolean stairOrSlab = isStairOrSlab(node.below()) || isStairOrSlab(player.blockPosition().below());
            boolean swimEdge = isFluid(node) || player.isSwimming() || player.isUnderWater()
                    || player.isInWater() || player.isInLava();
            boolean inCobweb = isCobweb(player.blockPosition()) || isCobweb(node);
            boolean stickyJumpSurface = isStickyJumpSurface(player.blockPosition().below())
                    || isStickyJumpSurface(node.below());
            boolean headHitStep = isLowCeilingAscent(player.blockPosition(), node);
            boolean climbingOut = isConfinedVerticalEscape(player, node);
            if (swimEdge) {
                // Swimming is driven by view pitch. Jump causes repeated surface
                // breaches and sinking, so it is deliberately never used here.
                status(isLava(node) || player.isInLava() ? "swimming (lava)" : "swimming");
                client.options.keyJump.setDown(false);
                steerSwimming(player, followTarget, node);
            } else if (climbingOut) {
                // A boxed ascent is impossible without jumping. This deliberately
                // precedes WALK and crawl/slow-terrain handling so precision mode can
                // never pin the player against the wall of a one-wide pit.
                status("climbing out");
                client.options.keySprint.setDown(false);
                client.options.keyJump.setDown(false);
                steerToward(player, followTarget, false);
                pressSpamJumpIfReady(player);
            } else if (inCobweb) {
                // Sprinting and jumping only waste inputs against cobweb collision.
                status("slow terrain (cobweb)");
                client.options.keySprint.setDown(false);
                client.options.keyJump.setDown(false);
                steerToward(player, followTarget, false);
            } else if (stickyJumpSurface) {
                // Honey suppresses jump velocity and slime turns repeated jumps into
                // unwanted bounces. Push through without fighting either surface.
                status("slow terrain (sticky)");
                client.options.keySprint.setDown(false);
                client.options.keyJump.setDown(false);
                steerToward(player, followTarget, false);
            } else if (isCrawlNode(node)) {
                status("crawling");
                client.options.keyShift.setDown(true);
                client.options.keyJump.setDown(false);
                steerToward(player, followTarget, false);
            } else if (headHitStep) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("spam-jump (head-hit)");
                client.options.keyJump.setDown(false);
                pressSpamJumpIfReady(player);
            } else if (stairOrSlab) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("spam-jump (stairs)");
                // Stair/slab travel deliberately pulses space instead of holding it.
                client.options.keyJump.setDown(false);
                pressSpamJumpIfReady(player);
            } else if (jumpEdge) {
                steerToward(player, followTarget, isSlippery(player, node));
                // A gap edge is one indivisible movement: never let waypoint advancement
                // turn the player back toward a passed node during the airborne arc.
                sprintJumpUsesSprint = committedMode != PlannedMode.WALK || horizontalDistance > 2;
                status(sprintJumpUsesSprint ? "sprint-jump" : "precise jump");
                sprintJumpLanding = node.immutable();
                sprintJumpWasAirborne = !player.onGround();
                client.options.keySprint.setDown(sprintJumpUsesSprint);
                client.options.keyJump.setDown(true);
            } else if (committedMode == PlannedMode.SPAM_JUMP) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("spam-jump");
                client.options.keyJump.setDown(false);
                pressSpamJumpIfReady(player);
            } else if (committedMode == PlannedMode.WALK) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("walk/precise");
                client.options.keySprint.setDown(false);
                client.options.keyJump.setDown(false);
            } else if (committedMode == PlannedMode.SPRINT) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("sprint");
                client.options.keyJump.setDown(false);
            } else {
                steerToward(player, followTarget, isSlippery(player, node));
                // Continuous bunny-hopping is only appropriate in a fully open
                // corridor. A block in either jump headspace uses prompt pulses.
                status("sprint-jump");
                client.options.keyJump.setDown(true);
            }
        }
        scheduleDelay();
    }

    /**
     * Builds a small segment plan ahead of the current waypoint.  Safety modes may
     * begin before their first node (the braking window); open running begins only
     * when the whole visible prefix is consistently open.
     */
    private void updateCommittedMode() {
        PlannedMode desired = classifyNode(index);
        int end = Math.min(nodes.size(), index + PLAN_LOOKAHEAD_NODES);
        if (desired == PlannedMode.SPRINT_JUMP) {
            for (int i = index + 1; i < end; i++) {
                PlannedMode ahead = classifyNode(i);
                if (ahead != PlannedMode.SPRINT_JUMP) {
                    if (i - index <= BRAKING_NODES) desired = brakingMode(ahead);
                    break;
                }
            }
        }

        if (desired == committedMode) return;
        boolean safetyTransition = committedMode == PlannedMode.SPRINT_JUMP
                && desired != PlannedMode.SPRINT_JUMP;
        boolean dwellComplete = ticks - committedModeSinceTick >= MIN_MODE_DWELL_TICKS;
        // A safety boundary must brake immediately. Other boundaries wait out the
        // dwell, preventing one-tick open/precise flicker from starting a new run.
        if (safetyTransition || dwellComplete) {
            committedMode = desired;
            committedModeSinceTick = ticks;
            if (committedMode != PlannedMode.SPRINT_JUMP) {
                client.options.keyJump.setDown(false);
            }
        }
    }

    private PlannedMode brakingMode(PlannedMode upcoming) {
        return switch (upcoming) {
            // Brake a precision boundary without expanding WALK backward onto safe
            // approach nodes; WALK starts only on the qualifying segment itself.
            case WALK -> PlannedMode.SPRINT;
            case SWIM, CRAWL, SPAM_JUMP -> PlannedMode.WALK;
            case SPRINT -> PlannedMode.SPRINT;
            case SPRINT_JUMP -> PlannedMode.SPRINT_JUMP;
        };
    }

    /** Classifies the segment beginning at one path node using path and terrain shape. */
    private PlannedMode classifyNode(int at) {
        BlockPos pos = nodes.get(at);
        BlockPos from = at > 0 ? nodes.get(at - 1) : pos;
        if (isFluid(pos)) return PlannedMode.SWIM;
        if (isCrawlNode(pos)) return PlannedMode.CRAWL;
        if (isLowCeilingAscent(from, pos) || isStairOrSlab(pos.below())
                || isStairOrSlab(from.below())) return PlannedMode.SPAM_JUMP;
        if (!hasOpenJumpHeadroom(pos)) return PlannedMode.SPAM_JUMP;
        if (isIce(pos.below())) return PlannedMode.SPRINT;

        // Precision is intentionally rare: a true two-flank drop, a reversal-like
        // turn, or the last two edges when the actual goal cell is constrained.
        int remainingEdges = nodes.size() - 1 - at;
        BlockPos goalCell = nodes.getLast();
        boolean constrainedGoalApproach = remainingEdges <= PRECISE_GOAL_EDGES
                && (isTrueOneWideLanding(goalCell, nodes.size() - 1)
                || isGoalNarrow(goalCell) || isGoalEdgeBounded(goalCell));
        if (isTrueOneWideLanding(pos, at) || isSharpTurn(at) || constrainedGoalApproach) {
            return PlannedMode.WALK;
        }
        // A wide-open goal needs speed control, not precision mode. Stop hopping on
        // its final edge while retaining sprint speed.
        if (remainingEdges <= 1) return PlannedMode.SPRINT;
        return PlannedMode.SPRINT_JUMP;
    }

    private boolean isSharpTurn(int at) {
        if (at <= 0 || at + 1 >= nodes.size()) return false;
        BlockPos before = nodes.get(at - 1), here = nodes.get(at), after = nodes.get(at + 1);
        double inX = here.getX() - before.getX(), inZ = here.getZ() - before.getZ();
        double outX = after.getX() - here.getX(), outZ = after.getZ() - here.getZ();
        double lengths = Math.hypot(inX, inZ) * Math.hypot(outX, outZ);
        return lengths > 0.0 && (inX * outX + inZ * outZ) / lengths < SHARP_TURN_COSINE;
    }

    /** True only when both flanks of the route are unsupported fall drops. */
    private boolean isTrueOneWideLanding(BlockPos pos, int at) {
        if (nodes.size() < 2) return false;
        BlockPos other = at > 0 ? nodes.get(at - 1) : nodes.get(1);
        int dx = Integer.compare(pos.getX(), other.getX());
        int dz = Integer.compare(pos.getZ(), other.getZ());
        if (dx == 0 && dz == 0) return false;
        BlockPos left = pos.offset(-dz, 0, dx);
        BlockPos right = pos.offset(dz, 0, -dx);
        return isFallRisk(left.below()) && isFallRisk(right.below());
    }

    /** Goal-only check for cells genuinely bounded by multiple hazardous edges. */
    private boolean isGoalEdgeBounded(BlockPos pos) {
        int exposed = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (isFallRisk(side.below()) || isHazardCell(side) || isHazardCell(side.below())) exposed++;
        }
        return exposed >= 2;
    }

    /** Opposing body walls make the goal itself a genuine one-wide opening. */
    private boolean isGoalNarrow(BlockPos pos) {
        return (blocksBody(pos.west()) && blocksBody(pos.east()))
                || (blocksBody(pos.north()) && blocksBody(pos.south()));
    }

    private boolean isHazardCell(BlockPos pos) {
        var state = client.level.getBlockState(pos);
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }

    private void continueSprintJump(LocalPlayer player) {
        int landingIndex = nodes.indexOf(sprintJumpLanding);
        Vec3 waypoint = landingIndex >= 0 ? steeringNodes.get(landingIndex)
                : clearanceAdjustedCenter(sprintJumpLanding, player.getEyeY());
        Vec3 landingAim = new Vec3(waypoint.x, player.getEyeY(), waypoint.z);
        aim.aimAt(landingAim);
        aim.tick();
        releaseDirectionalInputs();
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(sprintJumpUsesSprint);
        // Holding jump is intentional for a single gap-crossing arc; randomized pulses
        // are reserved for repeated grounded stair/step/head-bump jumps.
        client.options.keyJump.setDown(true);
    }

    /** A short look-ahead removes center-to-center yaw snaps without cutting corners. */
    private Vec3 followTarget(LocalPlayer player, BlockPos node) {
        Vec3 waypoint = steeringNodes.get(index);
        Vec3 current = new Vec3(waypoint.x, player.getEyeY(), waypoint.z);
        if (index + 1 >= nodes.size()) return current;
        BlockPos next = nodes.get(index + 1);
        if (next.getY() != node.getY() || isCrawlNode(node) || isCrawlNode(next)) return current;
        if (index > 0) {
            BlockPos previous = nodes.get(index - 1);
            int inX = Integer.compare(node.getX(), previous.getX());
            int inZ = Integer.compare(node.getZ(), previous.getZ());
            int outX = Integer.compare(next.getX(), node.getX());
            int outZ = Integer.compare(next.getZ(), node.getZ());
            if (inX != outX || inZ != outZ) return current;
        }
        Vec3 nextWaypoint = steeringNodes.get(index + 1);
        Vec3 ahead = new Vec3(nextWaypoint.x, player.getEyeY(), nextWaypoint.z);
        return current.lerp(ahead, 0.45);
    }

    /** Returns the exact precomputed clearance waypoint when this is a path node. */
    private Vec3 clearanceAdjustedCenter(BlockPos pos, double y) {
        int at = nodes.indexOf(pos);
        Vec3 waypoint = at >= 0 ? steeringNodes.get(at) : clearanceWaypoint(client, pos);
        return new Vec3(waypoint.x, y, waypoint.z);
    }

    /**
     * Centers each axis in its locally usable interval. A solid neighbor constrains
     * that side to a half-block wall clearance; an open side permits 0.70 blocks,
     * producing a visible 0.10 offset toward free space beside a single wall while
     * keeping opposing one-wide walls safely balanced.
     */
    public static List<Vec3> buildSteeringNodes(Minecraft client, List<BlockPos> nodes) {
        return nodes.stream().map(pos -> clearanceWaypoint(client, pos)).toList();
    }

    private static Vec3 clearanceWaypoint(Minecraft client, BlockPos pos) {
        double centerX = pos.getX() + 0.5;
        double centerZ = pos.getZ() + 0.5;
        double minX = pos.getX() + (blocksBody(client, pos.west()) ? WALL_CLEARANCE : 1.0 - OPEN_SIDE_LIMIT);
        double maxX = pos.getX() + (blocksBody(client, pos.east()) ? 1.0 - WALL_CLEARANCE : OPEN_SIDE_LIMIT);
        double minZ = pos.getZ() + (blocksBody(client, pos.north()) ? WALL_CLEARANCE : 1.0 - OPEN_SIDE_LIMIT);
        double maxZ = pos.getZ() + (blocksBody(client, pos.south()) ? 1.0 - WALL_CLEARANCE : OPEN_SIDE_LIMIT);
        double x = minX <= maxX ? (minX + maxX) * 0.5 : centerX;
        double z = minZ <= maxZ ? (minZ + maxZ) * 0.5 : centerZ;
        return new Vec3(x, pos.getY() + 0.5, z);
    }

    private boolean blocksBody(BlockPos pos) {
        return blocksBody(client, pos);
    }

    private static boolean blocksBody(Minecraft client, BlockPos pos) {
        return client.level != null
                && (!client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty()
                || !client.level.getBlockState(pos.above()).getCollisionShape(client.level, pos.above()).isEmpty());
    }

    /** A route rising out of a boxed feet cell or over its solid lip requires jump. */
    private boolean isConfinedVerticalEscape(LocalPlayer player, BlockPos node) {
        BlockPos feet = player.blockPosition();
        if (node.getY() <= feet.getY()) return false;
        int blockedSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (blocksBody(feet.relative(direction))) blockedSides++;
        }
        boolean directlyAbove = node.getX() == feet.getX() && node.getZ() == feet.getZ();
        int dx = Integer.compare(node.getX(), feet.getX());
        int dz = Integer.compare(node.getZ(), feet.getZ());
        boolean solidLip = (dx != 0 || dz != 0) && blocksBody(feet.offset(dx, 0, dz));
        return blockedSides >= 3 || (directlyAbove && blockedSides >= 1)
                || (solidLip && blockedSides >= 2);
    }

    private void steerSwimming(LocalPlayer player, Vec3 target, BlockPos node) {
        Vec3 horizontalTarget = new Vec3(target.x, player.getEyeY(), target.z);
        steerToward(player, horizontalTarget, false);
        double horizontal = Math.hypot(target.x - player.getX(), target.z - player.getZ());
        double pathY = node.getY() + 0.5;
        float pathPitch = (float) Mth.clamp(
                -Math.toDegrees(Math.atan2(pathY - player.getEyeY(), Math.max(0.01, horizontal))),
                -32.0, 32.0);
        // Eyes below the fluid surface need a gentle, sustained rise. Near the
        // surface, a slight upward pitch maintains breathing without porpoising.
        boolean needsAir = player.isUnderWater();
        float desiredPitch = needsAir ? -18.0F : Math.min(pathPitch, -4.0F);
        player.setXRot(Mth.lerp(0.22F, player.getXRot(), desiredPitch));
    }

    private void steerToward(LocalPlayer player, Vec3 target, boolean slippery) {
        double dx = target.x - player.getX(), dz = target.z - player.getZ();
        float targetYaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        Vec3 velocity = player.getDeltaMovement();
        double speed = Math.hypot(velocity.x, velocity.z);
        if (slippery && speed > 0.08) {
            float velocityYaw = (float) Mth.wrapDegrees(
                    Math.toDegrees(Math.atan2(velocity.z, velocity.x)) - 90.0);
            targetYaw = player.getYRot() + Mth.wrapDegrees(
                    0.70F * Mth.wrapDegrees(targetYaw - player.getYRot())
                            + 0.30F * Mth.wrapDegrees(velocityYaw - player.getYRot()));
        }
        float delta = Mth.wrapDegrees(targetYaw - player.getYRot());
        float deadzone = slippery ? 2.5F : 1.25F;
        if (Math.abs(delta) > deadzone) {
            // Snap the heading on normal terrain so jumps launch in the right direction
            // (the look-ahead target already smooths steering); only ice keeps a damped
            // turn to avoid overshooting on low friction.
            float yaw = slippery
                    ? player.getYRot() + Mth.clamp(delta, -4.0F, 4.0F)
                    : targetYaw;
            player.setYRot(yaw); player.setYHeadRot(yaw); player.setYBodyRot(yaw);
        }
        player.setXRot(Mth.lerp(0.18F, player.getXRot(), 0.0F));
        if (slippery && (Math.abs(delta) > 28.0F || speed > 0.48)) {
            client.options.keySprint.setDown(false);
        }
    }

    private boolean isSlippery(LocalPlayer player, BlockPos target) {
        return isIce(player.blockPosition().below()) || isIce(target.below());
    }

    private boolean isIce(BlockPos pos) {
        var state = client.level.getBlockState(pos);
        return state.is(net.minecraft.world.level.block.Blocks.ICE)
                || state.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)
                || state.is(net.minecraft.world.level.block.Blocks.BLUE_ICE)
                || state.is(net.minecraft.world.level.block.Blocks.FROSTED_ICE);
    }

    private boolean isCrawlNode(BlockPos pos) {
        return client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty()
                && !client.level.getBlockState(pos.above()).getCollisionShape(client.level, pos.above()).isEmpty();
    }

    private boolean isCobweb(BlockPos pos) {
        return client.level.getBlockState(pos).is(Blocks.COBWEB);
    }

    private boolean isStickyJumpSurface(BlockPos pos) {
        var state = client.level.getBlockState(pos);
        return state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.SLIME_BLOCK)
                || state.getBlock().getJumpFactor() < 1.0F;
    }

    /** Detect a supported ascent whose low destination ceiling forces head-bump pulses. */
    private boolean isLowCeilingAscent(BlockPos feet, BlockPos destination) {
        return destination.getY() > feet.getY()
                && (!hasOpenJumpHeadroom(feet) || !hasOpenJumpHeadroom(destination));
    }

    private void pressSpamJumpIfReady(LocalPlayer player) {
        if (!player.onGround()) {
            spamJumpWasGrounded = false;
            return;
        }
        long now = System.nanoTime();
        // A landing always wins over the humanized repeat timer. The timer only adds
        // light jitter while we remain grounded; it can never consume a landing window.
        boolean justLanded = !spamJumpWasGrounded;
        spamJumpWasGrounded = true;
        if (!justLanded && now < nextSpamJumpNanos) return;
        client.options.keyJump.setDown(true);
        long range = MAX_SPAM_JUMP_INTERVAL_NANOS - MIN_SPAM_JUMP_INTERVAL_NANOS;
        nextSpamJumpNanos = now + MIN_SPAM_JUMP_INTERVAL_NANOS
                + (long) (jumpRandom.nextDouble() * (range + 1L));
    }

    private boolean hasOpenJumpHeadroom(BlockPos feet) {
        return client.level.getBlockState(feet.above()).getCollisionShape(client.level, feet.above()).isEmpty()
                && client.level.getBlockState(feet.above(2))
                .getCollisionShape(client.level, feet.above(2)).isEmpty();
    }

    private void advancePastReachedOrPassed(LocalPlayer player) {
        while (index < nodes.size()
                && (reached(player, nodes.get(index)) || passed(player, nodes.get(index)))) {
            index++;
        }
    }

    /** True once the player has crossed the plane through this node along its path edge. */
    private boolean passed(LocalPlayer player, BlockPos node) {
        if (index <= 0 || index >= nodes.size() || !nodes.get(index).equals(node)) return false;
        BlockPos previous = nodes.get(index - 1);
        double edgeX = node.getX() - previous.getX();
        double edgeZ = node.getZ() - previous.getZ();
        if (edgeX == 0.0 && edgeZ == 0.0) return player.getY() >= node.getY();
        Vec3 waypoint = steeringNodes.get(index);
        double beyondX = player.getX() - waypoint.x;
        double beyondZ = player.getZ() - waypoint.z;
        return beyondX * edgeX + beyondZ * edgeZ >= 0.0;
    }

    private boolean handleTraversal(LocalPlayer player, BlockPos node) {
        BlockPos support = node.below();
        boolean unsupportedUp = node.getX() == player.getBlockX()
                && node.getZ() == player.getBlockZ() && node.getY() > player.getBlockY()
                && !isFluid(node) && !isFluid(support) && isFallRisk(support);
        // A vertical PLACE edge wins over mining even if a prior failed placement has
        // temporarily made the destination collide.
        if (unsupportedUp) return handlePillar(player);
        var state = client.level.getBlockState(node);
        if (!state.getCollisionShape(client.level, node).isEmpty()) {
            if (!allowMining) {
                finish(false, "Route became blocked and mining is disabled");
                return true;
            }
            status("mining");
            releaseInputs();
            aim.aimAt(node); aim.tick();
            if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(node))
                    <= player.blockInteractionRange() * player.blockInteractionRange()
                    && aim.isAimed() && TalosClient.tickBudget().hasBudgetRemaining()) {
                int best = player.getInventory().getSelectedSlot();
                float speed = player.getInventory().getItem(best).getDestroySpeed(state);
                for (int i = 0; i < 9; i++) {
                    float candidate = player.getInventory().getItem(i).getDestroySpeed(state);
                    if (candidate > speed) { best = i; speed = candidate; }
                }
                player.getInventory().setSelectedSlot(best);
                if (!node.equals(breaking)) {
                    client.gameMode.startDestroyBlock(node, Direction.UP);
                    breaking = node.immutable();
                } else client.gameMode.continueDestroyBlock(node, Direction.UP);
                player.swing(InteractionHand.MAIN_HAND);
            }
            return true;
        }
        breaking = null;
        var supportState = client.level.getBlockState(support);
        if (isFluid(node) || isFluid(support)) return false;
        // Confirm placement from world state, then walk onto the new support before
        // handling the next PLACE edge. Sneak permits forward movement but catches us
        // at the far lip if the following placement is delayed by the server.
        if (support.equals(lastBridgeTarget) && !isFallRisk(support)) {
            status("bridging");
            releaseDirectionalInputs();
            client.options.keyUp.setDown(true);
            client.options.keyShift.setDown(true);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(false);
            steerToward(player, clearanceAdjustedCenter(node, player.getEyeY()), false);
            return true;
        }
        if (isFallRisk(support)) {
            // Never approach an unsupported edge at walking speed.  Keeping sneak held
            // also makes a delayed server placement safe: the player stops at the lip.
            status("bridging");
            releaseInputs();
            client.options.keyShift.setDown(true);
            // Open trapdoors/gates occupy the support cell: remove them before filling it.
            if (!supportState.isAir() && (supportState.getBlock() instanceof TrapDoorBlock
                    || supportState.getBlock() instanceof FenceGateBlock)) {
                breakBlock(player, support, supportState);
                return true;
            }
            int blockSlot = findBlockSlot(player);
            if (blockSlot < 0) { finish(false, "Ran out of bridge blocks"); return true; }
            int dx = Integer.compare(node.getX(), player.getBlockX());
            int dz = Integer.compare(node.getZ(), player.getBlockZ());
            boolean pillar = dx == 0 && dz == 0 && node.getY() > player.getBlockY();
            if (pillar) return handlePillar(player);

            BlockPos standingSupport = player.blockPosition().below();
            BlockPos placementTarget = support;
            BlockPos anchor = standingSupport;
            Direction side;
            if (dx != 0 && dz != 0) {
                // A diagonal bridge is an L-shaped two-block cycle.  First extend the
                // standing block along X, then attach the diagonal block to its Z face.
                BlockPos xSupport = standingSupport.offset(dx, 0, 0);
                if (isFallRisk(xSupport)) {
                    placementTarget = xSupport;
                    side = dx > 0 ? Direction.EAST : Direction.WEST;
                } else {
                    anchor = xSupport;
                    side = dz > 0 ? Direction.SOUTH : Direction.NORTH;
                }
            } else {
                side = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST
                        : dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }

            placeBridgeBlock(player, blockSlot, anchor, side, placementTarget);
            return true;
        }
        lastBridgeTarget = null;
        return false;
    }

    private void placeBridgeBlock(LocalPlayer player, int blockSlot, BlockPos anchor,
                                  Direction side, BlockPos placementTarget) {
        // Never attempt to replace either of the two cells occupied by the player.
        BlockPos feet = player.blockPosition();
        if (placementTarget.equals(feet) || placementTarget.equals(feet.above())) return;
        // The hit must describe a fresh, existing anchor face adjacent to this exact
        // target. Invalid/stale faces otherwise silently no-op on every later retry.
        if (isFallRisk(anchor) || !anchor.relative(side).equals(placementTarget)) return;
        Vec3 hit = Vec3.atCenterOf(anchor).add(side.getStepX() * 0.5,
                side.getStepY() * 0.5, side.getStepZ() * 0.5);
        aim.aimAt(hit);
        aim.tick();
        boolean rateReady = !placementTarget.equals(lastBridgeTarget)
                || ticks - lastBridgePlaceTick >= 2;
        if (!rateReady || !aim.isAimed() || !TalosClient.tickBudget().hasBudgetRemaining()) return;
        player.getInventory().setSelectedSlot(blockSlot);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, side, anchor, false));
        player.swing(InteractionHand.MAIN_HAND);
        lastBridgeTarget = placementTarget.immutable();
        lastBridgePlaceTick = ticks;
    }

    private boolean handlePillar(LocalPlayer player) {
        status("pillaring up");
        releaseInputs();
        int blockSlot = findBlockSlot(player);
        if (blockSlot < 0) { finish(false, "Ran out of pillar blocks"); return true; }
        if (pillarOrigin == null || player.onGround()) pillarOrigin = player.blockPosition().immutable();
        BlockPos anchor = pillarOrigin.below();
        Vec3 hit = Vec3.atCenterOf(anchor).add(0.0, 0.5, 0.0);
        aim.aimAt(hit);
        aim.tick();
        player.getInventory().setSelectedSlot(blockSlot);
        pressSpamJumpIfReady(player);
        // Jump input is applied after this task tick. Place only after the player's feet
        // clear the target cell; same-tick jump+place is rejected by collision checks.
        if (player.getY() >= pillarOrigin.getY() + 0.42 && aim.isAimed()
                && TalosClient.tickBudget().hasBudgetRemaining()) {
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, anchor, false));
            player.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private int findBlockSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof BlockItem) return i;
        }
        return -1;
    }

    private void breakBlock(LocalPlayer player, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        aim.aimAt(pos); aim.tick();
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))
                > player.blockInteractionRange() * player.blockInteractionRange()
                || !aim.isAimed() || !TalosClient.tickBudget().hasBudgetRemaining()) return;
        int best = player.getInventory().getSelectedSlot();
        float speed = player.getInventory().getItem(best).getDestroySpeed(state);
        for (int i = 0; i < 9; i++) {
            float candidate = player.getInventory().getItem(i).getDestroySpeed(state);
            if (candidate > speed) { best = i; speed = candidate; }
        }
        player.getInventory().setSelectedSlot(best);
        if (!pos.equals(breaking)) {
            client.gameMode.startDestroyBlock(pos, Direction.UP);
            breaking = pos.immutable();
        } else client.gameMode.continueDestroyBlock(pos, Direction.UP);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean isFallRisk(BlockPos support) {
        var state = client.level.getBlockState(support);
        if (state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        }
        var shape = state.getCollisionShape(client.level, support);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) < 0.5;
    }

    private boolean isStairOrSlab(BlockPos pos) {
        var block = client.level.getBlockState(pos).getBlock();
        return block instanceof StairBlock || block instanceof SlabBlock;
    }

    private boolean isWater(BlockPos pos) {
        return client.level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    private boolean isLava(BlockPos pos) {
        return client.level.getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    private boolean isFluid(BlockPos pos) { return isWater(pos) || isLava(pos); }

    private boolean reached(LocalPlayer player, BlockPos node) {
        Vec3 waypoint = steeringNodes.get(index);
        double dx = player.getX() - waypoint.x, dz = player.getZ() - waypoint.z;
        return dx * dx + dz * dz < NODE_DISTANCE_SQUARED
                && Math.abs(player.getY() - node.getY()) <= 0.75;
    }

    public void cancel() { finish(false, "Pathing cancelled"); _break(); }
    private void finish(boolean success, String detail) {
        if (client.player != null) {
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                    (success ? "§aTalos §7» §f" : "§cTalos §7» §f") + detail));
        }
        releaseInputs();
        future.complete(new PathResult(success, detail));
    }
    private void releaseInputs() {
        releaseDirectionalInputs();
        client.options.keyJump.setDown(false); client.options.keySprint.setDown(false);
    }
    private void releaseDirectionalInputs() {
        client.options.keyUp.setDown(false); client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false); client.options.keyRight.setDown(false);
        client.options.keyShift.setDown(false);
    }
    @Override public void onCompleted() {
        releaseInputs();
        if (!future.isDone()) future.complete(new PathResult(false, "Navigation was interrupted"));
    }
    @Override public Set<Object> getMutexKeys() { return Set.of("talos-player-movement"); }
}
