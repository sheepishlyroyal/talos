package dev.glade.client.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Parses and applies a practical subset of Minecraft-style target selectors for
 * {@code /glade look}: {@code @e[...]}, {@code @a}, {@code @p} and {@code @s}.
 *
 * <p>Supported {@code @e[...]} arguments: {@code type=} (namespaced or plain, {@code !} negation),
 * {@code tag=} (repeatable, {@code !} negation), {@code name=}, {@code distance=} (e.g. {@code ..16},
 * {@code 5..}, {@code 5..16}), {@code limit=} and {@code sort=nearest|furthest}. An empty
 * {@code @e[]} (or bare {@code @e}) matches any entity.</p>
 */
final class EntitySelector {
    enum Kind { SELF, PLAYERS_ALL, PLAYER_NEAREST, ENTITIES }

    private final Kind kind;
    private Identifier typeId;
    private boolean typeNegate;
    private final List<String> requiredTags = new ArrayList<>();
    private final List<String> excludedTags = new ArrayList<>();
    private String name;
    private Double minDistance;
    private Double maxDistance;
    private Integer limit;
    private boolean furthest;

    private EntitySelector(Kind kind) {
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }

    Integer limit() {
        return limit;
    }

    boolean furthest() {
        return furthest;
    }

    /**
     * Parses a raw selector token (as produced by {@link SelectorArgumentType}), e.g.
     * {@code @e[type=cow,distance=..16]}. On failure, writes a human-readable message to
     * {@code error[0]} and returns {@code null}.
     */
    static EntitySelector parse(String token, String[] error) {
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
            case 's' -> Kind.SELF;
            default -> null;
        };
        if (kind == null) {
            error[0] = "Unknown selector @" + letter + " (use @e, @a, @p or @s)";
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

        for (String rawPair : body.split(",")) {
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
            String value = pair.substring(eq + 1).trim();
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
                Identifier id = idString.contains(":") ? Identifier.of(idString) : Identifier.of("minecraft", idString);
                if (!Registries.ENTITY_TYPE.containsId(id)) {
                    error[0] = "Unknown entity type: " + idString;
                    return false;
                }
                typeId = id;
                typeNegate = negate;
            }
            case "tag" -> {
                if (value.startsWith("!")) {
                    excludedTags.add(value.substring(1));
                } else {
                    requiredTags.add(value);
                }
            }
            case "name" -> name = value;
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
                } catch (NumberFormatException exception) {
                    error[0] = "Invalid limit: " + value;
                    return false;
                }
            }
            case "sort" -> furthest = value.equalsIgnoreCase("furthest");
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
    boolean matchesFilters(Entity entity) {
        if (typeId != null) {
            boolean isType = Registries.ENTITY_TYPE.get(typeId) == entity.getType();
            if (isType == typeNegate) {
                return false;
            }
        }
        for (String tag : requiredTags) {
            if (!entity.getCommandTags().contains(tag)) {
                return false;
            }
        }
        for (String tag : excludedTags) {
            if (entity.getCommandTags().contains(tag)) {
                return false;
            }
        }
        return name == null || name.equals(entity.getName().getString());
    }

    boolean withinDistance(double distance) {
        if (minDistance != null && distance < minDistance) {
            return false;
        }
        return maxDistance == null || distance <= maxDistance;
    }
}
