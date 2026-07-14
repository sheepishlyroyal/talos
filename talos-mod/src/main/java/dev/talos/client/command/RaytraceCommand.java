package dev.talos.client.command;

import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.command.LocalCoordinateArgumentType.Axis;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * {@code /talos raytrace} — look-relative coordinate and raycast queries.
 *
 * <ul>
 *   <li>{@code raytrace get <x> <y> <z>} (or the bare {@code raytrace <x> <y> <z>}) — resolve a
 *       coordinate triple (absolute, {@code ~}-relative, or {@code ^}-local) to a world point,
 *       reported to 3 decimal places. {@code ^ ^ 5} is 5 blocks forward; {@code ^ ^ -3} is 3
 *       behind.</li>
 *   <li>{@code raytrace where [maxDist]} — cast from the eyes along the look and report the first
 *       block or entity hit, with its exact hit point (3dp), id and distance.</li>
 *   <li>{@code raytrace if block <id> [maxDist]} / {@code raytrace if entity <selector> [maxDist]}
 *       — succeed (1) or fail (0) depending on whether the first hit matches.</li>
 * </ul>
 */
final class RaytraceCommand {
    private RaytraceCommand() {
    }

    // ---- get -------------------------------------------------------------------------------

    static int get(CommandContext<FabricClientCommandSource> context) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        Axis ax = context.getArgument("x", Axis.class);
        Axis ay = context.getArgument("y", Axis.class);
        Axis az = context.getArgument("z", Axis.class);

        Vec3d point;
        try {
            point = resolve(player, ax, ay, az);
        } catch (IllegalArgumentException mixed) {
            context.getSource().sendError(Text.literal(mixed.getMessage()));
            return 0;
        }

        String block = "unloaded";
        ClientWorld world = context.getSource().getClient().world;
        if (world != null) {
            BlockPos cell = BlockPos.ofFloored(point);
            block = Registries.BLOCK.getId(world.getBlockState(cell).getBlock()).toString();
        }
        context.getSource().sendFeedback(Text.literal(String.format(Locale.ROOT,
                "§bpoint§f = %.3f %.3f %.3f §7(block: %s)",
                point.x, point.y, point.z, block)));
        return 1;
    }

    /**
     * Resolves a coordinate triple against the player. Absolute axes are literal; {@code ~} axes
     * offset the eye position; {@code ^} (local) axes form the caret {@code ^left ^up ^forward}
     * frame. Friendlier than vanilla: once ANY axis is a caret, plain numbers on the other axes
     * are read as local offsets too — {@code ^ ^ 5} IS {@code ^ ^ ^5}, five blocks along the
     * gaze. Only mixing {@code ^} with {@code ~} stays illegal (two different origins).
     */
    static Vec3d resolve(ClientPlayerEntity player, Axis ax, Axis ay, Axis az) {
        boolean anyLocal = ax.type() == LocalCoordinateArgumentType.Type.LOCAL
                || ay.type() == LocalCoordinateArgumentType.Type.LOCAL
                || az.type() == LocalCoordinateArgumentType.Type.LOCAL;
        if (anyLocal) {
            if (ax.type() == LocalCoordinateArgumentType.Type.RELATIVE
                    || ay.type() == LocalCoordinateArgumentType.Type.RELATIVE
                    || az.type() == LocalCoordinateArgumentType.Type.RELATIVE) {
                throw new IllegalArgumentException(
                        "Cannot mix ^ (local) with ~ (relative) axes — pick one frame");
            }
            // Caret order is ^left ^up ^forward; origin is the eyes.
            return RaycastMath.local(player.getEyePos(), player.getYaw(), player.getPitch(),
                    ax.value(), ay.value(), az.value());
        }
        Vec3d eye = player.getEyePos();
        return new Vec3d(axis(ax, eye.x), axis(ay, eye.y), axis(az, eye.z));
    }

    private static double axis(Axis a, double base) {
        return a.type() == LocalCoordinateArgumentType.Type.RELATIVE ? base + a.value() : a.value();
    }

    // ---- where -----------------------------------------------------------------------------

    static int where(CommandContext<FabricClientCommandSource> context, double maxDist) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        ClientWorld world = context.getSource().getClient().world;
        if (world == null) {
            context.getSource().sendError(Text.literal("No world is loaded"));
            return 0;
        }
        RaycastMath.Hit hit = RaycastMath.cast(player, world, maxDist);
        if (hit.isMiss()) {
            context.getSource().sendFeedback(Text.literal(String.format(Locale.ROOT,
                    "§7Nothing within %.1fm along the look", maxDist)));
            return 0;
        }
        String kind = hit.type() == RaycastMath.HitType.ENTITY ? "entity" : "block";
        context.getSource().sendFeedback(Text.literal(String.format(Locale.ROOT,
                "§bray §f→ %s §b%s§f at %.3f %.3f %.3f §7(%.2fm)",
                kind, hit.id(), hit.point().x, hit.point().y, hit.point().z, hit.distance())));
        return 1;
    }

    // ---- if --------------------------------------------------------------------------------

    static int ifBlock(CommandContext<FabricClientCommandSource> context, String blockId,
                       double maxDist) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        ClientWorld world = context.getSource().getClient().world;
        if (world == null) {
            context.getSource().sendError(Text.literal("No world is loaded"));
            return 0;
        }
        Identifier id = Identifier.tryParse(blockId.contains(":") ? blockId : "minecraft:" + blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            context.getSource().sendError(Text.literal("Unknown block: " + blockId));
            return 0;
        }
        RaycastMath.Hit hit = RaycastMath.cast(player, world, maxDist);
        boolean match = hit.type() == RaycastMath.HitType.BLOCK && id.toString().equals(hit.id());
        context.getSource().sendFeedback(Text.literal(match
                ? "§apass§f: looking at " + id
                : "§cfail§f: first hit is " + describe(hit)));
        return match ? 1 : 0;
    }

    static int ifEntity(CommandContext<FabricClientCommandSource> context, String selectorToken,
                        double maxDist) {
        ClientPlayerEntity player = context.getSource().getPlayer();
        ClientWorld world = context.getSource().getClient().world;
        if (world == null) {
            context.getSource().sendError(Text.literal("No world is loaded"));
            return 0;
        }
        String[] error = new String[1];
        EntitySelector selector = EntitySelector.parse(selectorToken, error);
        if (selector == null) {
            context.getSource().sendError(Text.literal(error[0]));
            return 0;
        }
        RaycastMath.Hit hit = RaycastMath.cast(player, world, maxDist);
        boolean match = hit.type() == RaycastMath.HitType.ENTITY
                && selectorMatches(selector, hit.entity(), player);
        context.getSource().sendFeedback(Text.literal(match
                ? "§apass§f: looking at " + hit.id()
                : "§cfail§f: first hit is " + describe(hit)));
        return match ? 1 : 0;
    }

    /** Whether one already-identified entity satisfies a selector's kind and filters. */
    private static boolean selectorMatches(EntitySelector selector, Entity entity,
                                           ClientPlayerEntity player) {
        return switch (selector.kind()) {
            case SELF -> entity == player;
            case PLAYERS_ALL, PLAYER_NEAREST -> entity instanceof PlayerEntity;
            case ENTITIES -> selector.matchesFilters(entity);
        };
    }

    private static String describe(RaycastMath.Hit hit) {
        return switch (hit.type()) {
            case BLOCK -> "block " + hit.id();
            case ENTITY -> "entity " + hit.id();
            case MISS -> "nothing in range";
        };
    }
}
