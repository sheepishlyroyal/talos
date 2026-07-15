package dev.talos.client.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

/**
 * Parses and applies a practical subset of Minecraft-style target selectors for
 * {@code /talos look}: {@code @e[...]}, {@code @a}, {@code @p} and {@code @s}.
 *
 * <p>Supported {@code @e[...]} arguments: {@code type=} (namespaced or plain, {@code !} negation),
 * {@code tag=} (repeatable, {@code !} negation), {@code name=}, {@code distance=} (e.g. {@code ..16},
 * {@code 5..}, {@code 5..16}), {@code limit=} and {@code sort=nearest|furthest}. An empty
 * {@code @e[]} (or bare {@code @e}) matches any entity.</p>
 */
public final class EntitySelector {
    public enum Kind { SELF, PLAYERS_ALL, PLAYER_NEAREST, PLAYER_RANDOM, ENTITIES, ENTITY_NEAREST }

    private final Kind kind;
    private Identifier typeId;
    private final List<Identifier> excludedTypeIds = new ArrayList<>();
    private TagKey<EntityType<?>> typeTag;
    private final List<TagKey<EntityType<?>>> excludedTypeTags = new ArrayList<>();
    private final List<String> requiredTags = new ArrayList<>();
    private final List<String> excludedTags = new ArrayList<>();
    private String name;
    private final List<String> excludedNames = new ArrayList<>();
    private Double minDistance;
    private Double maxDistance;
    private Integer limit;
    private String sort;

    private EntitySelector(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    Integer limit() {
        return limit;
    }

    boolean furthest() {
        return "furthest".equals(sort);
    }

    /**
     * Parses a raw selector token (as produced by {@link SelectorArgumentType}), e.g.
     * {@code @e[type=cow,distance=..16]}. On failure, writes a human-readable message to
     * {@code error[0]} and returns {@code null}.
     */
    public static EntitySelector parse(String token, String[] error) {
        if (token.isEmpty() || token.charAt(0) != '@') {
            error[0] = "Selector must start with @";
            return null;
        }
        if (token.length() < 2) {
            error[0] = "Expected a selector type after @ (e, a, p or s)";
            return null;
        }

        char letter = Character.toLowerCase(token.charAt(1));
        Kind kind = switch (letter) {
            case 'e' -> Kind.ENTITIES;
            case 'a' -> Kind.PLAYERS_ALL;
            case 'p' -> Kind.PLAYER_NEAREST;
            case 'r' -> Kind.PLAYER_RANDOM;
            case 'n' -> Kind.ENTITY_NEAREST;
            case 's' -> Kind.SELF;
            default -> null;
        };
        if (kind == null) {
            error[0] = "Unknown selector @" + letter + " (use @e, @a, @p, @r, @n or @s)";
            return null;
        }

        EntitySelector selector = new EntitySelector(kind);
        String rest = token.substring(2);
        if (rest.isEmpty()) {
            return selector;
        }
        if (rest.charAt(0) != '[' || rest.charAt(rest.length() - 1) != ']') {
            error[0] = "Expected [ ] after @" + letter;
            return null;
        }
        String body = rest.substring(1, rest.length() - 1).trim();
        if (body.isEmpty()) {
            return selector;
        }

        for (String rawPair : splitArguments(body, error)) {
            if (rawPair == null) return null;
            String pair = rawPair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                error[0] = "Invalid selector argument: " + pair;
                return null;
            }
            String key = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = unquote(pair.substring(eq + 1).trim(), error);
            if (value == null) return null;
            if (!selector.applyArgument(key, value, error)) {
                return null;
            }
        }
        return selector;
    }

