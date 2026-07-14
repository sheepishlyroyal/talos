package dev.talos.client.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

        char kind = text.length() > 1 ? text.charAt(1) : ' ';
        String args = "";
        if (text.length() > 2) {
            if (text.charAt(2) != '[' || !text.endsWith("]"))
                throw new IllegalArgumentException("Malformed selector: " + text);
            args = text.substring(3, text.length() - 1);
        }
        if (kind == 's') {
            if (!args.isEmpty()) throw new IllegalArgumentException("@s takes no arguments");
            return client.player;
        }
        boolean playersOnly = kind == 'p' || kind == 'a' || kind == 'r';
        if (!playersOnly && kind != 'e' && kind != 'n')
            throw new IllegalArgumentException("Unsupported selector @" + kind
                    + " — use @p, @a, @r, @e, @n, @s, or a name");

        Filters filters = Filters.parse(args);
        List<Entity> candidates = new ArrayList<>();
        for (Entity entity : client.world.getEntities()) {
            if (entity.isRemoved()) continue;
            if (excludeSelf && entity == client.player) continue;
            if (playersOnly && !(entity instanceof PlayerEntity)) continue;
            if (filters.matches(client, entity)) candidates.add(entity);
        }
        if (candidates.isEmpty())
            throw new IllegalArgumentException("No entity matches " + text);

        String sort = filters.sort != null ? filters.sort
                : (kind == 'r' ? "random" : "nearest");
        Comparator<Entity> byDistance =
                Comparator.comparingDouble(entity -> entity.squaredDistanceTo(client.player));
        return switch (sort) {
            case "nearest" -> candidates.stream().min(byDistance).orElseThrow();
            case "furthest" -> candidates.stream().max(byDistance).orElseThrow();
            case "random" -> candidates.get(
                    java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
            case "arbitrary" -> candidates.get(0);
            default -> throw new IllegalArgumentException("Unsupported sort=" + sort);
        };
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

    private record Filters(List<String> types, List<String> notTypes, String name, String notName,
                           List<String> tags, List<String> notTags, double minSq, double maxSq,
                           String sort) {
        static Filters parse(String args) {
            List<String> types = new ArrayList<>(), notTypes = new ArrayList<>();
            List<String> tags = new ArrayList<>(), notTags = new ArrayList<>();
            String name = null, notName = null, sort = null;
            double minSq = 0, maxSq = Double.MAX_VALUE;
            if (!args.isBlank()) for (String pair : args.split(",")) {
                int eq = pair.indexOf('=');
                if (eq < 0) throw new IllegalArgumentException("Malformed selector argument: " + pair);
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                boolean negated = value.startsWith("!");
                String bare = negated ? value.substring(1) : value;
                switch (key) {
                    case "type" -> (negated ? notTypes : types).add(qualify(bare));
                    case "name" -> { if (negated) notName = bare; else name = bare; }
                    case "tag" -> (negated ? notTags : tags).add(bare);
                    case "sort" -> sort = bare;
                    case "limit" -> { } // one entity is returned regardless
                    case "distance" -> {
                        if (negated) throw new IllegalArgumentException("distance cannot be negated");
                        int dots = bare.indexOf("..");
                        if (dots < 0) {
                            double exact = Double.parseDouble(bare);
                            minSq = exact * exact; maxSq = exact * exact;
                        } else {
                            String low = bare.substring(0, dots), high = bare.substring(dots + 2);
                            if (!low.isEmpty()) { double v = Double.parseDouble(low); minSq = v * v; }
                            if (!high.isEmpty()) { double v = Double.parseDouble(high); maxSq = v * v; }
                        }
                    }
                    default -> throw new IllegalArgumentException("Unsupported selector argument '"
                            + key + "' (client-side selectors support type, name, tag, distance, sort, limit)");
                }
            }
            return new Filters(types, notTypes, name, notName, tags, notTags, minSq, maxSq, sort);
        }

        private static String qualify(String id) {
            return id.contains(":") ? id : "minecraft:" + id.toLowerCase(Locale.ROOT);
        }

        boolean matches(MinecraftClient client, Entity entity) {
            String typeId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            if (!types.isEmpty() && !types.contains(typeId)) return false;
            if (notTypes.contains(typeId)) return false;
            String entityName = entity instanceof PlayerEntity player
                    ? player.getGameProfile().name() : entity.getName().getString();
            if (name != null && !entityName.equalsIgnoreCase(name)) return false;
            if (notName != null && entityName.equalsIgnoreCase(notName)) return false;
            for (String tag : tags) if (!entity.getCommandTags().contains(tag)) return false;
            for (String tag : notTags) if (entity.getCommandTags().contains(tag)) return false;
            double distanceSq = entity.squaredDistanceTo(client.player);
            return distanceSq >= minSq && distanceSq <= maxSq;
        }
    }
}
