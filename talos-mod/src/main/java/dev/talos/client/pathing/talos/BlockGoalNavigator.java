package dev.talos.client.pathing.talos;

import dev.talos.client.TalosClient;
import dev.talos.client.pathing.GoalBlock;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.pathing.PathingOptions;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * Drives {@code /talos goto block <id>}: scan outward for the nearest matching block, path
 * to it, and — when that specific block turns out unreachable — blacklist it and try the
 * next-nearest candidate instead of giving up. A cancelled or superseded goto is terminal
 * (the user changed their mind); only genuine path failures trigger a re-pick.
 */
public final class BlockGoalNavigator {
    /** How many distinct candidate blocks a single command may burn through. */
    private static final int MAX_CANDIDATES = 5;

    private BlockGoalNavigator() {}

    /** Call on the client thread. The future completes with the final attempt's result. */
    public static CompletableFuture<PathResult> navigate(Minecraft client, String blockId,
                                                         int radius) {
        CompletableFuture<PathResult> outcome = new CompletableFuture<>();
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(blockId);
        if (client.player == null || client.level == null) {
            outcome.complete(new PathResult(false, "No client world or player is loaded"));
        } else if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            outcome.complete(new PathResult(false, "Unknown block: " + blockId));
        } else {
            attempt(client, BuiltInRegistries.BLOCK.getValue(id), blockId, radius, new HashSet<>(), 1, outcome);
        }
        return outcome;
    }

    private static void attempt(Minecraft client, Block block, String blockId, int radius,
                                Set<BlockPos> tried, int attemptNo,
                                CompletableFuture<PathResult> outcome) {
        if (client.player == null || client.level == null) {
            outcome.complete(new PathResult(false, "World unloaded"));
            return;
        }
        NearestBlockScan scan = new NearestBlockScan(
                client.player.blockPosition(), radius, block, tried);
        TalosClient.taskScheduler().forceAddTask("goto-block-scan", scan);
        scan.future.whenComplete((pos, scanError) -> client.execute(() -> {
            if (scanError != null) {
                outcome.complete(new PathResult(false, "Scan failed: " + scanError.getMessage()));
                return;
            }
            if (pos == null) {
                outcome.complete(new PathResult(false, attemptNo == 1
                        ? "No " + blockId + " within " + radius + " blocks"
                        : "No reachable " + blockId + " left (" + tried.size() + " candidates tried)"));
                return;
            }
            tried.add(pos);
            TalosClient.pathingEngine()
                    .goTo(new GoalBlock(pos.getX(), pos.getY(), pos.getZ()), PathingOptions.DEFAULT)
                    .whenComplete((result, error) -> client.execute(() -> {
                        if (error != null) {
                            outcome.complete(new PathResult(false,
                                    "Pathing failed: " + error.getMessage()));
                            return;
                        }
                        if (result.successful()) { outcome.complete(result); return; }
                        String detail = String.valueOf(result.detail());
                        boolean terminal = detail.contains("cancelled")
                                || detail.contains("Superseded") || detail.contains("unloaded");
                        if (terminal || attemptNo >= MAX_CANDIDATES) {
                            outcome.complete(result);
                            return;
                        }
                        attempt(client, block, blockId, radius, tried, attemptNo + 1, outcome);
                    }));
        }));
    }

    /** Budgeted outward scan for the nearest matching block not yet blacklisted. */
    private static final class NearestBlockScan extends SimpleTask {
        private final Iterator<BlockPos> positions;
        private final Block block;
        private final Set<BlockPos> excluded;
        final CompletableFuture<BlockPos> future = new CompletableFuture<>();

        private NearestBlockScan(BlockPos center, int radius, Block block, Set<BlockPos> excluded) {
            this.positions = BlockPos.withinManhattan(center, radius, radius, radius).iterator();
            this.block = block;
            this.excluded = excluded;
        }

        @Override public void initialize() {}
        @Override public boolean condition() { return positions.hasNext() && !future.isDone(); }

        @Override
        protected void onTick() {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || !client.isSameThread()) { _break(); return; }
            while (positions.hasNext() && TalosClient.tickBudget().hasBudgetRemaining()) {
                BlockPos pos = positions.next();
                if (client.level.getBlockState(pos).is(block) && !excluded.contains(pos)) {
                    future.complete(pos.immutable());
                    _break();
                    return;
                }
            }
        }

        @Override public void onCompleted() { if (!future.isDone()) future.complete(null); }
        @Override public Set<Object> getMutexKeys() { return Set.of(ScanTask.INTENSIVE_MUTEX); }
    }
}