    private boolean applyArgument(String key, String value, String[] error) {
        switch (key) {
            case "type" -> {
                boolean negate = value.startsWith("!");
                String idString = negate ? value.substring(1) : value;
                if (idString.isEmpty()) {
                    error[0] = "Entity type cannot be empty";
                    return false;
                }
                boolean tag = idString.startsWith("#");
                if (tag) idString = idString.substring(1);
                Identifier id = Identifier.tryParse(idString.contains(":")
                        ? idString : "minecraft:" + idString);
                if (id == null) {
                    error[0] = "Invalid entity type: " + idString;
                    return false;
                }
                if (tag) {
                    TagKey<EntityType<?>> typeTagKey = TagKey.create(Registries.ENTITY_TYPE, id);
                    if (negate) excludedTypeTags.add(typeTagKey);
                    else if (typeId == null && typeTag == null) typeTag = typeTagKey;
                    else {
                        error[0] = "Only one non-negated type= argument is allowed";
                        return false;
                    }
                    break;
                }
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                    error[0] = "Unknown entity type: " + idString;
                    return false;
                }
                if (negate) excludedTypeIds.add(id);
                else if (typeId == null) typeId = id;
                else {
                    error[0] = "Only one non-negated type= argument is allowed";
                    return false;
                }
            }
            case "tag" -> {
                if (value.startsWith("!")) {
                    excludedTags.add(value.substring(1));
                } else {
                    requiredTags.add(value);
                }
            }
            case "name" -> {
                if (value.startsWith("!")) excludedNames.add(value.substring(1));
                else if (name == null) name = value;
                else {
                    error[0] = "Only one non-negated name= argument is allowed";
                    return false;
                }
            }
            case "distance" -> {
                int dots = value.indexOf("..");
                try {
                    if (dots < 0) {
                        double exact = Double.parseDouble(value);
                        minDistance = exact;
                        maxDistance = exact;
                    } else {
                        String lo = value.substring(0, dots);
                        String hi = value.substring(dots + 2);
                        minDistance = lo.isEmpty() ? null : Double.parseDouble(lo);
                        maxDistance = hi.isEmpty() ? null : Double.parseDouble(hi);
                    }
                } catch (NumberFormatException exception) {
                    error[0] = "Invalid distance range: " + value;
                    return false;
                }
            }
            case "limit" -> {
                try {
                    limit = Integer.parseInt(value);
                    if (limit < 1) {
                        error[0] = "Selector limit must be at least 1";
                        return false;
                    }
                } catch (NumberFormatException exception) {
                    error[0] = "Invalid limit: " + value;
                    return false;
                }
            }
            case "sort" -> {
                String normalized = value.toLowerCase(Locale.ROOT);
                if (!List.of("nearest", "furthest", "random", "arbitrary").contains(normalized)) {
                    error[0] = "Unsupported selector sort: " + value;
                    return false;
                }
                sort = normalized;
            }
            default -> {
                error[0] = "Unsupported selector argument: " + key;
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code entity} matches this selector's {@code type=}/{@code tag=}/{@code name=}
     * filters. Distance is checked separately via {@link #withinDistance} since it needs the
     * executing player's position.
     */
    public boolean matchesFilters(Entity entity) {
        return matchesFilters(entity.getType(), entity.entityTags(), entity.getName().getString());
    }

    boolean matchesFilters(EntityType<?> entityType, Set<String> commandTags, String entityName) {
        if (typeId != null) {
            if (BuiltInRegistries.ENTITY_TYPE.getValue(typeId) != entityType) return false;
        }
        var entityTypeHolder = BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entityType);
        if (typeTag != null && !entityTypeHolder.is(typeTag)) return false;
        Identifier entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        if (excludedTypeIds.contains(entityTypeId)) return false;
        for (TagKey<EntityType<?>> excludedTypeTag : excludedTypeTags) {
            if (entityTypeHolder.is(excludedTypeTag)) return false;
        }
        for (String tag : requiredTags) {
            if (tag.isEmpty() ? !commandTags.isEmpty() : !commandTags.contains(tag)) {
                return false;
            }
        }
        for (String tag : excludedTags) {
            if (tag.isEmpty() ? commandTags.isEmpty() : commandTags.contains(tag)) {
                return false;
            }
        }
        if (name != null && !name.equalsIgnoreCase(entityName)) return false;
        return excludedNames.stream().noneMatch(excluded -> excluded.equalsIgnoreCase(entityName));
    }

    private static List<String> splitArguments(String body, String[] error) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int nested = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && quote != 0) {
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{' || c == '[') {
                nested++;
            } else if (c == '}' || c == ']') {
                if (--nested < 0) {
                    error[0] = "Unbalanced selector argument value";
                    return java.util.Collections.singletonList(null);
                }
            } else if (c == ',' && nested == 0) {
                result.add(body.substring(start, i));
                start = i + 1;
            }
        }
        if (quote != 0 || nested != 0) {
            error[0] = "Unbalanced selector argument value";
            return java.util.Collections.singletonList(null);
        }
        result.add(body.substring(start));
        return result;
    }

