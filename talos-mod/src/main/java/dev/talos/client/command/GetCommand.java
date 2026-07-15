package dev.talos.client.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.talos.client.rules.EventRuleEngine;
import dev.talos.client.rules.EventRuleEngine.Kind;
import dev.talos.client.rules.EventRuleEngine.Trigger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BiFunction;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * {@code /talos get <observable>} — instant readout of every value the rule engine can watch:
 * all numeric metrics (identical to what rules compare against) plus string and boolean
 * observables. {@code /talos get list} enumerates them.
 */
public final class GetCommand {
    private static final Map<String, BiFunction<MinecraftClient, ClientPlayerEntity, String>>
            GETTERS = build();

    private GetCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        LiteralArgumentBuilder<FabricClientCommandSource> get = ClientCommandManager.literal("get");
        get.then(ClientCommandManager.literal("list").executes(context -> {
            context.getSource().sendFeedback(Text.literal(
                    "Observables/triggers: " + String.join(", ", catalogNames())
                            + " | slot <hotbar.1-9|inv.1-27|head|chest|legs|feet|offhand|cursor"
                            + "|container.N|saddle|horsearmor> | parameterized trigger usage: "
                            + "entity_* <selector> [radius], block_* <block> [radius], "
                            + "item_count/hotbar_item_count/held_enchant <id>, "
                            + "entered_region/left_region <x1 y1 z1 x2 y2 z2>; spatial getters "
                            + "accept x y z using absolute, ~ feet-relative, or ^ look-relative"));
            return 1;
        }));
        get.then(ClientCommandManager.literal("entity")
                .then(ClientCommandManager.argument("selector", SelectorArgumentType.selector())
                        .executes(context -> entityAt(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType
                                        .getString(context, "selector"), 0))
                        .then(ClientCommandManager.argument("index",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(context -> entityAt(context.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(context, "selector"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(context, "index"))))))
        ;
        get.then(ClientCommandManager.literal("blockpos")
                .then(ClientCommandManager.argument("block",
                                IdArgumentType.blockId())
                        .executes(context -> blockAt(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType
                                        .getString(context, "block"), 0))
                        .then(ClientCommandManager.argument("index",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(context -> blockAt(context.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(context, "block"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(context, "index"))))));
        get.then(ClientCommandManager.literal("slot")
                .then(ClientCommandManager.argument("name",
                                com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(context -> slot(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType
                                        .getString(context, "name")))));
        get.then(ClientCommandManager.literal("entity_location")
                .then(ClientCommandManager.argument("id",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                        .executes(context -> sendQuery(context.getSource(), "entity_location",
                                Integer.toString(com.mojang.brigadier.arguments.IntegerArgumentType
                                        .getInteger(context, "id"))))));
        for (Map.Entry<String, BiFunction<MinecraftClient, ClientPlayerEntity, String>> entry
                : GETTERS.entrySet()) {
            LiteralArgumentBuilder<FabricClientCommandSource> getterNode =
                    ClientCommandManager.literal(entry.getKey()).executes(context -> {
                MinecraftClient client = context.getSource().getClient();
                if (client.player == null || client.world == null) {
                    context.getSource().sendError(Text.literal("No world is loaded"));
                    return 0;
                }
                context.getSource().sendFeedback(Text.literal("§b" + entry.getKey() + "§f = "
                        + entry.getValue().apply(client, client.player)));
                return 1;
            });
            getterNode.then(ClientCommandManager.argument("args",
                            com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(context -> sendQuery(context.getSource(), entry.getKey(),
                            words(com.mojang.brigadier.arguments.StringArgumentType
                                    .getString(context, "args")).toArray(String[]::new))));
            get.then(getterNode);
        }
        get.then(ClientCommandManager.argument("query",
                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                .executes(context -> {
                    List<String> words = words(com.mojang.brigadier.arguments.StringArgumentType
                            .getString(context, "query"));
                    if (words.isEmpty()) return 0;
                    return sendQuery(context.getSource(), words.getFirst(),
                            words.subList(1, words.size()).toArray(String[]::new));
                }));
        return get;
    }

    public static java.util.Set<String> catalogNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(GETTERS.keySet());
        for (Trigger trigger : Trigger.values()) names.add(trigger.id());
        names.add("entity_location");
        return java.util.Collections.unmodifiableSet(names);
    }

    private static int sendQuery(FabricClientCommandSource source, String name, String... args) {
        try {
            String value = value(source.getClient(), name, args);
            source.sendFeedback(Text.literal("§b" + name.replace(' ', '_') + "§f = " + value));
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal(exception.getMessage()));
            return 0;
        }
    }

    public static String value(MinecraftClient client, String requested, String... args) {
        if (client.player == null || client.world == null) {
            throw new IllegalArgumentException("No world is loaded");
        }
        String name = requested.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        args = expandArgs(args);

        // String-valued spatial getters use the same coordinate grammar as numeric metrics.
        if ((name.equals("biome") || name.equals("standing_on")
                || name.equals("block_at_feet") || name.equals("block_above_head"))
                && args.length > 0) {
            Vec3d point = parsePoint(name, client.player, args, 0);
            net.minecraft.util.math.BlockPos pos = net.minecraft.util.math.BlockPos.ofFloored(point);
            if (name.equals("biome")) return client.world.getBiome(pos).getKey().orElseThrow().getValue().toString();
            if (name.equals("standing_on")) pos = pos.down();
            if (name.equals("block_above_head")) pos = pos.up(2);
            return Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
        }

        Trigger requestedTrigger = trigger(name);
        if (requestedTrigger != null && requestedTrigger.kind == Kind.COMPARE
                && EventRuleEngine.acceptsPoint(requestedTrigger)
                && args.length > 0) {
            Vec3d point = parsePoint(name, client.player, args, 0);
            if (args.length > 3 && !EventRuleEngine.acceptsSpatialRadius(requestedTrigger)) {
                throw new IllegalArgumentException(name + " accepts x y z but not a radius");
            }
            double radius = args.length > 3 ? parseDouble(name, args[3]) : -1.0;
            return number(EventRuleEngine.metricValue(
                    requestedTrigger, client, client.player, point, radius));
        }
        BiFunction<MinecraftClient, ClientPlayerEntity, String> getter = GETTERS.get(name);
        if (getter != null) {
            if (args.length > 0) throw new IllegalArgumentException(
                    name + " does not take a location or other arguments");
            return getter.apply(client, client.player);
        }

        if (name.equals("entity_location") || name.equals("mob_location")) {
            requireArgs(name, args, 1);
            final int id;
            try { id = Integer.parseInt(args[0]); }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("entity_location needs a numeric mob/entity id");
            }
            var entity = client.world.getEntityById(id);
            if (entity == null) return "-1";
            return String.format(Locale.ROOT, "%s @ %.3f %.3f %.3f",
                    EventRuleEngine.entityLabel(entity), entity.getX(), entity.getY(), entity.getZ());
        }

        final Trigger trigger = requestedTrigger;
        if (trigger == null) {
            throw new IllegalArgumentException("Unknown observable/trigger: " + requested);
        }
        if (trigger.kind == Kind.COMPARE) {
            return number(EventRuleEngine.metricValue(trigger, client, client.player));
        }
        if (trigger.kind == Kind.NUMBER) {
            return switch (trigger) {
                case HEALTH_BELOW, HEALTH_ABOVE -> number(EventRuleEngine.metricValue(
                        Trigger.HEALTH, client, client.player));
                case HUNGER_BELOW -> number(EventRuleEngine.metricValue(
                        Trigger.HUNGER, client, client.player));
                case AIR_BELOW -> number(EventRuleEngine.metricValue(
                        Trigger.AIR, client, client.player));
                case XP_LEVEL_ABOVE -> number(EventRuleEngine.metricValue(
                        Trigger.XP_LEVEL, client, client.player));
                case TICK_EVERY -> Integer.toString(EventRuleEngine.currentTick());
                default -> EventRuleEngine.latestValue(trigger);
            };
        }
        if (trigger.kind == Kind.ENTITY_COUNT || trigger.kind == Kind.ENTITY_PRESENCE) {
            requireArgs(name, args, 1);
            double radius = args.length > 1 ? parseDouble(name, args[1]) : -1.0;
            Vec3d point = args.length > 2 ? parsePoint(name, client.player, args, 2) : null;
            return number(EventRuleEngine.parameterValue(trigger, client, client.player,
                    args[0], radius, null, point));
        }
        if (trigger.kind == Kind.BLOCK_COUNT || trigger.kind == Kind.BLOCK_PRESENCE) {
            requireArgs(name, args, 1);
            double radius = args.length > 1 ? parseDouble(name, args[1]) : 16.0;
            Vec3d point = args.length > 2 ? parsePoint(name, client.player, args, 2) : null;
            return number(EventRuleEngine.parameterValue(trigger, client, client.player,
                    args[0], radius, null, point));
        }
        if (trigger.kind == Kind.ITEM_COUNT) {
            requireArgs(name, args, 1);
            return number(EventRuleEngine.parameterValue(trigger, client, client.player,
                    args[0], 0, null));
        }
        if (trigger.kind == Kind.REGION) {
            requireArgs(name, args, 6);
            double[] region = new double[6];
            Vec3d first = parsePoint(name, client.player, args, 0);
            Vec3d second = parsePoint(name, client.player, args, 3);
            region[0] = first.x; region[1] = first.y; region[2] = first.z;
            region[3] = second.x; region[4] = second.y; region[5] = second.z;
            double inside = EventRuleEngine.parameterValue(trigger, client, client.player,
                    "", 0, region);
            return Boolean.toString(trigger == Trigger.LEFT_REGION ? inside == 0 : inside != 0);
        }
        return EventRuleEngine.latestValue(trigger);
    }

    private static void requireArgs(String name, String[] args, int count) {
        if (args.length < count) throw new IllegalArgumentException(
                name + " needs " + count + " argument" + (count == 1 ? "" : "s"));
    }

    private static double parseDouble(String name, String raw) {
        try { return Double.parseDouble(raw); }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + ": expected a number, got " + raw);
        }
    }

    private static Trigger trigger(String name) {
        try { return Trigger.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private static String[] expandArgs(String[] args) {
        List<String> expanded = new ArrayList<>();
        for (String arg : args) expanded.addAll(words(arg));
        return expanded.toArray(String[]::new);
    }

    /** Resolve absolute, feet-relative ~, or eye/look-relative ^ coordinates. */
    private static Vec3d parsePoint(String name, ClientPlayerEntity player, String[] args, int start) {
        if (args.length < start + 3) throw new IllegalArgumentException(
                name + " needs x y z (absolute, ~ relative, or ^ local)");
        String[] raw = {args[start], args[start + 1], args[start + 2]};
        boolean local = java.util.Arrays.stream(raw).anyMatch(value -> value.startsWith("^"));
        boolean relative = java.util.Arrays.stream(raw).anyMatch(value -> value.startsWith("~"));
        if (local && relative) throw new IllegalArgumentException(
                name + ": cannot mix ^ local and ~ relative coordinates");
        try {
            if (local) {
                return RaycastMath.local(player.getEyePos(), player.getYaw(), player.getPitch(),
                        coordinateTail(raw[0], '^'), coordinateTail(raw[1], '^'),
                        coordinateTail(raw[2], '^'));
            }
            Vec3d feet = player.getEntityPos();
            return new Vec3d(resolveAxis(raw[0], feet.x), resolveAxis(raw[1], feet.y),
                    resolveAxis(raw[2], feet.z));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + ": invalid coordinate triplet "
                    + String.join(" ", raw));
        }
    }

    private static double resolveAxis(String raw, double base) {
        return raw.startsWith("~") ? base + coordinateTail(raw, '~') : Double.parseDouble(raw);
    }

    private static double coordinateTail(String raw, char prefix) {
        String tail = raw.startsWith(Character.toString(prefix)) ? raw.substring(1) : raw;
        return tail.isEmpty() ? 0.0 : Double.parseDouble(tail);
    }

    private static String number(double value) {
        return value == Math.floor(value) && Math.abs(value) < 1.0E12
                ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.3f", value);
    }

    private static List<String> words(String raw) {
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        int brackets = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                word.append(c);
                if (c == quote && (i == 0 || raw.charAt(i - 1) != '\\')) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
                word.append(c);
            } else if (c == '[') { brackets++; word.append(c); }
            else if (c == ']') { brackets = Math.max(0, brackets - 1); word.append(c); }
            else if (Character.isWhitespace(c) && brackets == 0) {
                if (!word.isEmpty()) { result.add(word.toString()); word.setLength(0); }
            } else word.append(c);
        }
        if (!word.isEmpty()) result.add(word.toString());
        return result;
    }

    private static Map<String, BiFunction<MinecraftClient, ClientPlayerEntity, String>> build() {
        Map<String, BiFunction<MinecraftClient, ClientPlayerEntity, String>> map =
                new LinkedHashMap<>();

        // Every COMPARE metric reads through the exact code path rules evaluate.
        for (Trigger trigger : Trigger.values()) {
            if (trigger.kind != Kind.COMPARE) continue;
            map.put(trigger.id(), (client, player) -> {
                double value = EventRuleEngine.metricValue(trigger, client, player);
                return value == Math.floor(value) && Math.abs(value) < 1.0E9
                        ? Long.toString((long) value)
                        : String.format(Locale.ROOT, "%.2f", value);
            });
        }

        // Strings.
        map.put("position", (client, player) -> String.format(Locale.ROOT,
                "%.2f %.2f %.2f", player.getX(), player.getY(), player.getZ()));
        map.put("block_position", (client, player) -> player.getBlockPos().toShortString());
        map.put("yaw", (client, player) -> String.format(Locale.ROOT, "%.1f", player.getYaw()));
        map.put("pitch", (client, player) -> String.format(Locale.ROOT, "%.1f", player.getPitch()));
        map.put("dimension", (client, player) ->
                client.world.getRegistryKey().getValue().toString());
        map.put("biome", (client, player) ->
                client.world.getBiome(player.getBlockPos()).getIdAsString());
        map.put("held_item", (client, player) ->
                Registries.ITEM.getId(player.getMainHandStack().getItem()).toString()
                        + " x" + player.getMainHandStack().getCount());
        map.put("offhand_item", (client, player) ->
                Registries.ITEM.getId(player.getOffHandStack().getItem()).toString());
        map.put("armor", (client, player) -> {
            StringJoiner joiner = new StringJoiner(", ");
            for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                joiner.add(Registries.ITEM.getId(player.getEquippedStack(slot).getItem()).getPath());
            }
            return joiner.toString();
        });
        map.put("hotbar", (client, player) -> {
            StringJoiner joiner = new StringJoiner(", ");
            for (int i = 0; i < 9; i++) {
                joiner.add((i + 1) + ":" + Registries.ITEM.getId(
                        player.getInventory().getStack(i).getItem()).getPath());
            }
            return joiner.toString();
        });
        map.put("selected_slot", (client, player) ->
                Integer.toString(player.getInventory().getSelectedSlot() + 1));
        map.put("vehicle", (client, player) -> player.getVehicle() == null ? "none"
                : Registries.ENTITY_TYPE.getId(player.getVehicle().getType()).toString());
        map.put("standing_on", (client, player) -> Registries.BLOCK.getId(client.world
                .getBlockState(player.getBlockPos().down()).getBlock()).toString());
        map.put("block_at_feet", (client, player) -> Registries.BLOCK.getId(client.world
                .getBlockState(player.getBlockPos()).getBlock()).toString());
        map.put("block_above_head", (client, player) -> Registries.BLOCK.getId(client.world
                .getBlockState(player.getBlockPos().up(2)).getBlock()).toString());
        map.put("looking_at", (client, player) -> client.crosshairTarget == null ? "none"
                : client.crosshairTarget.toString());
        map.put("screen", (client, player) -> client.currentScreen == null ? "none"
                : client.currentScreen.getClass().getSimpleName());
        map.put("difficulty", (client, player) ->
                client.world.getDifficulty().getName());
        map.put("weather", (client, player) -> client.world.isThundering() ? "thunder"
                : client.world.isRaining() ? "rain" : "clear");
        map.put("time", (client, player) -> {
            long time = client.world.getTimeOfDay() % 24000L;
            return time + " (" + (time < 12000L ? "day" : "night") + ")";
        });
        map.put("effects", (client, player) -> {
            if (player.getStatusEffects().isEmpty()) return "none";
            StringJoiner joiner = new StringJoiner(", ");
            for (StatusEffectInstance instance : player.getStatusEffects()) {
                joiner.add(instance.getEffectType().getIdAsString()
                        + " " + (instance.getAmplifier() + 1)
                        + " (" + instance.getDuration() / 20 + "s)");
            }
            return joiner.toString();
        });
        map.put("players", (client, player) -> {
            StringJoiner joiner = new StringJoiner(", ");
            if (player.networkHandler != null) {
                for (var entry : player.networkHandler.getPlayerList()) {
                    joiner.add(entry.getProfile().name());
                }
            }
            return joiner.length() == 0 ? "none" : joiner.toString();
        });
        map.put("spawn_point", (client, player) ->
                client.world.getSpawnPoint().getPos().toShortString());
        map.put("sounds", (client, player) -> {
            var recent = dev.talos.client.rules.EventRuleEngine.recentSounds(5.0);
            return recent.isEmpty() ? "none in the last 5s" : String.join(", ", recent);
        });
        map.put("particles", (client, player) -> {
            var recent = dev.talos.client.rules.EventRuleEngine.recentParticles(3.0);
            return recent.isEmpty() ? "none in the last 3s" : String.join(", ", recent);
        });
        map.put("crosshair_particles", (client, player) -> {
            var recent = dev.talos.client.rules.EventRuleEngine
                    .particlesOnCrosshair(player, 2.0, 2.0);
            return recent.isEmpty() ? "none near the look ray (2s, 2m)"
                    : String.join(", ", recent);
        });
        map.put("sign", (client, player) -> {
            var be = crosshairBlockEntity(client);
            if (!(be instanceof net.minecraft.block.entity.SignBlockEntity sign)) {
                return "not looking at a sign";
            }
            StringJoiner lines = new StringJoiner(" / ");
            for (var line : sign.getFrontText().getMessages(false)) {
                if (!line.getString().isEmpty()) lines.add(line.getString());
            }
            return lines.length() == 0 ? "(blank sign)" : lines.toString();
        });
        map.put("lectern", (client, player) -> {
            var be = crosshairBlockEntity(client);
            if (!(be instanceof net.minecraft.block.entity.LecternBlockEntity lectern)) {
                return "not looking at a lectern";
            }
            if (!lectern.hasBook()) return "empty lectern";
            var book = lectern.getBook();
            return Registries.ITEM.getId(book.getItem()) + " (page "
                    + (lectern.getCurrentPage() + 1) + ")"
                    + (book.getCustomName() != null ? " \"" + book.getCustomName().getString() + "\"" : "");
        });
        map.put("skull", (client, player) -> {
            var be = crosshairBlockEntity(client);
            if (!(be instanceof net.minecraft.block.entity.SkullBlockEntity skull)) {
                return "not looking at a skull";
            }
            var owner = skull.getOwner();
            return owner == null ? "no owner" : owner.toString();
        });
        map.put("banner", (client, player) -> {
            var be = crosshairBlockEntity(client);
            if (!(be instanceof net.minecraft.block.entity.BannerBlockEntity banner)) {
                return "not looking at a banner";
            }
            return banner.getColorForState().getId() + " with "
                    + banner.getPatterns().layers().size() + " pattern layer(s)";
        });
        map.put("campfire", (client, player) -> {
            var be = crosshairBlockEntity(client);
            if (!(be instanceof net.minecraft.block.entity.CampfireBlockEntity campfire)) {
                return "not looking at a campfire";
            }
            StringJoiner items = new StringJoiner(", ");
            for (var stack : campfire.getItemsBeingCooked()) {
                if (!stack.isEmpty()) items.add(Registries.ITEM.getId(stack.getItem()).getPath());
            }
            return items.length() == 0 ? "nothing cooking" : items.toString();
        });
        map.put("item_frame", (client, player) -> {
            if (client.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult hit
                    && hit.getEntity() instanceof net.minecraft.entity.decoration.ItemFrameEntity frame) {
                var stack = frame.getHeldItemStack();
                return stack.isEmpty() ? "empty frame"
                        : Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
            }
            return "not looking at an item frame";
        });

        // Booleans.
        map.put("sneaking", bool((client, player) -> player.isSneaking()));
        map.put("sprinting", bool((client, player) -> player.isSprinting()));
        map.put("swimming", bool((client, player) -> player.isSwimming()));
        map.put("gliding", bool((client, player) -> player.isGliding()));
        map.put("underwater", bool((client, player) -> player.isSubmergedInWater()));
        map.put("on_fire", bool((client, player) -> player.isOnFire()));
        map.put("on_ground", bool((client, player) -> player.isOnGround()));
        map.put("climbing", bool((client, player) -> player.isClimbing()));
        map.put("blocking", bool((client, player) -> player.isBlocking()));
        map.put("using_item", bool((client, player) -> player.isUsingItem()));
        map.put("sleeping", bool((client, player) -> player.isSleeping()));
        map.put("frozen", bool((client, player) -> player.isFrozen()));
        map.put("hurt", bool((client, player) -> player.hurtTime > 0));
        map.put("moving", bool((client, player) ->
                player.getVelocity().horizontalLengthSquared() > 1.0E-4));
        map.put("window_focused", bool((client, player) -> client.isWindowFocused()));
        map.put("raining", bool((client, player) -> client.world.isRaining()));
        map.put("day", bool((client, player) ->
                client.world.getTimeOfDay() % 24000L < 12000L));
        map.put("inventory_full", bool((client, player) ->
                player.getInventory().getEmptySlot() == -1));
        map.put("container_open", bool((client, player) ->
                player.currentScreenHandler.slots.stream()
                        .anyMatch(slot -> slot.inventory != player.getInventory())));
        return map;
    }

