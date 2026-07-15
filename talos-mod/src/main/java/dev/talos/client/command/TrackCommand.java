package dev.talos.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.talos.client.TalosClient;
import dev.talos.client.action.AimController;
import dev.talos.client.task.TalosTask;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Continuous humanized tracking with the cube-aim controller:
 * {@code /talos track <@e[...]|@a|@p|@s>} follows the nearest matching entity (default
 * {@code @p}); {@code /talos track block <id>} follows the nearest matching block;
 * {@code /talos track stop} ends it. Moving targets keep the same aim session (the cube
 * follows), so the crosshair trails them with the fast/slow human profile, never a snap.
 */
public final class TrackCommand {
    private static volatile TrackTask active;

    private TrackCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        return ClientCommands.literal("track")
                .executes(context -> start(context.getSource(), "@p", null, 0))
                .then(ClientCommands.literal("stop").executes(context -> {
                    TrackTask task = active;
                    if (task == null) {
                        context.getSource().sendError(Component.literal("Not tracking anything"));
                        return 0;
                    }
                    task.stop();
                    context.getSource().sendFeedback(Component.literal("Tracking stopped"));
                    return 1;
                }))
                .then(ClientCommands.literal("block")
                        .then(ClientCommands.argument("block", IdArgumentType.blockId())
                                .executes(context -> start(context.getSource(), null,
                                        StringArgumentType.getString(context, "block"), 0))
                                .then(ClientCommands.argument("index",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(context -> start(context.getSource(), null,
                                                StringArgumentType.getString(context, "block"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(context, "index"))))))
                .then(ClientCommands.argument("selector", SelectorArgumentType.selector())
                        .executes(context -> start(context.getSource(),
                                StringArgumentType.getString(context, "selector"), null, 0))
                        .then(ClientCommands.argument("index",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(context -> start(context.getSource(),
                                        StringArgumentType.getString(context, "selector"), null,
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(context, "index")))));
    }

    private static int start(FabricClientCommandSource source, String selectorToken,
            String blockId, int index) {
        Minecraft client = source.getClient();
        if (client.player == null || client.level == null) {
            source.sendError(Component.literal("No world is loaded"));
            return 0;
        }
        EntitySelector selector = null;
        Block block = null;
        if (blockId != null) {
            Identifier id = Identifier.tryParse(blockId.contains(":")
                    ? blockId : "minecraft:" + blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                source.sendError(Component.literal("Unknown block: " + blockId));
                return 0;
            }
            block = BuiltInRegistries.BLOCK.getValue(id);
        } else {
            String[] error = new String[1];
            selector = EntitySelector.parse(selectorToken, error);
            if (selector == null) {
                source.sendError(Component.literal(error[0]));
                return 0;
            }
        }
        TrackTask previous = active;
        if (previous != null) previous.stop();
        TrackTask task = new TrackTask(client, selector, block,
                blockId != null ? blockId : selectorToken, index);
        try {
            TalosClient.taskScheduler().addTask("talos-track", task);
        } catch (RuntimeException exception) {
            source.sendError(Component.literal(exception.getMessage()));
            return 0;
        }
        active = task;
        source.sendFeedback(Component.literal("Tracking " + (blockId != null
                ? "block " + blockId : selectorToken)
                + (index != 0 ? " [" + index + "]" : "") + " — /talos track stop to end"));
        return 1;
    }

    private static final class TrackTask extends TalosTask {
        private static final int BLOCK_RESCAN_TICKS = 20;
        private static final int BLOCK_SCAN_RADIUS = 32;
        private static final int LOST_TICKS = 60;

        private final Minecraft client;
        private final EntitySelector selector;
        private final Block block;
        private final String description;
        private final AimController aim;
        private final int index;
        private BlockPos blockTarget;
        private int ticks;
        private int lastSeenTick;
        private boolean stopped;

        TrackTask(Minecraft client, EntitySelector selector, Block block,
                String description, int index) {
            this.client = client;
            this.selector = selector;
            this.block = block;
            this.description = description;
            this.index = index;
            this.aim = new AimController(client, null, null,
                    description.hashCode() * 31L + (client.player == null ? 0 : client.player.tickCount));
        }

        void stop() { stopped = true; }

        @Override public void initialize() { }
        @Override public boolean condition() {
            return !stopped && client.player != null && client.level != null
                    && ticks - lastSeenTick <= LOST_TICKS;
        }
        @Override public void increment() { ticks++; }

        @Override public void body() {
            Vec3 target = block != null ? blockTarget() : entityTarget();
            if (target != null) {
                lastSeenTick = ticks;
                aim.aimAt(target);
                aim.tick();
            }
            scheduleDelay();
        }

        private Vec3 entityTarget() {
            var player = client.player;
            if (selector.kind() == EntitySelector.Kind.SELF) return null; // nothing to track
            java.util.List<Entity> matches = selector.select(client, true);
            Entity target = Indexed.select(matches, index);
            return target == null ? null : target.getBoundingBox().getCenter();
        }

        private Vec3 blockTarget() {
            if (blockTarget != null && ticks % BLOCK_RESCAN_TICKS != 0
                    && client.level.getBlockState(blockTarget).getBlock() == block) {
                return Vec3.atCenterOf(blockTarget);
            }
            BlockPos center = client.player.blockPosition();
            java.util.List<BlockPos> matches = new java.util.ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-BLOCK_SCAN_RADIUS, -BLOCK_SCAN_RADIUS, -BLOCK_SCAN_RADIUS),
                    center.offset(BLOCK_SCAN_RADIUS, BLOCK_SCAN_RADIUS, BLOCK_SCAN_RADIUS))) {
                if (client.level.getBlockState(pos).getBlock() == block) {
                    matches.add(pos.immutable());
                }
            }
            matches.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(center)));
            blockTarget = Indexed.select(matches, index);
            return blockTarget == null ? null : Vec3.atCenterOf(blockTarget);
        }

        @Override public void onCompleted() {
            if (active == this) active = null;
            if (client.player != null) client.player.sendOverlayMessage(
                    Component.literal("§bTalos §7» §ftracking '" + description + "' ended"));
        }

        @Override public Set<Object> getMutexKeys() { return Set.of("talos-aim"); }
    }
}