    private static String unquote(String value, String[] error) {
        if (value.startsWith("!")) {
            String unquoted = unquote(value.substring(1), error);
            return unquoted == null ? null : "!" + unquoted;
        }
        if (value.length() < 2 || value.charAt(0) != value.charAt(value.length() - 1)
                || value.charAt(0) != '"' && value.charAt(0) != '\'') return value;
        StringBuilder result = new StringBuilder(value.length() - 2);
        boolean escaped = false;
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (escaped) {
                result.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                result.append(c);
            }
        }
        if (escaped) {
            error[0] = "Trailing escape in quoted selector value";
            return null;
        }
        return result.toString();
    }

    public boolean withinDistance(double distance) {
        if (minDistance != null && distance < minDistance) {
            return false;
        }
        return maxDistance == null || distance <= maxDistance;
    }

    /**
     * Applies the selector identity and every bracket filter to one entity. Filters are
     * deliberately not restricted to {@code @e}: vanilla forms such as
     * {@code @a[tag=staff]}, {@code @p[name=!Alex]} and {@code @s[tag=ready]} must behave
     * identically in every Talos command and Python entry point.
     */
    public boolean matches(Entity entity, Entity self) {
        boolean kindMatches = switch (kind) {
            case SELF -> entity == self;
            case PLAYERS_ALL, PLAYER_NEAREST, PLAYER_RANDOM -> entity instanceof Player;
            case ENTITIES, ENTITY_NEAREST -> true;
        };
        return kindMatches && matchesFilters(entity)
                && withinDistance(Math.sqrt(entity.distanceToSqr(self)));
    }

    /** Returns all loaded matches in selector order with the selector's limit applied. */
    public List<Entity> select(Minecraft client, boolean excludeSelf) {
        if (client.level == null || client.player == null) return List.of();
        Entity self = client.player;
        if (kind == Kind.SELF) {
            return matches(self, self) ? List.of(self) : List.of();
        }

        List<Entity> matches = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.isRemoved() || excludeSelf && entity == self) continue;
            if (matches(entity, self)) matches.add(entity);
        }

        String ordering = sort != null ? sort : kind == Kind.PLAYER_RANDOM ? "random" : "nearest";
        Comparator<Entity> byDistance = Comparator.comparingDouble(self::distanceToSqr);
        switch (ordering) {
            case "nearest" -> matches.sort(byDistance);
            case "furthest" -> matches.sort(byDistance.reversed());
            case "random" -> Collections.shuffle(matches);
            case "arbitrary" -> { }
            default -> throw new IllegalStateException("Validated selector sort became " + ordering);
        }

        int defaultLimit = switch (kind) {
            case SELF, PLAYER_NEAREST, PLAYER_RANDOM, ENTITY_NEAREST -> 1;
            case PLAYERS_ALL, ENTITIES -> Integer.MAX_VALUE;
        };
        int resultLimit = limit != null ? limit : defaultLimit;
        return matches.size() <= resultLimit
                ? List.copyOf(matches) : List.copyOf(matches.subList(0, resultLimit));
    }
}