    /** {@code /talos get slot <name>} — exact contents of one named slot. */
    private static int slot(FabricClientCommandSource source, String name) {
        MinecraftClient client = source.getClient();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            source.sendError(Text.literal("No player is loaded"));
            return 0;
        }
        net.minecraft.item.ItemStack stack = null;
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("hotbar.")) {
            int index = parseIndex(key.substring(7), 1, 9);
            if (index > 0) stack = player.getInventory().getStack(index - 1);
        } else if (key.startsWith("inv.")) {
            int index = parseIndex(key.substring(4), 1, 27);
            if (index > 0) stack = player.getInventory().getStack(8 + index);
        } else if (key.startsWith("container.")) {
            int index = parseIndex(key.substring(10), 1, 1024);
            int seen = 0;
            for (net.minecraft.screen.slot.Slot handlerSlot : player.currentScreenHandler.slots) {
                if (handlerSlot.inventory == player.getInventory()) continue;
                if (++seen == index) { stack = handlerSlot.getStack(); break; }
            }
        } else {
            stack = switch (key) {
                case "head", "helmet" -> player.getEquippedStack(EquipmentSlot.HEAD);
                case "chest", "chestplate" -> player.getEquippedStack(EquipmentSlot.CHEST);
                case "legs", "leggings" -> player.getEquippedStack(EquipmentSlot.LEGS);
                case "feet", "boots" -> player.getEquippedStack(EquipmentSlot.FEET);
                case "offhand" -> player.getOffHandStack();
                case "held", "hand" -> player.getMainHandStack();
                case "cursor" -> player.currentScreenHandler.getCursorStack();
                // Horse screen layout: container slot 1 = saddle, 2 = body armor.
                case "saddle" -> containerSlot(player, 1);
                case "horsearmor", "horse_armor" -> containerSlot(player, 2);
                default -> null;
            };
        }
        if (stack == null) {
            source.sendError(Text.literal("Unknown slot '" + name
                    + "' (hotbar.1-9, inv.1-27, head/chest/legs/feet, offhand, held, cursor, "
                    + "container.N, saddle, horsearmor)"));
            return 0;
        }
        source.sendFeedback(Text.literal("§bslot[" + key + "]§f = " + (stack.isEmpty() ? "empty"
                : Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount())));
        return 1;
    }

    private static net.minecraft.item.ItemStack containerSlot(ClientPlayerEntity player,
            int oneBasedIndex) {
        int seen = 0;
        for (net.minecraft.screen.slot.Slot handlerSlot : player.currentScreenHandler.slots) {
            if (handlerSlot.inventory == player.getInventory()) continue;
            if (++seen == oneBasedIndex) return handlerSlot.getStack();
        }
        return net.minecraft.item.ItemStack.EMPTY;
    }

    private static int parseIndex(String raw, int min, int max) {
        try {
            int value = Integer.parseInt(raw);
            return value >= min && value <= max ? value : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** Iteration primitive: nth match of a selector, nearest-first, Python-style index. */
    private static int entityAt(FabricClientCommandSource source, String token, int index) {
        MinecraftClient client = source.getClient();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            source.sendError(Text.literal("No world is loaded"));
            return 0;
        }
        String[] error = new String[1];
        EntitySelector selector = EntitySelector.parse(token, error);
        if (selector == null) { source.sendError(Text.literal(error[0])); return 0; }
        java.util.List<net.minecraft.entity.Entity> matches =
                selector.select(client, selector.kind() != EntitySelector.Kind.SELF);
        net.minecraft.entity.Entity target = Indexed.select(matches, index);
        if (target == null) {
            source.sendError(Text.literal("entity[" + index + "]: "
                    + Indexed.rangeHint(matches.size())));
            return 0;
        }
        source.sendFeedback(Text.literal(String.format(Locale.ROOT,
                "§bentity[%d]§f = %s @ %.1f %.1f %.1f (%.1fm)", index,
                dev.talos.client.rules.EventRuleEngine.entityLabel(target),
                target.getX(), target.getY(), target.getZ(),
                Math.sqrt(target.squaredDistanceTo(player)))));
        return 1;
    }

    /** Iteration primitive: nth-nearest matching block within 32, Python-style index. */
    private static int blockAt(FabricClientCommandSource source, String blockId, int index) {
        MinecraftClient client = source.getClient();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            source.sendError(Text.literal("No world is loaded"));
            return 0;
        }
        var id = net.minecraft.util.Identifier.tryParse(blockId.contains(":")
                ? blockId : "minecraft:" + blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            source.sendError(Text.literal("Unknown block: " + blockId));
            return 0;
        }
        var block = Registries.BLOCK.get(id);
        var center = player.getBlockPos();
        java.util.List<net.minecraft.util.math.BlockPos> matches = new java.util.ArrayList<>();
        for (var pos : net.minecraft.util.math.BlockPos.iterate(
                center.add(-32, -32, -32), center.add(32, 32, 32))) {
            if (client.world.getBlockState(pos).getBlock() == block) matches.add(pos.toImmutable());
        }
        matches.sort(java.util.Comparator.comparingDouble(
                pos -> pos.getSquaredDistance(center)));
        var target = Indexed.select(matches, index);
        if (target == null) {
            source.sendError(Text.literal("blockpos[" + index + "]: "
                    + Indexed.rangeHint(matches.size()) + " within 32"));
            return 0;
        }
        source.sendFeedback(Text.literal(String.format(Locale.ROOT,
                "§bblockpos[%d]§f = %d %d %d (%.1fm)", index,
                target.getX(), target.getY(), target.getZ(),
                Math.sqrt(target.getSquaredDistance(center)))));
        return 1;
    }

    /** {@code /talos get block <x> <y> <z>} (~-relative) or {@code ... direction <yaw> <pitch>}. */
    public static int blockAtPos(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context,
            net.minecraft.util.math.BlockPos pos) {
        MinecraftClient client = context.getSource().getClient();
        if (client.world == null) {
            context.getSource().sendError(Text.literal("No world is loaded"));
            return 0;
        }
        var state = client.world.getBlockState(pos);
        context.getSource().sendFeedback(Text.literal(String.format(Locale.ROOT,
                "§bblock[%d %d %d]§f = %s", pos.getX(), pos.getY(), pos.getZ(),
                Registries.BLOCK.getId(state.getBlock()))));
        return 1;
    }

    private static net.minecraft.block.entity.BlockEntity crosshairBlockEntity(
            MinecraftClient client) {
        if (client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
                && client.crosshairTarget.getType()
                        == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            return client.world.getBlockEntity(hit.getBlockPos());
        }
        return null;
    }

    private interface BoolGetter {
        boolean get(MinecraftClient client, ClientPlayerEntity player);
    }

    private static BiFunction<MinecraftClient, ClientPlayerEntity, String> bool(BoolGetter getter) {
        return (client, player) -> Boolean.toString(getter.get(client, player));
    }
}
