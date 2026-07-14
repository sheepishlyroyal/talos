package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.talos.client.TalosClient;
import dev.talos.client.action.ActionResult;
import dev.talos.client.action.BreakBlockAction;
import dev.talos.client.action.KillEntityAction;
import dev.talos.client.action.PlaceBlockAction;
import dev.talos.client.scan.BlockStatePredicate;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;

final class ActionCommand {
    private ActionCommand() {
    }

    static int mine(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        BreakBlockAction action = new BreakBlockAction(pos);
        return schedule(context, "mine", action, action.future());
    }

    /**
     * {@code /talos mine block <id> [n]} — mines the Nth-closest block matching {@code id}
     * (default {@code n} = 1), mirroring {@code /talos look block <id> [n]}'s targeting via
     * {@link NthClosestBlockTask}. The block id argument suggests real block ids (e.g.
     * {@code minecraft:stone}) via {@link BlockStatePredicate#argument}.
     */
    static int mineBlock(CommandContext<FabricClientCommandSource> context, int n) throws CommandSyntaxException {
        FabricClientCommandSource source = context.getSource();
        BlockStatePredicate predicate = BlockStatePredicate.fromArgument(context, "blockPredicate");

        int radius = Minecraft.getInstance().options.renderDistance().get();
        NthClosestBlockTask task = new NthClosestBlockTask(
                predicate, radius, n, (found, pos) ->
                        source.getClient().execute(() -> {
                            if (pos == null) {
                                source.sendError(Component.literal(
                                        "Index %d out of range: %d match(es) (0-based, -1 = furthest)"
                                                .formatted(n, found)));
                                return;
                            }
                            mine(context, pos);
                        }));
        try {
            TalosClient.taskScheduler().addTask("mine-block", task);
        } catch (IllegalStateException conflict) {
            source.sendError(Component.literal("A world scan is already running"));
            return 0;
        }
        source.sendFeedback(Component.literal("Scanning loaded chunks..."));
        return 1;
    }

    static int place(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        PlaceBlockAction action = new PlaceBlockAction(pos);
        return schedule(context, "place", action, action.future());
    }

    static int killNearest(CommandContext<FabricClientCommandSource> context, double radius) {
        FabricClientCommandSource source = context.getSource();
        var player = source.getPlayer();
        var world = source.getLevel();
        Entity nearest = world.getEntitiesOfClass(Monster.class,
                        player.getBoundingBox().inflate(radius),
                        entity -> entity.isAlive())
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (nearest == null) {
            source.sendError(Component.literal("No hostile entity found within " + radius + " blocks"));
            return 0;
        }
        KillEntityAction action = new KillEntityAction(nearest);
        return schedule(context, "kill", action, action.future());
    }

    private static int schedule(CommandContext<FabricClientCommandSource> context, String name,
                                dev.talos.client.task.TalosTask task,
                                CompletableFuture<ActionResult> future) {
        FabricClientCommandSource source = context.getSource();
        try {
            String taskName = TalosClient.taskScheduler().addTask(name, task);
            source.sendFeedback(Component.literal("Started " + taskName));
            future.whenComplete((result, error) -> source.getClient().execute(() -> {
                if (error != null) {
                    source.sendError(Component.literal(name + " failed: " + error.getMessage()));
                } else if (result.success()) {
                    source.sendFeedback(Component.literal(result.message()));
                } else {
                    source.sendError(Component.literal(result.message()));
                }
            }));
            return 1;
        } catch (IllegalStateException exception) {
            source.sendError(Component.literal("Cannot start " + name + ": " + exception.getMessage()));
            return 0;
        }
    }
}
