package dev.glade.client.pathing.glade;

import dev.glade.client.GladeClient;
import dev.glade.client.action.AimController;
import dev.glade.client.pathing.PathResult;
import dev.glade.client.task.GladeTask;
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
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.item.BlockItem;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import org.jetbrains.annotations.Nullable;

/** One cooperative tick loop for waypoint movement, immediate looking, and route actions. */
public final class NavigateAndActTask extends GladeTask {
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
    private final MinecraftClient client;
    private final List<BlockPos> nodes;
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
    private String lastStatus = "";

    /** Unobtrusive action-bar readout so the current movement mode is visible in-game. */
    private void status(String mode) {
        if (mode.equals(lastStatus)) return;
        lastStatus = mode;
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal("§bGlade §7» §f" + mode), true);
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
        this.client = client;
        this.nodes = List.copyOf(nodes);
        this.goal = goal;
        this.action = action;
        this.allowMining = allowMining;
        this.future = future;
        this.index = nodes.size() > 1 ? 1 : nodes.size();
        this.aim = new AimController(client, GladeClient.humanizer().rotation(),
                GladeClient.humanizer().defaultProfile(), System.nanoTime());
    }

    @Override public void initialize() { }
    @Override public boolean condition() { return !future.isDone(); }
    @Override public void increment() { ticks++; }

    @Override public void body() {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) { finish(false, "World unloaded while navigating"); return; }
        if (ticks >= 60 * 20) { finish(false, "Navigation timed out after 60 seconds"); return; }
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
        Vec3d actionTarget = action == null ? null : action.target();
        if (actionTarget != null && player.getEyePos().squaredDistanceTo(actionTarget)
                <= action.reach() * action.reach()) {
            releaseInputs();
            aim.aimAt(actionTarget);
            aim.tick();
            if (aim.isAimed() && GladeClient.tickBudget().hasBudgetRemaining()) action.perform(client);
        } else {
            aim.aimAt(new Vec3d(node.getX() + 0.5, player.getEyeY(), node.getZ() + 0.5));
            aim.tick();
            // Recompute steering without flickering sprint/jump off for part of every
            // client tick. Normal travel keeps both inputs continuously held.
            releaseDirectionalInputs();
            client.options.forwardKey.setPressed(true);
            client.options.sprintKey.setPressed(true);
            int horizontalDistance = Math.max(Math.abs(node.getX() - player.getBlockX()),
                    Math.abs(node.getZ() - player.getBlockZ()));
            boolean jumpEdge = horizontalDistance > 1;
            boolean stairOrSlab = isStairOrSlab(node.down()) || isStairOrSlab(player.getBlockPos().down());
            boolean swimEdge = isWater(node) && (isWater(node.down()) || player.isSwimming()
                    || player.isSubmergedInWater() || player.isTouchingWater());
            if (swimEdge) {
                // Sprint is required to enter and maintain the swimming pose.
                status("swimming");
                client.options.jumpKey.setPressed(node.getY() >= player.getBlockY());
            } else if (stairOrSlab) {
                status("spam-jump (stairs)");
                // Stair/slab travel deliberately pulses space instead of holding it.
                client.options.jumpKey.setPressed(false);
                pressSpamJumpIfReady(player);
            } else if (jumpEdge) {
                // A gap edge is one indivisible movement: never let waypoint advancement
                // turn the player back toward a passed node during the airborne arc.
                status("sprint-jump");
                sprintJumpLanding = node.toImmutable();
                sprintJumpWasAirborne = !player.isOnGround();
                client.options.jumpKey.setPressed(true);
            } else if (!hasOpenJumpHeadroom(player.getBlockPos()) || !hasOpenJumpHeadroom(node)) {
                status("spam-jump");
                client.options.jumpKey.setPressed(false);
                pressSpamJumpIfReady(player);
            } else {
                // Continuous bunny-hopping is only appropriate in a fully open
                // corridor. A block in either jump headspace uses prompt pulses.
                status("sprint-jump");
                client.options.jumpKey.setPressed(true);
            }
        }
        scheduleDelay();
    }

    private void continueSprintJump(ClientPlayerEntity player) {
        Vec3d landingAim = new Vec3d(sprintJumpLanding.getX() + 0.5, player.getEyeY(),
                sprintJumpLanding.getZ() + 0.5);
        aim.aimAt(landingAim);
        aim.tick();
        releaseDirectionalInputs();
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(true);
        // Holding jump is intentional for a single gap-crossing arc; randomized pulses
        // are reserved for repeated grounded stair/step/head-bump jumps.
        client.options.jumpKey.setPressed(true);
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
        double beyondX = player.getX() - (node.getX() + 0.5);
        double beyondZ = player.getZ() - (node.getZ() + 0.5);
        return beyondX * edgeX + beyondZ * edgeZ >= 0.0;
    }

    private boolean handleTraversal(ClientPlayerEntity player, BlockPos node) {
        BlockPos support = node.down();
        boolean unsupportedUp = node.getX() == player.getBlockX()
                && node.getZ() == player.getBlockZ() && node.getY() > player.getBlockY()
                && !isWater(node) && !isWater(support) && isFallRisk(support);
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
                    && aim.isAimed() && GladeClient.tickBudget().hasBudgetRemaining()) {
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
        if (isWater(node) || isWater(support)) return false;
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
        Vec3d hit = Vec3d.ofCenter(anchor).add(side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        aim.aimAt(hit);
        aim.tick();
        boolean rateReady = !placementTarget.equals(lastBridgeTarget)
                || ticks - lastBridgePlaceTick >= 2;
        if (!rateReady || !aim.isAimed() || !GladeClient.tickBudget().hasBudgetRemaining()) return;
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
                && GladeClient.tickBudget().hasBudgetRemaining()) {
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
                || !aim.isAimed() || !GladeClient.tickBudget().hasBudgetRemaining()) return;
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

    private static boolean reached(ClientPlayerEntity player, BlockPos node) {
        double dx = player.getX() - node.getX() - 0.5, dz = player.getZ() - node.getZ() - 0.5;
        return dx * dx + dz * dz < NODE_DISTANCE_SQUARED && Math.abs(player.getY() - node.getY()) <= 0.75;
    }

    public void cancel() { finish(false, "Pathing cancelled"); _break(); }
    private void finish(boolean success, String detail) {
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(
                    (success ? "§aGlade §7» §f" : "§cGlade §7» §f") + detail), true);
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
    @Override public Set<Object> getMutexKeys() { return Set.of("glade-player-movement"); }
}
