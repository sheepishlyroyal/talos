package dev.glade.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.glade.client.GladeClient;
import dev.glade.client.action.ActionResult;
import dev.glade.client.action.BreakBlockAction;
import dev.glade.client.action.KillEntityAction;
import dev.glade.client.action.PlaceBlockAction;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

final class ActionCommand {
    private ActionCommand() {
    }

    static int mine(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        BreakBlockAction action = new BreakBlockAction(pos);
        return schedule(context, "mine", action, action.future());
    }

    static int place(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        PlaceBlockAction action = new PlaceBlockAction(pos);
        return schedule(context, "place", action, action.future());
    }

    static int killNearest(CommandContext<FabricClientCommandSource> context, double radius) {
        FabricClientCommandSource source = context.getSource();
        var player = source.getPlayer();
        var world = source.getWorld();
        Entity nearest = world.getEntitiesByClass(HostileEntity.class,
                        player.getBoundingBox().expand(radius),
                        entity -> entity.isAlive())
                .stream()
                .min(Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
        if (nearest == null) {
            source.sendError(Text.literal("No hostile entity found within " + radius + " blocks"));
            return 0;
        }
        KillEntityAction action = new KillEntityAction(nearest);
        return schedule(context, "kill", action, action.future());
    }

    private static int schedule(CommandContext<FabricClientCommandSource> context, String name,
                                dev.glade.client.task.GladeTask task,
                                CompletableFuture<ActionResult> future) {
        FabricClientCommandSource source = context.getSource();
        try {
            String taskName = GladeClient.taskScheduler().addTask(name, task);
            source.sendFeedback(Text.literal("Started " + taskName));
            future.whenComplete((result, error) -> source.getClient().execute(() -> {
                if (error != null) {
                    source.sendError(Text.literal(name + " failed: " + error.getMessage()));
                } else if (result.success()) {
                    source.sendFeedback(Text.literal(result.message()));
                } else {
                    source.sendError(Text.literal(result.message()));
                }
            }));
            return 1;
        } catch (IllegalStateException exception) {
            source.sendError(Text.literal("Cannot start " + name + ": " + exception.getMessage()));
            return 0;
        }
    }
}
