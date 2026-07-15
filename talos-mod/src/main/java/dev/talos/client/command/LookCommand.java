package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.talos.client.TalosClient;
import dev.talos.client.scan.BlockStatePredicate;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

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
        return MinecraftClient.getInstance().options.getViewDistance().getValue();
    }

    /** Radius (in blocks) searched for {@code /talos look entity}. */
    private static final double ENTITY_SEARCH_RADIUS = 128.0;

    private LookCommand() {
    }

    static int execute(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        ClientPlayerEntity player = source.getPlayer();

        float yaw = RelativeAngleArgumentType.resolve(context, "yaw", player.getYaw());
        float pitch = RelativeAngleArgumentType.resolve(context, "pitch", player.getPitch());
        pitch = MathHelper.clamp(pitch, -90.0F, 90.0F);

        aim(player, yaw, pitch);

        source.sendFeedback(Text.literal("Looking at yaw %.2f, pitch %.2f".formatted(yaw, pitch)));
        return 1;
    }

    static int executeBlock(CommandContext<FabricClientCommandSource> context, int n) throws CommandSyntaxException {
        FabricClientCommandSource source = context.getSource();
        BlockStatePredicate predicate = BlockStatePredicate.fromArgument(context, "blockPredicate");

        NthClosestBlockTask task = new NthClosestBlockTask(
                predicate, defaultBlockRadius(), n, (found, pos) ->
                        source.getClient().execute(() -> {
                            if (pos == null) {
                                source.sendError(Text.literal(
                                        "Index %d out of range: %d match(es) (0-based, -1 = furthest)"
                                                .formatted(n, found)));
                                return;
                            }
                            ClientPlayerEntity player = source.getPlayer();
                            Vec3d center = Vec3d.ofCenter(pos);
                            aimAt(player, center);
                            source.sendFeedback(Text.literal("Looking at block #%d at %d, %d, %d"
                                    .formatted(n, pos.getX(), pos.getY(), pos.getZ())));
                        }));
        try {
            TalosClient.taskScheduler().addTask("look-block", task);
        } catch (IllegalStateException conflict) {
            source.sendError(Text.literal("A world scan is already running"));
            return 0;
        }
        source.sendFeedback(Text.literal("Scanning loaded chunks..."));
        return 1;
    }

    /** {@code /talos look coords <x> <y> <z>} — aims at the center of the given block position. */
    static int executeCoords(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        FabricClientCommandSource source = context.getSource();
        ClientPlayerEntity player = source.getPlayer();
        aimAt(player, Vec3d.ofCenter(pos));
        source.sendFeedback(Text.literal("Looking at %d, %d, %d"
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
            if (!Registries.ENTITY_TYPE.containsId(entityTypeId)) {
                source.sendError(Text.literal("Unknown entity type: " + entityTypeId));
                return 0;
            }
            entityType = Registries.ENTITY_TYPE.get(entityTypeId);
        }

        ClientPlayerEntity player = source.getPlayer();
        MinecraftClient client = source.getClient();
        if (client.world == null) {
            source.sendError(Text.literal("No world loaded"));
            return 0;
        }

        EntityType<?> wantedType = entityType;
        Box box = player.getBoundingBox().expand(ENTITY_SEARCH_RADIUS);
        List<Entity> matches = client.world.getEntitiesByClass(Entity.class, box, entity ->
                        entity != player
                                && entity.isAlive()
                                && (wantedType == null || entity.getType() == wantedType)
                                && (tag == null || entity.getCommandTags().contains(tag)))
                .stream()
                .sorted(Comparator.comparingDouble(player::squaredDistanceTo))
                .toList();

        Entity target = Indexed.select(matches, n);
        if (target == null) {
            source.sendError(Text.literal("Index %d out of range: %d matching entit%s (%s)"
                    .formatted(n, matches.size(), matches.size() == 1 ? "y" : "ies",
                            Indexed.rangeHint(matches.size()))));
            return 0;
        }
        aimAt(player, target.getEyePos());

        Identifier targetTypeId = Registries.ENTITY_TYPE.getId(target.getType());
        source.sendFeedback(Text.literal("Looking at %s #%d at %.0f, %.0f, %.0f"
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
            source.sendError(Text.literal(error[0]));
            return 0;
        }

        ClientPlayerEntity player = source.getPlayer();
        MinecraftClient client = source.getClient();
        if (client.world == null) {
            source.sendError(Text.literal("No world loaded"));
            return 0;
        }

        List<Entity> candidates = selector.select(client, false).stream()
                .filter(Entity::isAlive)
                .toList();
        if (selector.kind() == EntitySelector.Kind.SELF && !candidates.isEmpty()) {
            source.sendFeedback(Text.literal("@s is you — no aim change"));
            return 1;
        }
        return aimAtNth(source, player, candidates, n,
                selector.kind() == EntitySelector.Kind.PLAYERS_ALL
                        || selector.kind() == EntitySelector.Kind.PLAYER_NEAREST
                        || selector.kind() == EntitySelector.Kind.PLAYER_RANDOM
                        ? "player" : "entity");
    }

    /** Filters by distance, sorts (nearest by default, furthest if requested), applies {@code limit=} then picks the Nth match. */
    private static int aimAtNth(FabricClientCommandSource source, ClientPlayerEntity player,
            List<Entity> candidates, int n, String noun) {
        Entity target = Indexed.select(candidates, n);
        if (target == null) {
            source.sendError(Text.literal("Index %d out of range: %d matching %s%s (%s)"
                    .formatted(n, candidates.size(), noun, candidates.size() == 1 ? "" : "s",
                            Indexed.rangeHint(candidates.size()))));
            return 0;
        }
        aimAt(player, target.getEyePos());

        Text label = target instanceof PlayerEntity playerEntity
                ? playerEntity.getName()
                : Text.literal(Registries.ENTITY_TYPE.getId(target.getType()).toString());
        source.sendFeedback(Text.literal("Looking at ")
                .append(label)
                .append(Text.literal(" #%d at %.0f, %.0f, %.0f"
                        .formatted(n, target.getX(), target.getY(), target.getZ()))));
        return 1;
    }

    /** Runs a humanized cube-aim session toward {@code target} (fast->slow, red-X, path). */
    private static void aimAt(ClientPlayerEntity player, Vec3d target) {
        dev.talos.client.action.AimController.startTask(
                net.minecraft.client.MinecraftClient.getInstance(), target,
                Double.doubleToLongBits(target.x * 31.0 + target.z) ^ player.age);
    }

    private static void aim(ClientPlayerEntity player, float yaw, float pitch) {
        dev.talos.client.action.AimController.startTask(
                net.minecraft.client.MinecraftClient.getInstance(), yaw, pitch,
                Float.floatToIntBits(yaw) * 31L ^ Float.floatToIntBits(pitch) ^ player.age);
    }
}
