package dev.talos.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.talos.client.TalosClient;
import dev.talos.client.action.AimController;
import dev.talos.client.task.TalosTask;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
        return ClientCommandManager.literal("track")
                .executes(context -> start(context.getSource(), "@p", null, 0))
                .then(ClientCommandManager.literal("stop").executes(context -> {
                    TrackTask task = active;
                    if (task == null) {
                        context.getSource().sendError(Text.literal("Not tracking anything"));
                        return 0;
                    }
                    task.stop();
                    context.getSource().sendFeedback(Text.literal("Tracking stopped"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("block")
                        .then(ClientCommandManager.argument("block", IdArgumentType.blockId())
                                .executes(context -> start(context.getSource(), null,
                                        StringArgumentType.getString(context, "block"), 0))
                                .then(ClientCommandManager.argument("index",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(context -> start(context.getSource(), null,
                                                StringArgumentType.getString(context, "block"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(context, "index"))))))
                .then(ClientCommandManager.argument("selector", SelectorArgumentType.selector())
                        .executes(context -> start(context.getSource(),
                                StringArgumentType.getString(context, "selector"), null, 0))
                        .then(ClientCommandManager.argument("index",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(context -> start(context.getSource(),
                                        StringArgumentType.getString(context, "selector"), null,
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(context, "index")))));
    }

    private static int start(FabricClientCommandSource source, String selectorToken,
            String blockId, int index) {
        MinecraftClient client = source.getClient();
        if (client.player == null || client.world == null) {
            source.sendError(Text.literal("No world is loaded"));
            return 0;
        }
        EntitySelector selector = null;
        Block block = null;
        if (blockId != null) {
            Identifier id = Identifier.tryParse(blockId.contains(":")
                    ? blockId : "minecraft:" + blockId);
            if (id == null || !Registries.BLOCK.containsId(id)) {
                source.sendError(Text.literal("Unknown block: " + blockId));
                return 0;
            }
            block = Registries.BLOCK.get(id);
        } else {
            String[] error = new String[1];
            selector = EntitySelector.parse(selectorToken, error);
            if (selector == null) {
                source.sendError(Text.literal(error[0]));
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
            source.sendError(Text.literal(exception.getMessage()));
            return 0;
        }
        active = task;
        source.sendFeedback(Text.literal("Tracking " + (blockId != null
                ? "block " + blockId : selectorToken)
                + (index != 0 ? " [" + index + "]" : "") + " — /talos track stop to end"));
        return 1;
    }

    private static final class TrackTask extends TalosTask {
        private static final int BLOCK_RESCAN_TICKS = 20;
        private static final int BLOCK_SCAN_RADIUS = 32;
        private static final int LOST_TICKS = 60;

        private final MinecraftClient client;
        private final EntitySelector selector;
        private final Block block;
        private final String description;
        private final AimController aim;
        private final int index;
        private BlockPos blockTarget;
        private int ticks;
        private int lastSeenTick;
        private boolean stopped;

        TrackTask(MinecraftClient client, EntitySelector selector, Block block,
                String description, int index) {
            this.client = client;
            this.selector = selector;
            this.block = block;
            this.description = description;
            this.index = index;
            this.aim = new AimController(client, null, null,
                    description.hashCode() * 31L + (client.player == null ? 0 : client.player.age));
        }

        void stop() { stopped = true; }

        @Override public void initialize() { }
        @Override public boolean condition() {
            return !stopped && client.player != null && client.world != null
                    && ticks - lastSeenTick <= LOST_TICKS;
        }
        @Override public void increment() { ticks++; }

        @Override public void body() {
            Vec3d target = block != null ? blockTarget() : entityTarget();
            if (target != null) {
                lastSeenTick = ticks;
                aim.aimAt(target);
                aim.tick();
            }
            scheduleDelay();
        }

        private Vec3d entityTarget() {
            var player = client.player;
            if (selector.kind() == EntitySelector.Kind.SELF) return null; // nothing to track
            java.util.List<Entity> matches = selector.select(client, true);
            Entity target = Indexed.select(matches, index);
            return target == null ? null : target.getBoundingBox().getCenter();
        }

        private Vec3d blockTarget() {
            if (blockTarget != null && ticks % BLOCK_RESCAN_TICKS != 0
                    && client.world.getBlockState(blockTarget).getBlock() == block) {
                return Vec3d.ofCenter(blockTarget);
            }
            BlockPos center = client.player.getBlockPos();
            java.util.List<BlockPos> matches = new java.util.ArrayList<>();
            for (BlockPos pos : BlockPos.iterate(
                    center.add(-BLOCK_SCAN_RADIUS, -BLOCK_SCAN_RADIUS, -BLOCK_SCAN_RADIUS),
                    center.add(BLOCK_SCAN_RADIUS, BLOCK_SCAN_RADIUS, BLOCK_SCAN_RADIUS))) {
                if (client.world.getBlockState(pos).getBlock() == block) {
                    matches.add(pos.toImmutable());
                }
            }
            matches.sort(java.util.Comparator.comparingDouble(pos -> pos.getSquaredDistance(center)));
            blockTarget = Indexed.select(matches, index);
            return blockTarget == null ? null : Vec3d.ofCenter(blockTarget);
        }

        @Override public void onCompleted() {
            if (active == this) active = null;
            if (client.player != null) client.player.sendMessage(
                    Text.literal("§bTalos §7» §ftracking '" + description + "' ended"), true);
        }

        @Override public Set<Object> getMutexKeys() { return Set.of("talos-aim"); }
    }
}
