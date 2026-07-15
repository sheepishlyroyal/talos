package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.talos.client.TalosClient;
import dev.talos.client.scan.BlockStatePredicate;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /talos look <yaw> <pitch>} — set the player's look angles, supports {@code ^} relative syntax.
 *
 * <p>Also supports selector-style targeting:</p>
 * <ul>
 *   <li>{@code /talos look block <id> [n]} aims at the Nth-closest matching block.</li>
 *   <li>{@code /talos look entity type <id>|tag <tag> [n]} aims at the Nth-closest matching
 *       entity (legacy, kept for back-compat).</li>
 *   <li>{@code /talos look <selector> [n]} aims at the Nth-closest match of a Minecraft-style
 *       target selector: {@code @e[...]}, {@code @a}, {@code @p} (nearest player excluding
 *       yourself) or {@code @s} (self). See {@link EntitySelector} for the supported subset of
 *       bracket arguments.</li>
 * </ul>
 */
final class LookCommand {
    /** Radius (in chunks) scanned for {@code /talos look block}, matching the client's view distance. */
    private static int defaultBlockRadius() {
        return Minecraft.getInstance().options.renderDistance().get();
    }

    /** Radius (in blocks) searched for {@code /talos look entity}. */
    private static final double ENTITY_SEARCH_RADIUS = 128.0;

    private LookCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        LocalPlayer player = source.getPlayer();

        float yaw = RelativeAngleArgumentType.resolve(context, "yaw", player.getYRot());
        float pitch = RelativeAngleArgumentType.resolve(context, "pitch", player.getXRot());
        pitch = Mth.clamp(pitch, -90.0F, 90.0F);

        aim(player, yaw, pitch);

