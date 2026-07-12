package dev.glade.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.glade.client.GladeClient;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

/**
 * {@code /glade look <yaw> <pitch>} — set the player's look angles, supports {@code ^} relative syntax.
 *
 * <p>Also supports selector-style targeting:</p>
 * <ul>
 *   <li>{@code /glade look block <id> [n]} aims at the Nth-closest matching block.</li>
 *   <li>{@code /glade look entity type <id>|tag <tag> [n]} aims at the Nth-closest matching
 *       entity (legacy, kept for back-compat).</li>
 *   <li>{@code /glade look <selector> [n]} aims at the Nth-closest match of a Minecraft-style
 *       target selector: {@code @e[...]}, {@code @a}, {@code @p} (nearest player excluding
 *       yourself) or {@code @s} (self). See {@link EntitySelector} for the supported subset of
 *       bracket arguments.</li>
 * </ul>
 */
final class LookCommand {
    /** Radius (in chunks) scanned for {@code /glade look block}, matching the client's view distance. */
    private static int defaultBlockRadius() {
        return MinecraftClient.getInstance().options.getViewDistance().getValue();
    }

    /** Radius (in blocks) searched for {@code /glade look entity}. */
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

    static int executeBlock(CommandContext<FabricClientCommandSource> context, int n) {
        FabricClientCommandSource source = context.getSource();
        Identifier blockId = context.getArgument("blockId", Identifier.class);
        if (!Registries.BLOCK.containsId(blockId)) {
            source.sendError(Text.literal("Unknown block: " + blockId));
            return 0;
        }
        if (n < 1) {
            source.sendError(Text.literal("n must be >= 1"));
            return 0;
        }

        Block block = Registries.BLOCK.get(blockId);
        NthClosestBlockTask task = new NthClosestBlockTask(
                state -> state.isOf(block), defaultBlockRadius(), n, (found, pos) ->
                        source.getClient().execute(() -> {
                            if (pos == null) {
                                source.sendError(Text.literal("Only found %d match(es) of %s, need at least %d"
                                        .formatted(found, blockId, n)));
                                return;
                            }
                            ClientPlayerEntity player = source.getPlayer();
                            Vec3d center = Vec3d.ofCenter(pos);
                            aimAt(player, center);
                            source.sendFeedback(Text.literal("Looking at %s #%d at %d, %d, %d"
                                    .formatted(blockId, n, pos.getX(), pos.getY(), pos.getZ())));
                        }));
        try {
            GladeClient.taskScheduler().addTask("look-block", task);
        } catch (IllegalStateException conflict) {
            source.sendError(Text.literal("A world scan is already running"));
            return 0;
        }
        source.sendFeedback(Text.literal("Scanning loaded chunks..."));
        return 1;
    }

    static int executeEntity(
            CommandContext<FabricClientCommandSource> context, Identifier entityTypeId, String tag, int n) {
        FabricClientCommandSource source = context.getSource();
        if (n < 1) {
            source.sendError(Text.literal("n must be >= 1"));
            return 0;
        }

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

        if (matches.size() < n) {
            source.sendError(Text.literal("Only found %d matching entit%s, need at least %d"
                    .formatted(matches.size(), matches.size() == 1 ? "y" : "ies", n)));
            return 0;
        }

        Entity target = matches.get(n - 1);
        aimAt(player, target.getEyePos());

        Identifier targetTypeId = Registries.ENTITY_TYPE.getId(target.getType());
        source.sendFeedback(Text.literal("Looking at %s #%d at %.0f, %.0f, %.0f"
                .formatted(targetTypeId, n, target.getX(), target.getY(), target.getZ())));
        return 1;
    }

    /**
     * Handles {@code /glade look <selector> [n]} for Minecraft-style target selectors:
     * {@code @e[...]}, {@code @a}, {@code @p} (nearest player excluding the caller) and
     * {@code @s} (self).
     */
    static int executeSelector(CommandContext<FabricClientCommandSource> context, String token, int n) {
        FabricClientCommandSource source = context.getSource();
        if (n < 1) {
            source.sendError(Text.literal("n must be >= 1"));
            return 0;
        }

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

        return switch (selector.kind()) {
            case SELF -> {
                source.sendFeedback(Text.literal("@s is you — no aim change"));
                yield 1;
            }
            case PLAYER_NEAREST -> aimAtNth(source, player,
                    playerCandidates(client, player, false), selector, n, "player");
            case PLAYERS_ALL -> aimAtNth(source, player,
                    playerCandidates(client, player, true), selector, n, "player");
            case ENTITIES -> {
                Box box = player.getBoundingBox().expand(ENTITY_SEARCH_RADIUS);
                List<Entity> candidates = client.world.getEntitiesByClass(Entity.class, box,
                        entity -> entity.isAlive() && selector.matchesFilters(entity));
                yield aimAtNth(source, player, candidates, selector, n, "entity");
            }
        };
    }

    private static List<Entity> playerCandidates(MinecraftClient client, ClientPlayerEntity self, boolean includeSelf) {
        List<Entity> players = new java.util.ArrayList<>();
        for (AbstractClientPlayerEntity other : client.world.getPlayers()) {
            if (includeSelf || other != self) {
                players.add(other);
            }
        }
        return players;
    }

    /** Filters by distance, sorts (nearest by default, furthest if requested), applies {@code limit=} then picks the Nth match. */
    private static int aimAtNth(FabricClientCommandSource source, ClientPlayerEntity player,
            List<Entity> candidates, EntitySelector selector, int n, String noun) {
        Comparator<Entity> byDistance = Comparator.comparingDouble(player::squaredDistanceTo);
        List<Entity> filtered = candidates.stream()
                .filter(entity -> selector.withinDistance(Math.sqrt(player.squaredDistanceTo(entity))))
                .sorted(selector.furthest() ? byDistance.reversed() : byDistance)
                .toList();
        Integer limit = selector.limit();
        if (limit != null && filtered.size() > limit) {
            filtered = filtered.subList(0, Math.max(limit, 0));
        }

        if (filtered.size() < n) {
            source.sendError(Text.literal("Only found %d matching %s%s, need at least %d"
                    .formatted(filtered.size(), noun, filtered.size() == 1 ? "" : "s", n)));
            return 0;
        }

        Entity target = filtered.get(n - 1);
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

    /** Sets yaw/pitch/headYaw/bodyYaw so the player faces {@code target} from its eye position. */
    private static void aimAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float pitch = (float) (-(MathHelper.atan2(dy, horizontalDistance) * (180.0 / Math.PI)));
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;

        aim(player, MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90.0F, 90.0F));
    }

    private static void aim(ClientPlayerEntity player, float yaw, float pitch) {
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
    }
}
