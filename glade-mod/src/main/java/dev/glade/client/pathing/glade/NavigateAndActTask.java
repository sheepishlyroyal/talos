package dev.glade.client.pathing.glade;

import dev.glade.client.GladeClient;
import dev.glade.client.action.AimController;
import dev.glade.client.pathing.PathResult;
import dev.glade.client.task.GladeTask;
import java.util.List;
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
import org.jetbrains.annotations.Nullable;

/** One cooperative tick loop for waypoint movement, humanized looking, and route actions. */
public final class NavigateAndActTask extends GladeTask {
    public interface RouteAction {
        /** Current world-space target, or null when there is no target to act on. */
        @Nullable Vec3d target();
        double reach();
        /** Performs at most one tick of work; true means the action is complete. */
        boolean perform(MinecraftClient client);
    }

    private static final double NODE_DISTANCE_SQUARED = 0.36;
    private final MinecraftClient client;
    private final List<BlockPos> nodes;
    private final Predicate<BlockPos> goal;
    private final CompletableFuture<PathResult> future;
    private final @Nullable RouteAction action;
    private final AimController aim;
    private int index;
    private int ticks;
    private BlockPos breaking;

    public NavigateAndActTask(MinecraftClient client, List<BlockPos> nodes,
                              Predicate<BlockPos> goal, @Nullable RouteAction action,
                              CompletableFuture<PathResult> future) {
        this.client = client;
        this.nodes = List.copyOf(nodes);
        this.goal = goal;
        this.action = action;
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
        if (goal.test(player.getBlockPos())) { finish(true, "Arrived"); return; }
        if (ticks >= 60 * 20) { finish(false, "Navigation timed out after 60 seconds"); return; }
        while (index < nodes.size() && reached(player, nodes.get(index))) index++;
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
            releaseInputs();
            client.options.forwardKey.setPressed(true);
            if (node.getY() > player.getBlockY() && player.isOnGround()) client.options.jumpKey.setPressed(true);
            if (index + 1 < nodes.size() && node.getY() == nodes.get(index + 1).getY())
                client.options.sprintKey.setPressed(true);
        }
        scheduleDelay();
    }

    private boolean handleTraversal(ClientPlayerEntity player, BlockPos node) {
        var state = client.world.getBlockState(node);
        if (!state.getCollisionShape(client.world, node).isEmpty()) {
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
        BlockPos support = node.down();
        if (client.world.getBlockState(support).getCollisionShape(client.world, support).isEmpty()) {
            releaseInputs();
            int blockSlot = -1;
            for (int i = 0; i < 9; i++) if (player.getInventory().getStack(i).getItem() instanceof BlockItem) {
                blockSlot = i; break;
            }
            if (blockSlot < 0) { finish(false, "Ran out of bridge blocks"); return true; }
            BlockPos anchor = player.getBlockPos().down();
            int dx = Integer.compare(node.getX(), player.getBlockX());
            int dz = Integer.compare(node.getZ(), player.getBlockZ());
            boolean pillar = dx == 0 && dz == 0 && node.getY() > player.getBlockY();
            Direction side = pillar ? Direction.UP : dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST
                    : dz > 0 ? Direction.SOUTH : Direction.NORTH;
            aim.aimAt(Vec3d.ofCenter(anchor).add(side.getOffsetX() * 0.5,
                    side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5));
            aim.tick();
            if (aim.isAimed() && GladeClient.tickBudget().hasBudgetRemaining()) {
                player.getInventory().setSelectedSlot(blockSlot);
                if (pillar && player.isOnGround()) client.options.jumpKey.setPressed(true);
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(anchor), side, anchor, false));
                player.swingHand(Hand.MAIN_HAND);
            }
            return true;
        }
        return false;
    }

    private static boolean reached(ClientPlayerEntity player, BlockPos node) {
        double dx = player.getX() - node.getX() - 0.5, dz = player.getZ() - node.getZ() - 0.5;
        return dx * dx + dz * dz < NODE_DISTANCE_SQUARED && Math.abs(player.getY() - node.getY()) <= 0.75;
    }

    public void cancel() { finish(false, "Pathing cancelled"); _break(); }
    private void finish(boolean success, String detail) { releaseInputs(); future.complete(new PathResult(success, detail)); }
    private void releaseInputs() {
        client.options.forwardKey.setPressed(false); client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false); client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false); client.options.sprintKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }
    @Override public void onCompleted() {
        releaseInputs();
        if (!future.isDone()) future.complete(new PathResult(false, "Navigation was interrupted"));
    }
    @Override public Set<Object> getMutexKeys() { return Set.of("glade-player-movement"); }
}