        source.sendFeedback(Component.literal("Looking at yaw %.2f, pitch %.2f".formatted(yaw, pitch)));
        return 1;
    }

    static int executeBlock(CommandContext<FabricClientCommandSource> context, int n) throws CommandSyntaxException {
        FabricClientCommandSource source = context.getSource();
        BlockStatePredicate predicate = BlockStatePredicate.fromArgument(context, "blockPredicate");

        NthClosestBlockTask task = new NthClosestBlockTask(
                predicate, defaultBlockRadius(), n, (found, pos) ->
                        source.getClient().execute(() -> {
                            if (pos == null) {
                                source.sendError(Component.literal(
                                        "Index %d out of range: %d match(es) (0-based, -1 = furthest)"
                                                .formatted(n, found)));
                                return;
                            }
                            LocalPlayer player = source.getPlayer();
                            Vec3 center = Vec3.atCenterOf(pos);
                            aimAt(player, center);
                            source.sendFeedback(Component.literal("Looking at block #%d at %d, %d, %d"
                                    .formatted(n, pos.getX(), pos.getY(), pos.getZ())));
                        }));
        try {
            TalosClient.taskScheduler().addTask("look-block", task);
        } catch (IllegalStateException conflict) {
            source.sendError(Component.literal("A world scan is already running"));
            return 0;
        }
        source.sendFeedback(Component.literal("Scanning loaded chunks..."));
        return 1;
    }

    /** {@code /talos look coords <x> <y> <z>} — aims at the center of the given block position. */
    static int executeCoords(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        FabricClientCommandSource source = context.getSource();
        LocalPlayer player = source.getPlayer();
        aimAt(player, Vec3.atCenterOf(pos));
        source.sendFeedback(Component.literal("Looking at %d, %d, %d"
                .formatted(pos.getX(), pos.getY(), pos.getZ())));
        return 1;
    }

    /**
     * {@code /talos look direction <yaw> <pitch>} — aims at the center of the block hit by a
     * raycast along the given (possibly {@code ^}-relative) direction. The raycast itself is
     * performed by {@code TalosCommands}' shared {@code directionNode} helper.
     */
    static int executeDirection(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        return executeCoords(context, pos);
    }

    static int executeEntity(
            CommandContext<FabricClientCommandSource> context, Identifier entityTypeId, String tag, int n) {
        FabricClientCommandSource source = context.getSource();

        EntityType<?> entityType = null;
        if (entityTypeId != null) {
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(entityTypeId)) {
                source.sendError(Component.literal("Unknown entity type: " + entityTypeId));
                return 0;
            }
            entityType = BuiltInRegistries.ENTITY_TYPE.getValue(entityTypeId);
        }

        LocalPlayer player = source.getPlayer();
        Minecraft client = source.getClient();
        if (client.level == null) {
            source.sendError(Component.literal("No world loaded"));
            return 0;
        }

        EntityType<?> wantedType = entityType;
        AABB box = player.getBoundingBox().inflate(ENTITY_SEARCH_RADIUS);
        List<Entity> matches = client.level.getEntitiesOfClass(Entity.class, box, entity ->
                        entity != player
                                && entity.isAlive()
                                && (wantedType == null || entity.getType() == wantedType)
                                && (tag == null || entity.entityTags().contains(tag)))
                .stream()
                .sorted(Comparator.comparingDouble(player::distanceToSqr))
                .toList();

        Entity target = Indexed.select(matches, n);
        if (target == null) {
            source.sendError(Component.literal("Index %d out of range: %d matching entit%s (%s)"
                    .formatted(n, matches.size(), matches.size() == 1 ? "y" : "ies",
                            Indexed.rangeHint(matches.size()))));
            return 0;
        }
        aimAt(player, target.getEyePosition());

        Identifier targetTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        source.sendFeedback(Component.literal("Looking at %s #%d at %.0f, %.0f, %.0f"
                .formatted(targetTypeId, n, target.getX(), target.getY(), target.getZ())));
        return 1;
    }

    /**
     * Handles {@code /talos look <selector> [n]} for Minecraft-style target selectors:
     * {@code @e[...]}, {@code @a}, {@code @p} (nearest player excluding the caller) and
     * {@code @s} (self).
     */
    static int executeSelector(CommandContext<FabricClientCommandSource> context, String token, int n) {
        FabricClientCommandSource source = context.getSource();

        String[] error = new String[1];
        EntitySelector selector = EntitySelector.parse(token, error);
        if (selector == null) {
            source.sendError(Component.literal(error[0]));
            return 0;
        }

        LocalPlayer player = source.getPlayer();
        Minecraft client = source.getClient();
        if (client.level == null) {
            source.sendError(Component.literal("No world loaded"));
            return 0;
        }

        List<Entity> candidates = selector.select(client, false).stream()
                .filter(Entity::isAlive)
                .toList();
        if (selector.kind() == EntitySelector.Kind.SELF && !candidates.isEmpty()) {
            source.sendFeedback(Component.literal("@s is you — no aim change"));
            return 1;
        }
        return aimAtNth(source, player, candidates, n,
                selector.kind() == EntitySelector.Kind.PLAYERS_ALL
                        || selector.kind() == EntitySelector.Kind.PLAYER_NEAREST
                        || selector.kind() == EntitySelector.Kind.PLAYER_RANDOM
                        ? "player" : "entity");
    }

    /** Filters by distance, sorts (nearest by default, furthest if requested), applies {@code limit=} then picks the Nth match. */
    private static int aimAtNth(FabricClientCommandSource source, LocalPlayer player,
            List<Entity> candidates, int n, String noun) {
        Entity target = Indexed.select(candidates, n);
        if (target == null) {
            source.sendError(Component.literal("Index %d out of range: %d matching %s%s (%s)"
                    .formatted(n, candidates.size(), noun, candidates.size() == 1 ? "" : "s",
                            Indexed.rangeHint(candidates.size()))));
            return 0;
        }
        aimAt(player, target.getEyePosition());

        Component label = target instanceof Player playerEntity
                ? playerEntity.getName()
                : Component.literal(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
        source.sendFeedback(Component.literal("Looking at ")
                .append(label)
                .append(Component.literal(" #%d at %.0f, %.0f, %.0f"
                        .formatted(n, target.getX(), target.getY(), target.getZ()))));
        return 1;
    }

    /** Runs a humanized cube-aim session toward {@code target} (fast->slow, red-X, path). */
    private static void aimAt(LocalPlayer player, Vec3 target) {
        dev.talos.client.action.AimController.startTask(
                net.minecraft.client.Minecraft.getInstance(), target,
                Double.doubleToLongBits(target.x * 31.0 + target.z) ^ player.tickCount);
    }

    private static void aim(LocalPlayer player, float yaw, float pitch) {
        dev.talos.client.action.AimController.startTask(
                net.minecraft.client.Minecraft.getInstance(), yaw, pitch,
                Float.floatToIntBits(yaw) * 31L ^ Float.floatToIntBits(pitch) ^ player.tickCount);
    }
}
