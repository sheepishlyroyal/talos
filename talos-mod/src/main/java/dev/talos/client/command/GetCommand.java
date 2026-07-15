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
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * {@code /talos get <observable>} — instant readout of every value the rule engine can watch:
 * all numeric metrics (identical to what rules compare against) plus string and boolean
 * observables. {@code /talos get list} enumerates them.
 */
public final class GetCommand {
    private static final Map<String, BiFunction<Minecraft, LocalPlayer, String>>
            GETTERS = build();

    private GetCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        LiteralArgumentBuilder<FabricClientCommandSource> get = ClientCommands.literal("get");
        get.then(ClientCommands.literal("list").executes(context -> {
            context.getSource().sendFeedback(Component.literal(
                    "Observables/triggers: " + String.join(", ", catalogNames())
                            + " | slot <hotbar.1-9|inv.1-27|head|chest|legs|feet|offhand|cursor"
                            + "|container.N|saddle|horsearmor> | parameterized trigger usage: "
                            + "entity_* <selector> [radius], block_* <block> [radius], "
                            + "item_count/hotbar_item_count/held_enchant <id>, "
                            + "entered_region/left_region <x1 y1 z1 x2 y2 z2>"));
            return 1;
        }));
        get.then(ClientCommands.literal("entity")
                .then(ClientCommands.argument("selector", SelectorArgumentType.selector())
                        .executes(context -> entityAt(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType
                                        .getString(context, "selector"), 0))
                        .then(ClientCommands.argument("index",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(context -> entityAt(context.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(context, "selector"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(context, "index"))))))
        ;
        get.then(ClientCommands.literal("blockpos")
                .then(ClientCommands.argument("block",
                                IdArgumentType.blockId())
                        .executes(context -> blockAt(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType
                                        .getString(context, "block"), 0))
                        .then(ClientCommands.argument("index",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(context -> blockAt(context.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(context, "block"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(context, "index"))))));
        get.then(ClientCommands.literal("slot")
                .then(ClientCommands.argument("name",
                                com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(context -> slot(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType
                                        .getString(context, "name")))));
        get.then(ClientCommands.literal("entity_location")
                .then(ClientCommands.argument("id",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                        .executes(context -> sendQuery(context.getSource(), "entity_location",
                                Integer.toString(com.mojang.brigadier.arguments.IntegerArgumentType
                                        .getInteger(context, "id"))))));
        for (Map.Entry<String, BiFunction<Minecraft, LocalPlayer, String>> entry
                : GETTERS.entrySet()) {
            get.then(ClientCommands.literal(entry.getKey()).executes(context -> {
                Minecraft client = context.getSource().getClient();
                if (client.player == null || client.level == null) {
                    context.getSource().sendError(Component.literal("No world is loaded"));
                    return 0;
                }
                context.getSource().sendFeedback(Component.literal("§b" + entry.getKey() + "§f = "
                        + entry.getValue().apply(client, client.player)));
                return 1;
            }));
        }
        // Every trigger not already represented by a literal getter lands here. A greedy tail is
        // intentional: selector brackets and quoted names stay intact for the shared parser.
        get.then(ClientCommands.argument("query",
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

    /** Stable union used by list output and regression tests. */
    public static java.util.Set<String> catalogNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(GETTERS.keySet());
        for (Trigger trigger : Trigger.values()) names.add(trigger.id());
        names.add("entity_location");
        return java.util.Collections.unmodifiableSet(names);
    }

    private static int sendQuery(FabricClientCommandSource source, String name, String... args) {
        try {
            String value = value(source.getClient(), name, args);
            source.sendFeedback(Component.literal("§b" + name.replace(' ', '_') + "§f = " + value));
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendError(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    /** Shared catalog entry point used by both Brigadier and the GraalPy bridge. */
    public static String value(Minecraft client, String requested, String... args) {
        if (client.player == null || client.level == null) {
            throw new IllegalArgumentException("No world is loaded");
        }
        String name = requested.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        BiFunction<Minecraft, LocalPlayer, String> getter = GETTERS.get(name);
        if (getter != null) return getter.apply(client, client.player);

        if (name.equals("entity_location") || name.equals("mob_location")) {
            requireArgs(name, args, 1);
            final int id;
            try { id = Integer.parseInt(args[0]); }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("entity_location needs a numeric mob/entity id");
            }
            var entity = client.level.getEntity(id);
            if (entity == null) throw new IllegalArgumentException("No loaded entity with id " + id);
            return String.format(Locale.ROOT, "%s @ %.3f %.3f %.3f",
                    EventRuleEngine.entityLabel(entity), entity.getX(), entity.getY(), entity.getZ());
        }

        final Trigger trigger;
        try { trigger = Trigger.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
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
            return number(EventRuleEngine.parameterValue(trigger, client, client.player,
                    args[0], radius, null));
        }
        if (trigger.kind == Kind.BLOCK_COUNT || trigger.kind == Kind.BLOCK_PRESENCE) {
            requireArgs(name, args, 1);
            double radius = args.length > 1 ? parseDouble(name, args[1]) : 16.0;
            return number(EventRuleEngine.parameterValue(trigger, client, client.player,
                    args[0], radius, null));
        }
        if (trigger.kind == Kind.ITEM_COUNT) {
            requireArgs(name, args, 1);
            return number(EventRuleEngine.parameterValue(trigger, client, client.player,
                    args[0], 0, null));
        }
        if (trigger.kind == Kind.REGION) {
            requireArgs(name, args, 6);
            double[] region = new double[6];
            for (int i = 0; i < 6; i++) region[i] = parseDouble(name, args[i]);
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

    private static String number(double value) {
        return value == Math.floor(value) && Math.abs(value) < 1.0E12
                ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.3f", value);
    }

    /** Whitespace tokenizer that preserves quoted selector names and bracket expressions. */
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

    private static Map<String, BiFunction<Minecraft, LocalPlayer, String>> build() {
        Map<String, BiFunction<Minecraft, LocalPlayer, String>> map =
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
        map.put("block_position", (client, player) -> player.blockPosition().toShortString());
        map.put("yaw", (client, player) -> String.format(Locale.ROOT, "%.1f", player.getYRot()));
        map.put("pitch", (client, player) -> String.format(Locale.ROOT, "%.1f", player.getXRot()));
        map.put("dimension", (client, player) ->
                client.level.dimension().identifier().toString());
        map.put("biome", (client, player) ->
                client.level.getBiome(player.blockPosition()).getRegisteredName());
        map.put("held_item", (client, player) ->
                BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString()
                        + " x" + player.getMainHandItem().getCount());
        map.put("offhand_item", (client, player) ->
                BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem()).toString());
        map.put("armor", (client, player) -> {
            StringJoiner joiner = new StringJoiner(", ");
            for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                joiner.add(BuiltInRegistries.ITEM.getKey(player.getItemBySlot(slot).getItem()).getPath());
            }
            return joiner.toString();
        });
        map.put("hotbar", (client, player) -> {
            StringJoiner joiner = new StringJoiner(", ");
            for (int i = 0; i < 9; i++) {
                joiner.add((i + 1) + ":" + BuiltInRegistries.ITEM.getKey(
                        player.getInventory().getItem(i).getItem()).getPath());
            }
            return joiner.toString();
        });
        map.put("selected_slot", (client, player) ->
                Integer.toString(player.getInventory().getSelectedSlot() + 1));
        map.put("vehicle", (client, player) -> player.getVehicle() == null ? "none"
                : BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString());
        map.put("standing_on", (client, player) -> BuiltInRegistries.BLOCK.getKey(client.level
                .getBlockState(player.blockPosition().below()).getBlock()).toString());
        map.put("block_at_feet", (client, player) -> BuiltInRegistries.BLOCK.getKey(client.level
                .getBlockState(player.blockPosition()).getBlock()).toString());
        map.put("looking_at", (client, player) -> client.hitResult == null ? "none"
                : client.hitResult.toString());
        map.put("screen", (client, player) -> client.screen == null ? "none"
                : client.screen.getClass().getSimpleName());
        map.put("difficulty", (client, player) ->
                client.level.getDifficulty().getSerializedName());
        map.put("weather", (client, player) -> client.level.isThundering() ? "thunder"
                : client.level.isRaining() ? "rain" : "clear");
        map.put("time", (client, player) -> {
            long time = client.level.getOverworldClockTime() % 24000L;
            return time + " (" + (time < 12000L ? "day" : "night") + ")";
        });
        map.put("effects", (client, player) -> {
            if (player.getActiveEffects().isEmpty()) return "none";
            StringJoiner joiner = new StringJoiner(", ");
            for (MobEffectInstance instance : player.getActiveEffects()) {
                joiner.add(instance.getEffect().getRegisteredName()
                        + " " + (instance.getAmplifier() + 1)
                        + " (" + instance.getDuration() / 20 + "s)");
            }
            return joiner.toString();
        });
        map.put("players", (client, player) -> {
            StringJoiner joiner = new StringJoiner(", ");
            if (player.connection != null) {
                for (var entry : player.connection.getOnlinePlayers()) {
                    joiner.add(entry.getProfile().name());
                }
            }
            return joiner.length() == 0 ? "none" : joiner.toString();
        });
        map.put("spawn_point", (client, player) ->
                client.level.getRespawnData().pos().toShortString());
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
            if (!(be instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) {
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
            if (!(be instanceof net.minecraft.world.level.block.entity.LecternBlockEntity lectern)) {
                return "not looking at a lectern";
            }
            if (!lectern.hasBook()) return "empty lectern";
            var book = lectern.getBook();
            return BuiltInRegistries.ITEM.getKey(book.getItem()) + " (page "
                    + (lectern.getPage() + 1) + ")"
                    + (book.getCustomName() != null ? " \"" + book.getCustomName().getString() + "\"" : "");
        });
        map.put("skull", (client, player) -> {
            var be = crosshairBlockEntity(client);
            if (!(be instanceof net.minecraft.world.level.block.entity.SkullBlockEntity skull)) {
                return "not looking at a skull";
            }
            var owner = skull.getOwnerProfile();
            return owner == null ? "no owner" : owner.toString();
        });
        map.put("banner", (client, player) -> {
            var be = crosshairBlockEntity(client);
            if (!(be instanceof net.minecraft.world.level.block.entity.BannerBlockEntity banner)) {
                return "not looking at a banner";
            }
            return banner.getBaseColor().getName() + " with "
                    + banner.getPatterns().layers().size() + " pattern layer(s)";
        });
        map.put("campfire", (client, player) -> {
            var be = crosshairBlockEntity(client);
            if (!(be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity campfire)) {
                return "not looking at a campfire";
            }
            StringJoiner items = new StringJoiner(", ");
            for (var stack : campfire.getItems()) {
                if (!stack.isEmpty()) items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
            }
            return items.length() == 0 ? "nothing cooking" : items.toString();
        });
        map.put("item_frame", (client, player) -> {
            if (client.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
                    && hit.getEntity() instanceof net.minecraft.world.entity.decoration.ItemFrame frame) {
                var stack = frame.getItem();
                return stack.isEmpty() ? "empty frame"
                        : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
            }
            return "not looking at an item frame";
        });

        // Booleans.
        map.put("sneaking", bool((client, player) -> player.isShiftKeyDown()));
        map.put("sprinting", bool((client, player) -> player.isSprinting()));
        map.put("swimming", bool((client, player) -> player.isSwimming()));
        map.put("gliding", bool((client, player) -> player.isFallFlying()));
        map.put("underwater", bool((client, player) -> player.isUnderWater()));
        map.put("on_fire", bool((client, player) -> player.isOnFire()));
        map.put("on_ground", bool((client, player) -> player.onGround()));
        map.put("climbing", bool((client, player) -> player.onClimbable()));
        map.put("blocking", bool((client, player) -> player.isBlocking()));
        map.put("using_item", bool((client, player) -> player.isUsingItem()));
        map.put("sleeping", bool((client, player) -> player.isSleeping()));
        map.put("frozen", bool((client, player) -> player.isFullyFrozen()));
        map.put("hurt", bool((client, player) -> player.hurtTime > 0));
        map.put("moving", bool((client, player) ->
                player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4));
        map.put("window_focused", bool((client, player) -> client.isWindowActive()));
        map.put("raining", bool((client, player) -> client.level.isRaining()));
        map.put("day", bool((client, player) ->
                client.level.getOverworldClockTime() % 24000L < 12000L));
        map.put("inventory_full", bool((client, player) ->
                player.getInventory().getFreeSlot() == -1));
        map.put("container_open", bool((client, player) ->
                player.containerMenu.slots.stream()
                        .anyMatch(slot -> slot.container != player.getInventory())));
        return map;
    }

    /** {@code /talos get slot <name>} — exact contents of one named slot. */
    private static int slot(FabricClientCommandSource source, String name) {
        Minecraft client = source.getClient();
        LocalPlayer player = client.player;
        if (player == null) {
            source.sendError(Component.literal("No player is loaded"));
            return 0;
        }
        net.minecraft.world.item.ItemStack stack = null;
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("hotbar.")) {
            int index = parseIndex(key.substring(7), 1, 9);
            if (index > 0) stack = player.getInventory().getItem(index - 1);
        } else if (key.startsWith("inv.")) {
            int index = parseIndex(key.substring(4), 1, 27);
            if (index > 0) stack = player.getInventory().getItem(8 + index);
        } else if (key.startsWith("container.")) {
            int index = parseIndex(key.substring(10), 1, 1024);
            int seen = 0;
            for (net.minecraft.world.inventory.Slot handlerSlot : player.containerMenu.slots) {
                if (handlerSlot.container == player.getInventory()) continue;
                if (++seen == index) { stack = handlerSlot.getItem(); break; }
            }
        } else {
            stack = switch (key) {
                case "head", "helmet" -> player.getItemBySlot(EquipmentSlot.HEAD);
                case "chest", "chestplate" -> player.getItemBySlot(EquipmentSlot.CHEST);
                case "legs", "leggings" -> player.getItemBySlot(EquipmentSlot.LEGS);
                case "feet", "boots" -> player.getItemBySlot(EquipmentSlot.FEET);
                case "offhand" -> player.getOffhandItem();
                case "held", "hand" -> player.getMainHandItem();
                case "cursor" -> player.containerMenu.getCarried();
                // Horse screen layout: container slot 1 = saddle, 2 = body armor.
                case "saddle" -> containerSlot(player, 1);
                case "horsearmor", "horse_armor" -> containerSlot(player, 2);
                default -> null;
            };
        }
        if (stack == null) {
            source.sendError(Component.literal("Unknown slot '" + name
                    + "' (hotbar.1-9, inv.1-27, head/chest/legs/feet, offhand, held, cursor, "
                    + "container.N, saddle, horsearmor)"));
            return 0;
        }
        source.sendFeedback(Component.literal("§bslot[" + key + "]§f = " + (stack.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount())));
        return 1;
    }

    private static net.minecraft.world.item.ItemStack containerSlot(LocalPlayer player,
            int oneBasedIndex) {
        int seen = 0;
        for (net.minecraft.world.inventory.Slot handlerSlot : player.containerMenu.slots) {
            if (handlerSlot.container == player.getInventory()) continue;
            if (++seen == oneBasedIndex) return handlerSlot.getItem();
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
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
        Minecraft client = source.getClient();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            source.sendError(Component.literal("No world is loaded"));
            return 0;
        }
        String[] error = new String[1];
        EntitySelector selector = EntitySelector.parse(token, error);
        if (selector == null) { source.sendError(Component.literal(error[0])); return 0; }
        java.util.List<net.minecraft.world.entity.Entity> matches =
                selector.select(client, selector.kind() != EntitySelector.Kind.SELF);
        net.minecraft.world.entity.Entity target = Indexed.select(matches, index);
        if (target == null) {
            source.sendError(Component.literal("entity[" + index + "]: "
                    + Indexed.rangeHint(matches.size())));
            return 0;
        }
        source.sendFeedback(Component.literal(String.format(Locale.ROOT,
                "§bentity[%d]§f = %s @ %.1f %.1f %.1f (%.1fm)", index,
                dev.talos.client.rules.EventRuleEngine.entityLabel(target),
                target.getX(), target.getY(), target.getZ(),
                Math.sqrt(target.distanceToSqr(player)))));
        return 1;
    }

    /** Iteration primitive: nth-nearest matching block within 32, Python-style index. */
    private static int blockAt(FabricClientCommandSource source, String blockId, int index) {
        Minecraft client = source.getClient();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            source.sendError(Component.literal("No world is loaded"));
            return 0;
        }
        var id = net.minecraft.resources.Identifier.tryParse(blockId.contains(":")
                ? blockId : "minecraft:" + blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            source.sendError(Component.literal("Unknown block: " + blockId));
            return 0;
        }
        var block = BuiltInRegistries.BLOCK.getValue(id);
        var center = player.blockPosition();
        java.util.List<net.minecraft.core.BlockPos> matches = new java.util.ArrayList<>();
        for (var pos : net.minecraft.core.BlockPos.betweenClosed(
                center.offset(-32, -32, -32), center.offset(32, 32, 32))) {
            if (client.level.getBlockState(pos).getBlock() == block) matches.add(pos.immutable());
        }
        matches.sort(java.util.Comparator.comparingDouble(
                pos -> pos.distSqr(center)));
        var target = Indexed.select(matches, index);
        if (target == null) {
            source.sendError(Component.literal("blockpos[" + index + "]: "
                    + Indexed.rangeHint(matches.size()) + " within 32"));
            return 0;
        }
        source.sendFeedback(Component.literal(String.format(Locale.ROOT,
                "§bblockpos[%d]§f = %d %d %d (%.1fm)", index,
                target.getX(), target.getY(), target.getZ(),
                Math.sqrt(target.distSqr(center)))));
        return 1;
    }

    /** {@code /talos get block <x> <y> <z>} (~-relative) or {@code ... direction <yaw> <pitch>}. */
    public static int blockAtPos(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context,
            net.minecraft.core.BlockPos pos) {
        Minecraft client = context.getSource().getClient();
        if (client.level == null) {
            context.getSource().sendError(Component.literal("No world is loaded"));
            return 0;
        }
        var state = client.level.getBlockState(pos);
        context.getSource().sendFeedback(Component.literal(String.format(Locale.ROOT,
                "§bblock[%d %d %d]§f = %s", pos.getX(), pos.getY(), pos.getZ(),
                BuiltInRegistries.BLOCK.getKey(state.getBlock()))));
        return 1;
    }

    private static net.minecraft.world.level.block.entity.BlockEntity crosshairBlockEntity(
            Minecraft client) {
        if (client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && client.hitResult.getType()
                        == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return client.level.getBlockEntity(hit.getBlockPos());
        }
        return null;
    }

    private interface BoolGetter {
        boolean get(Minecraft client, LocalPlayer player);
    }

    private static BiFunction<Minecraft, LocalPlayer, String> bool(BoolGetter getter) {
        return (client, player) -> Boolean.toString(getter.get(client, player));
    }
}
