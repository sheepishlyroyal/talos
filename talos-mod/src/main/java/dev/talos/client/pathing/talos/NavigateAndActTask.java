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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.item.BlockItem;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import org.jetbrains.annotations.Nullable;

/** One cooperative tick loop for waypoint movement, immediate looking, and route actions. */
public final class NavigateAndActTask extends TalosTask {
    public interface RouteAction {
        /** Current world-space target, or null when there is no target to act on. */
        @Nullable Vec3d target();
        double reach();
        /** Performs at most one tick of work; true means the action is complete. */
        boolean perform(MinecraftClient client);
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
    private final MinecraftClient client;
    private final List<BlockPos> nodes;
    private final List<Vec3d> steeringNodes;
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
            client.player.sendMessage(net.minecraft.text.Text.literal("§bTalos §7» §f" + mode), true);
        }
    }

    public NavigateAndActTask(MinecraftClient client, List<BlockPos> nodes,
                              Predicate<BlockPos> goal, @Nullable RouteAction action,
                              CompletableFuture<PathResult> future) {
        this(client, nodes, goal, action, future, true);
    }

    public NavigateAndActTask(MinecraftClient client, List<BlockPos> nodes,
                              Predicate<BlockPos> goal, @Nullable RouteAction action,
                              CompletableFuture<PathResult> future, boolean allowMining) {
        this(client, nodes, buildSteeringNodes(client, nodes), goal, action, future, allowMining);
    }

    public NavigateAndActTask(MinecraftClient client, List<BlockPos> nodes,
                              List<Vec3d> steeringNodes, Predicate<BlockPos> goal,
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
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) { finish(false, "World unloaded while navigating"); return; }
        advancePastReachedOrPassed(player);
        if (sprintJumpLanding != null) {
            sprintJumpWasAirborne |= !player.isOnGround();
            if (!(sprintJumpWasAirborne && player.isOnGround())) {
                // An overshot landing must never remain the aim lock. While airborne,
                // move the lock forward with the path as soon as its old node is passed.
                if (index < nodes.size() && !nodes.get(index).equals(sprintJumpLanding)) {
                    sprintJumpLanding = nodes.get(index).toImmutable();
                }
                continueSprintJump(player);
                scheduleDelay();
                return;
            }
            sprintJumpLanding = null;
            sprintJumpWasAirborne = false;
        }
        if (goal.test(player.getBlockPos())) { finish(true, "Arrived"); return; }
        // Landing may have carried us over several short nodes. Discard all of them
        // before selecting a new steering target, rather than aiming back at one.
        advancePastReachedOrPassed(player);
        if (index >= nodes.size()) { finish(false, "Nodes ended before the goal was reached"); return; }

        BlockPos node = nodes.get(index);
        if (handleTraversal(player, node)) { scheduleDelay(); return; }
        updateCommittedMode();
        Vec3d actionTarget = action == null ? null : action.target();
        if (actionTarget != null && player.getEyePos().squaredDistanceTo(actionTarget)
                <= action.reach() * action.reach()) {
            releaseInputs();
            aim.aimAt(actionTarget);
            aim.tick();
            if (aim.isAimed() && TalosClient.tickBudget().hasBudgetRemaining()) action.perform(client);
        } else {
            Vec3d followTarget = followTarget(player, node);
            // Recompute steering without flickering sprint/jump off for part of every
            // client tick. Normal travel keeps both inputs continuously held.
            releaseDirectionalInputs();
            client.options.forwardKey.setPressed(true);
            client.options.sprintKey.setPressed(true);
            int horizontalDistance = Math.max(Math.abs(node.getX() - player.getBlockX()),
                    Math.abs(node.getZ() - player.getBlockZ()));
            boolean jumpEdge = horizontalDistance > 1;
            boolean stairOrSlab = isStairOrSlab(node.down()) || isStairOrSlab(player.getBlockPos().down());
            boolean swimEdge = isFluid(node) || player.isSwimming() || player.isSubmergedInWater()
                    || player.isTouchingWater() || player.isInLava();
            boolean inCobweb = isCobweb(player.getBlockPos()) || isCobweb(node);
            boolean stickyJumpSurface = isStickyJumpSurface(player.getBlockPos().down())
                    || isStickyJumpSurface(node.down());
            boolean headHitStep = isLowCeilingAscent(player.getBlockPos(), node);
            boolean climbingOut = isConfinedVerticalEscape(player, node);
            if (swimEdge) {
                // Swimming is driven by view pitch. Jump causes repeated surface
                // breaches and sinking, so it is deliberately never used here.
                status(isLava(node) || player.isInLava() ? "swimming (lava)" : "swimming");
                client.options.jumpKey.setPressed(false);
                steerSwimming(player, followTarget, node);
            } else if (climbingOut) {
                // A boxed ascent is impossible without jumping. This deliberately
                // precedes WALK and crawl/slow-terrain handling so precision mode can
                // never pin the player against the wall of a one-wide pit.
                status("climbing out");
                client.options.sprintKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                steerToward(player, followTarget, false);
                pressSpamJumpIfReady(player);
            } else if (inCobweb) {
                // Sprinting and jumping only waste inputs against cobweb collision.
                status("slow terrain (cobweb)");
                client.options.sprintKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                steerToward(player, followTarget, false);
            } else if (stickyJumpSurface) {
                // Honey suppresses jump velocity and slime turns repeated jumps into
                // unwanted bounces. Push through without fighting either surface.
                status("slow terrain (sticky)");
                client.options.sprintKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                steerToward(player, followTarget, false);
            } else if (isCrawlNode(node)) {
                status("crawling");
                client.options.sneakKey.setPressed(true);
                client.options.jumpKey.setPressed(false);
                steerToward(player, followTarget, false);
            } else if (headHitStep) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("spam-jump (head-hit)");
                client.options.jumpKey.setPressed(false);
                pressSpamJumpIfReady(player);
            } else if (stairOrSlab) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("spam-jump (stairs)");
                // Stair/slab travel deliberately pulses space instead of holding it.
                client.options.jumpKey.setPressed(false);
                pressSpamJumpIfReady(player);
            } else if (jumpEdge) {
                steerToward(player, followTarget, isSlippery(player, node));
                // A gap edge is one indivisible movement: never let waypoint advancement
                // turn the player back toward a passed node during the airborne arc.
                sprintJumpUsesSprint = committedMode != PlannedMode.WALK || horizontalDistance > 2;
                status(sprintJumpUsesSprint ? "sprint-jump" : "precise jump");
                sprintJumpLanding = node.toImmutable();
                sprintJumpWasAirborne = !player.isOnGround();
                client.options.sprintKey.setPressed(sprintJumpUsesSprint);
                client.options.jumpKey.setPressed(true);
            } else if (committedMode == PlannedMode.SPAM_JUMP) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("spam-jump");
                client.options.jumpKey.setPressed(false);
                pressSpamJumpIfReady(player);
            } else if (committedMode == PlannedMode.WALK) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("walk/precise");
                client.options.sprintKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
            } else if (committedMode == PlannedMode.SPRINT) {
                steerToward(player, followTarget, isSlippery(player, node));
                status("sprint");
                client.options.jumpKey.setPressed(false);
            } else {
                steerToward(player, followTarget, isSlippery(player, node));
                // Continuous bunny-hopping is only appropriate in a fully open
                // corridor. A block in either jump headspace uses prompt pulses.
                status("sprint-jump");
                client.options.jumpKey.setPressed(true);
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
                client.options.jumpKey.setPressed(false);
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
        if (isLowCeilingAscent(from, pos) || isStairOrSlab(pos.down())
                || isStairOrSlab(from.down())) return PlannedMode.SPAM_JUMP;
        if (!hasOpenJumpHeadroom(pos)) return PlannedMode.SPAM_JUMP;
        if (isIce(pos.down())) return PlannedMode.SPRINT;

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
        BlockPos left = pos.add(-dz, 0, dx);
        BlockPos right = pos.add(dz, 0, -dx);
        return isFallRisk(left.down()) && isFallRisk(right.down());
    }

    /** Goal-only check for cells genuinely bounded by multiple hazardous edges. */
    private boolean isGoalEdgeBounded(BlockPos pos) {
        int exposed = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos side = pos.offset(direction);
            if (isFallRisk(side.down()) || isHazardCell(side) || isHazardCell(side.down())) exposed++;
        }
        return exposed >= 2;
    }

    /** Opposing body walls make the goal itself a genuine one-wide opening. */
    private boolean isGoalNarrow(BlockPos pos) {
        return (blocksBody(pos.west()) && blocksBody(pos.east()))
                || (blocksBody(pos.north()) && blocksBody(pos.south()));
    }

    private boolean isHazardCell(BlockPos pos) {
        var state = client.world.getBlockState(pos);
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private void continueSprintJump(ClientPlayerEntity player) {
        int landingIndex = nodes.indexOf(sprintJumpLanding);
        Vec3d waypoint = landingIndex >= 0 ? steeringNodes.get(landingIndex)
                : clearanceAdjustedCenter(sprintJumpLanding, player.getEyeY());
        Vec3d landingAim = new Vec3d(waypoint.x, player.getEyeY(), waypoint.z);
        aim.aimAt(landingAim);
        aim.tick();
        releaseDirectionalInputs();
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(sprintJumpUsesSprint);
        // Holding jump is intentional for a single gap-crossing arc; randomized pulses
        // are reserved for repeated grounded stair/step/head-bump jumps.
        client.options.jumpKey.setPressed(true);
    }

    /** A short look-ahead removes center-to-center yaw snaps without cutting corners. */
    private Vec3d followTarget(ClientPlayerEntity player, BlockPos node) {
        Vec3d waypoint = steeringNodes.get(index);
        Vec3d current = new Vec3d(waypoint.x, player.getEyeY(), waypoint.z);
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
        Vec3d nextWaypoint = steeringNodes.get(index + 1);
        Vec3d ahead = new Vec3d(nextWaypoint.x, player.getEyeY(), nextWaypoint.z);
        return current.lerp(ahead, 0.45);
    }

    /** Returns the exact precomputed clearance waypoint when this is a path node. */
    private Vec3d clearanceAdjustedCenter(BlockPos pos, double y) {
        int at = nodes.indexOf(pos);
        Vec3d waypoint = at >= 0 ? steeringNodes.get(at) : clearanceWaypoint(client, pos);
        return new Vec3d(waypoint.x, y, waypoint.z);
    }

    /**
     * Centers each axis in its locally usable interval. A solid neighbor constrains
     * that side to a half-block wall clearance; an open side permits 0.70 blocks,
     * producing a visible 0.10 offset toward free space beside a single wall while
     * keeping opposing one-wide walls safely balanced.
     */
    public static List<Vec3d> buildSteeringNodes(MinecraftClient client, List<BlockPos> nodes) {
        return nodes.stream().map(pos -> clearanceWaypoint(client, pos)).toList();
    }

    private static Vec3d clearanceWaypoint(MinecraftClient client, BlockPos pos) {
        double centerX = pos.getX() + 0.5;
        double centerZ = pos.getZ() + 0.5;
        double minX = pos.getX() + (blocksBody(client, pos.west()) ? WALL_CLEARANCE : 1.0 - OPEN_SIDE_LIMIT);
        double maxX = pos.getX() + (blocksBody(client, pos.east()) ? 1.0 - WALL_CLEARANCE : OPEN_SIDE_LIMIT);
        double minZ = pos.getZ() + (blocksBody(client, pos.north()) ? WALL_CLEARANCE : 1.0 - OPEN_SIDE_LIMIT);
        double maxZ = pos.getZ() + (blocksBody(client, pos.south()) ? 1.0 - WALL_CLEARANCE : OPEN_SIDE_LIMIT);
        double x = minX <= maxX ? (minX + maxX) * 0.5 : centerX;
        double z = minZ <= maxZ ? (minZ + maxZ) * 0.5 : centerZ;
        return new Vec3d(x, pos.getY() + 0.5, z);
    }

    private boolean blocksBody(BlockPos pos) {
        return blocksBody(client, pos);
    }

    private static boolean blocksBody(MinecraftClient client, BlockPos pos) {
        return client.world != null
                && (!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty()
                || !client.world.getBlockState(pos.up()).getCollisionShape(client.world, pos.up()).isEmpty());
    }

    /** A route rising out of a boxed feet cell or over its solid lip requires jump. */
    private boolean isConfinedVerticalEscape(ClientPlayerEntity player, BlockPos node) {
        BlockPos feet = player.getBlockPos();
        if (node.getY() <= feet.getY()) return false;
        int blockedSides = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (blocksBody(feet.offset(direction))) blockedSides++;
        }
        boolean directlyAbove = node.getX() == feet.getX() && node.getZ() == feet.getZ();
        int dx = Integer.compare(node.getX(), feet.getX());
        int dz = Integer.compare(node.getZ(), feet.getZ());
        boolean solidLip = (dx != 0 || dz != 0) && blocksBody(feet.add(dx, 0, dz));
        return blockedSides >= 3 || (directlyAbove && blockedSides >= 1)
                || (solidLip && blockedSides >= 2);
    }

    private void steerSwimming(ClientPlayerEntity player, Vec3d target, BlockPos node) {
        Vec3d horizontalTarget = new Vec3d(target.x, player.getEyeY(), target.z);
        steerToward(player, horizontalTarget, false);
        double horizontal = Math.hypot(target.x - player.getX(), target.z - player.getZ());
        double pathY = node.getY() + 0.5;
        float pathPitch = (float) MathHelper.clamp(
                -Math.toDegrees(Math.atan2(pathY - player.getEyeY(), Math.max(0.01, horizontal))),
                -32.0, 32.0);
        // Eyes below the fluid surface need a gentle, sustained rise. Near the
        // surface, a slight upward pitch maintains breathing without porpoising.
        boolean needsAir = player.isSubmergedInWater();
        float desiredPitch = needsAir ? -18.0F : Math.min(pathPitch, -4.0F);
        player.setPitch(MathHelper.lerp(0.22F, player.getPitch(), desiredPitch));
    }

    private void steerToward(ClientPlayerEntity player, Vec3d target, boolean slippery) {
        double dx = target.x - player.getX(), dz = target.z - player.getZ();
        float targetYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        Vec3d velocity = player.getVelocity();
        double speed = Math.hypot(velocity.x, velocity.z);
        if (slippery && speed > 0.08) {
            float velocityYaw = (float) MathHelper.wrapDegrees(
                    Math.toDegrees(Math.atan2(velocity.z, velocity.x)) - 90.0);
            targetYaw = player.getYaw() + MathHelper.wrapDegrees(
                    0.70F * MathHelper.wrapDegrees(targetYaw - player.getYaw())
                            + 0.30F * MathHelper.wrapDegrees(velocityYaw - player.getYaw()));
        }
        float delta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float deadzone = slippery ? 2.5F : 1.25F;
        if (Math.abs(delta) > deadzone) {
            // Snap the heading on normal terrain so jumps launch in the right direction
            // (the look-ahead target already smooths steering); only ice keeps a damped
            // turn to avoid overshooting on low friction.
            float yaw = slippery
                    ? player.getYaw() + MathHelper.clamp(delta, -4.0F, 4.0F)
                    : targetYaw;
            player.setYaw(yaw); player.setHeadYaw(yaw); player.setBodyYaw(yaw);
        }
        player.setPitch(MathHelper.lerp(0.18F, player.getPitch(), 0.0F));
        if (slippery && (Math.abs(delta) > 28.0F || speed > 0.48)) {
            client.options.sprintKey.setPressed(false);
        }
    }

    private boolean isSlippery(ClientPlayerEntity player, BlockPos target) {
        return isIce(player.getBlockPos().down()) || isIce(target.down());
    }

    private boolean isIce(BlockPos pos) {
        var state = client.world.getBlockState(pos);
        return state.isOf(net.minecraft.block.Blocks.ICE)
                || state.isOf(net.minecraft.block.Blocks.PACKED_ICE)
                || state.isOf(net.minecraft.block.Blocks.BLUE_ICE)
                || state.isOf(net.minecraft.block.Blocks.FROSTED_ICE);
    }

    private boolean isCrawlNode(BlockPos pos) {
        return client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty()
                && !client.world.getBlockState(pos.up()).getCollisionShape(client.world, pos.up()).isEmpty();
    }

    private boolean isCobweb(BlockPos pos) {
        return client.world.getBlockState(pos).isOf(Blocks.COBWEB);
    }

    private boolean isStickyJumpSurface(BlockPos pos) {
        var state = client.world.getBlockState(pos);
        return state.isOf(Blocks.HONEY_BLOCK) || state.isOf(Blocks.SLIME_BLOCK)
                || state.getBlock().getJumpVelocityMultiplier() < 1.0F;
    }

    /** Detect a supported ascent whose low destination ceiling forces head-bump pulses. */
    private boolean isLowCeilingAscent(BlockPos feet, BlockPos destination) {
        return destination.getY() > feet.getY()
                && (!hasOpenJumpHeadroom(feet) || !hasOpenJumpHeadroom(destination));
    }

    private void pressSpamJumpIfReady(ClientPlayerEntity player) {
        if (!player.isOnGround()) {
            spamJumpWasGrounded = false;
            return;
        }
        long now = System.nanoTime();
        // A landing always wins over the humanized repeat timer. The timer only adds
        // light jitter while we remain grounded; it can never consume a landing window.
        boolean justLanded = !spamJumpWasGrounded;
        spamJumpWasGrounded = true;
        if (!justLanded && now < nextSpamJumpNanos) return;
        client.options.jumpKey.setPressed(true);
        long range = MAX_SPAM_JUMP_INTERVAL_NANOS - MIN_SPAM_JUMP_INTERVAL_NANOS;
        nextSpamJumpNanos = now + MIN_SPAM_JUMP_INTERVAL_NANOS
                + (long) (jumpRandom.nextDouble() * (range + 1L));
    }

    private boolean hasOpenJumpHeadroom(BlockPos feet) {
        return client.world.getBlockState(feet.up()).getCollisionShape(client.world, feet.up()).isEmpty()
                && client.world.getBlockState(feet.up(2))
                .getCollisionShape(client.world, feet.up(2)).isEmpty();
    }

    private void advancePastReachedOrPassed(ClientPlayerEntity player) {
        while (index < nodes.size()
                && (reached(player, nodes.get(index)) || passed(player, nodes.get(index)))) {
            index++;
        }
    }

    /** True once the player has crossed the plane through this node along its path edge. */
    private boolean passed(ClientPlayerEntity player, BlockPos node) {
        if (index <= 0 || index >= nodes.size() || !nodes.get(index).equals(node)) return false;
        BlockPos previous = nodes.get(index - 1);
        double edgeX = node.getX() - previous.getX();
        double edgeZ = node.getZ() - previous.getZ();
        if (edgeX == 0.0 && edgeZ == 0.0) return player.getY() >= node.getY();
        Vec3d waypoint = steeringNodes.get(index);
        double beyondX = player.getX() - waypoint.x;
        double beyondZ = player.getZ() - waypoint.z;
        return beyondX * edgeX + beyondZ * edgeZ >= 0.0;
    }

    private boolean handleTraversal(ClientPlayerEntity player, BlockPos node) {
        BlockPos support = node.down();
        boolean unsupportedUp = node.getX() == player.getBlockX()
                && node.getZ() == player.getBlockZ() && node.getY() > player.getBlockY()
                && !isFluid(node) && !isFluid(support) && isFallRisk(support);
        // A vertical PLACE edge wins over mining even if a prior failed placement has
        // temporarily made the destination collide.
        if (unsupportedUp) return handlePillar(player);
        var state = client.world.getBlockState(node);
        if (!state.getCollisionShape(client.world, node).isEmpty()) {
            if (!allowMining) {
                finish(false, "Route became blocked and mining is disabled");
                return true;
            }
            status("mining");
            releaseInputs();
            aim.aimAt(node); aim.tick();
            if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(node))
                    <= player.getBlockInteractionRange() * player.getBlockInteractionRange()
                    && aim.isAimed() && TalosClient.tickBudget().hasBudgetRemaining()) {
                int best = player.getInventory().getSelectedSlot();
                float speed = player.getInventory().getStack(best).getMiningSpeedMultiplier(state);
                for (int i = 0; i < 9; i++) {
                    float candidate = player.getInventory().getStack(i).getMiningSpeedMultiplier(state);
                    if (candidate > speed) { best = i; speed = candidate; }
                }
                player.getInventory().setSelectedSlot(best);
                if (!node.equals(breaking)) {
                    client.interactionManager.attackBlock(node, Direction.UP);
                    breaking = node.toImmutable();
                } else client.interactionManager.updateBlockBreakingProgress(node, Direction.UP);
                player.swingHand(Hand.MAIN_HAND);
            }
            return true;
        }
        breaking = null;
        var supportState = client.world.getBlockState(support);
        if (isFluid(node) || isFluid(support)) return false;
        // Confirm placement from world state, then walk onto the new support before
        // handling the next PLACE edge. Sneak permits forward movement but catches us
        // at the far lip if the following placement is delayed by the server.
        if (support.equals(lastBridgeTarget) && !isFallRisk(support)) {
            status("bridging");
            releaseDirectionalInputs();
            client.options.forwardKey.setPressed(true);
            client.options.sneakKey.setPressed(true);
            client.options.sprintKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            steerToward(player, clearanceAdjustedCenter(node, player.getEyeY()), false);
            return true;
        }
        if (isFallRisk(support)) {
            // Never approach an unsupported edge at walking speed.  Keeping sneak held
            // also makes a delayed server placement safe: the player stops at the lip.
            status("bridging");
            releaseInputs();
            client.options.sneakKey.setPressed(true);
            // Open trapdoors/gates occupy the support cell: remove them before filling it.
            if (!supportState.isAir() && (supportState.getBlock() instanceof TrapdoorBlock
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

            BlockPos standingSupport = player.getBlockPos().down();
            BlockPos placementTarget = support;
            BlockPos anchor = standingSupport;
            Direction side;
            if (dx != 0 && dz != 0) {
                // A diagonal bridge is an L-shaped two-block cycle.  First extend the
                // standing block along X, then attach the diagonal block to its Z face.
                BlockPos xSupport = standingSupport.add(dx, 0, 0);
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

    private void placeBridgeBlock(ClientPlayerEntity player, int blockSlot, BlockPos anchor,
                                  Direction side, BlockPos placementTarget) {
        // Never attempt to replace either of the two cells occupied by the player.
        BlockPos feet = player.getBlockPos();
        if (placementTarget.equals(feet) || placementTarget.equals(feet.up())) return;
        // The hit must describe a fresh, existing anchor face adjacent to this exact
        // target. Invalid/stale faces otherwise silently no-op on every later retry.
        if (isFallRisk(anchor) || !anchor.offset(side).equals(placementTarget)) return;
        Vec3d hit = Vec3d.ofCenter(anchor).add(side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        aim.aimAt(hit);
        aim.tick();
        boolean rateReady = !placementTarget.equals(lastBridgeTarget)
                || ticks - lastBridgePlaceTick >= 2;
        if (!rateReady || !aim.isAimed() || !TalosClient.tickBudget().hasBudgetRemaining()) return;
        player.getInventory().setSelectedSlot(blockSlot);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                new BlockHitResult(hit, side, anchor, false));
        player.swingHand(Hand.MAIN_HAND);
        lastBridgeTarget = placementTarget.toImmutable();
        lastBridgePlaceTick = ticks;
    }

    private boolean handlePillar(ClientPlayerEntity player) {
        status("pillaring up");
        releaseInputs();
        int blockSlot = findBlockSlot(player);
        if (blockSlot < 0) { finish(false, "Ran out of pillar blocks"); return true; }
        if (pillarOrigin == null || player.isOnGround()) pillarOrigin = player.getBlockPos().toImmutable();
        BlockPos anchor = pillarOrigin.down();
        Vec3d hit = Vec3d.ofCenter(anchor).add(0.0, 0.5, 0.0);
        aim.aimAt(hit);
        aim.tick();
        player.getInventory().setSelectedSlot(blockSlot);
        pressSpamJumpIfReady(player);
        // Jump input is applied after this task tick. Place only after the player's feet
        // clear the target cell; same-tick jump+place is rejected by collision checks.
        if (player.getY() >= pillarOrigin.getY() + 0.42 && aim.isAimed()
                && TalosClient.tickBudget().hasBudgetRemaining()) {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, anchor, false));
            player.swingHand(Hand.MAIN_HAND);
        }
        return true;
    }

    private int findBlockSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() instanceof BlockItem) return i;
        }
        return -1;
    }

    private void breakBlock(ClientPlayerEntity player, BlockPos pos, net.minecraft.block.BlockState state) {
        aim.aimAt(pos); aim.tick();
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos))
                > player.getBlockInteractionRange() * player.getBlockInteractionRange()
                || !aim.isAimed() || !TalosClient.tickBudget().hasBudgetRemaining()) return;
        int best = player.getInventory().getSelectedSlot();
        float speed = player.getInventory().getStack(best).getMiningSpeedMultiplier(state);
        for (int i = 0; i < 9; i++) {
            float candidate = player.getInventory().getStack(i).getMiningSpeedMultiplier(state);
            if (candidate > speed) { best = i; speed = candidate; }
        }
        player.getInventory().setSelectedSlot(best);
        if (!pos.equals(breaking)) {
            client.interactionManager.attackBlock(pos, Direction.UP);
            breaking = pos.toImmutable();
        } else client.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        player.swingHand(Hand.MAIN_HAND);
    }

    private boolean isFallRisk(BlockPos support) {
        var state = client.world.getBlockState(support);
        if (state.getBlock() instanceof TrapdoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return state.contains(Properties.OPEN) && state.get(Properties.OPEN);
        }
        var shape = state.getCollisionShape(client.world, support);
        return shape.isEmpty() || shape.getMax(Direction.Axis.Y) < 0.5;
    }

    private boolean isStairOrSlab(BlockPos pos) {
        var block = client.world.getBlockState(pos).getBlock();
        return block instanceof StairsBlock || block instanceof SlabBlock;
    }

    private boolean isWater(BlockPos pos) {
        return client.world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    private boolean isLava(BlockPos pos) {
        return client.world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    private boolean isFluid(BlockPos pos) { return isWater(pos) || isLava(pos); }

    private boolean reached(ClientPlayerEntity player, BlockPos node) {
        Vec3d waypoint = steeringNodes.get(index);
        double dx = player.getX() - waypoint.x, dz = player.getZ() - waypoint.z;
        return dx * dx + dz * dz < NODE_DISTANCE_SQUARED
                && Math.abs(player.getY() - node.getY()) <= 0.75;
    }

    public void cancel() { finish(false, "Pathing cancelled"); _break(); }
    private void finish(boolean success, String detail) {
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(
                    (success ? "§aTalos §7» §f" : "§cTalos §7» §f") + detail), true);
        }
        releaseInputs();
        future.complete(new PathResult(success, detail));
    }
    private void releaseInputs() {
        releaseDirectionalInputs();
        client.options.jumpKey.setPressed(false); client.options.sprintKey.setPressed(false);
    }
    private void releaseDirectionalInputs() {
        client.options.forwardKey.setPressed(false); client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false); client.options.rightKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }
    @Override public void onCompleted() {
        releaseInputs();
        if (!future.isDone()) future.complete(new PathResult(false, "Navigation was interrupted"));
    }
    @Override public Set<Object> getMutexKeys() { return Set.of("talos-player-movement"); }
}
