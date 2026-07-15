package dev.talos.client.command;

import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Client-side interpretation of vanilla-style target selectors for commands that need one
 * entity ({@code /talos follow}, scripts). Supported forms:
 *
 * <ul>
 *   <li>{@code @p} / {@code @a} / {@code @r} — players; {@code @e} / {@code @n} — any entity;
 *       {@code @s} — the local player (callers that follow reject it).</li>
 *   <li>Bracket arguments: {@code type=[!]id}, {@code name=[!]text}, {@code tag=[!]text},
 *       {@code distance=A..B|..B|A..|N}, {@code sort=nearest|furthest|random|arbitrary},
 *       {@code limit=} (accepted; one entity is returned regardless).</li>
 *   <li>Plain text — a player name (case-insensitive) first, else an entity type id
 *       ({@code zombie} or {@code minecraft:zombie}) resolved to the nearest match.</li>
 * </ul>
 *
 * <p>This runs on the CLIENT: only entities the server tracks to this client are visible,
 * and selector features that need server data (scores, gamemode, nbt) are unsupported and
 * rejected with a clear error rather than silently ignored.</p>
 */
public final class EntitySelectors {
    private EntitySelectors() {}

    /**
     * Resolves {@code input} to a single entity, or throws {@link IllegalArgumentException}
     * with a user-presentable reason. {@code excludeSelf} removes the local player from
     * every candidate set (follow can never target yourself); {@code @s} bypasses it.
     */
    public static Entity resolve(MinecraftClient client, String input, boolean excludeSelf) {
        if (client.world == null || client.player == null)
            throw new IllegalArgumentException("No world is loaded");
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Empty target selector");
        if (!text.startsWith("@")) return resolvePlain(client, text, excludeSelf);

        String[] error = new String[1];
        EntitySelector selector = EntitySelector.parse(text, error);
        if (selector == null) throw new IllegalArgumentException(error[0]);
        java.util.List<Entity> candidates = selector.select(client, excludeSelf);
        if (candidates.isEmpty())
            throw new IllegalArgumentException("No entity matches " + text);
        return candidates.get(0);
    }

    private static Entity resolvePlain(MinecraftClient client, String text, boolean excludeSelf) {
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof PlayerEntity player) || entity.isRemoved()) continue;
            if (excludeSelf && entity == client.player) continue;
            if (player.getGameProfile().name().equalsIgnoreCase(text)) return player;
        }
        Identifier typeId = Identifier.tryParse(
                text.contains(":") ? text : "minecraft:" + text.toLowerCase(Locale.ROOT));
        if (typeId != null && Registries.ENTITY_TYPE.containsId(typeId)) {
            Entity nearest = null;
            double best = Double.MAX_VALUE;
            for (Entity entity : client.world.getEntities()) {
                if (entity.isRemoved()) continue;
                if (excludeSelf && entity == client.player) continue;
                if (!Registries.ENTITY_TYPE.getId(entity.getType()).equals(typeId)) continue;
                double distance = entity.squaredDistanceTo(client.player);
                if (distance < best) { best = distance; nearest = entity; }
            }
            if (nearest != null) return nearest;
            throw new IllegalArgumentException("No loaded " + typeId + " found");
        }
        throw new IllegalArgumentException(
                "'" + text + "' is not an online player, entity type, or selector");
    }

}
