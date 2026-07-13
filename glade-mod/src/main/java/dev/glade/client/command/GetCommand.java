package dev.glade.client.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.glade.client.rules.EventRuleEngine;
import dev.glade.client.rules.EventRuleEngine.Kind;
import dev.glade.client.rules.EventRuleEngine.Trigger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BiFunction;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

/**
 * {@code /glade get <observable>} — instant readout of every value the rule engine can watch:
 * all numeric metrics (identical to what rules compare against) plus string and boolean
 * observables. {@code /glade get list} enumerates them.
 */
public final class GetCommand {
    private static final Map<String, BiFunction<MinecraftClient, ClientPlayerEntity, String>>
            GETTERS = build();

    private GetCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        LiteralArgumentBuilder<FabricClientCommandSource> get = ClientCommandManager.literal("get");
        get.then(ClientCommandManager.literal("list").executes(context -> {
            context.getSource().sendFeedback(Text.literal(
                    "Observables: " + String.join(", ", GETTERS.keySet())
                            + " | slot <hotbar.1-9|inv.1-27|head|chest|legs|feet|offhand|cursor"
                            + "|container.N|saddle|horsearmor>"));
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
                                com.mojang.brigadier.arguments.StringArgumentType.string())
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
        for (Map.Entry<String, BiFunction<MinecraftClient, ClientPlayerEntity, String>> entry
                : GETTERS.entrySet()) {
            get.then(ClientCommandManager.literal(entry.getKey()).executes(context -> {
                MinecraftClient client = context.getSource().getClient();
                if (client.player == null || client.world == null) {
                    context.getSource().sendError(Text.literal("No world is loaded"));
                    return 0;
                }
                context.getSource().sendFeedback(Text.literal("§b" + entry.getKey() + "§f = "
                        + entry.getValue().apply(client, client.player)));
                return 1;
            }));
        }
        return get;
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
            var recent = dev.glade.client.rules.EventRuleEngine.recentSounds(5.0);
            return recent.isEmpty() ? "none in the last 5s" : String.join(", ", recent);
        });
        map.put("particles", (client, player) -> {
            var recent = dev.glade.client.rules.EventRuleEngine.recentParticles(3.0);
            return recent.isEmpty() ? "none in the last 3s" : String.join(", ", recent);
        });
        map.put("crosshair_particles", (client, player) -> {
            var recent = dev.glade.client.rules.EventRuleEngine
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
        java.util.List<net.minecraft.entity.Entity> matches = new java.util.ArrayList<>();
        for (net.minecraft.entity.Entity entity : client.world.getEntities()) {
            if (entity == player && selector.kind() != EntitySelector.Kind.SELF) continue;
            boolean playersOnly = selector.kind() == EntitySelector.Kind.PLAYERS_ALL
                    || selector.kind() == EntitySelector.Kind.PLAYER_NEAREST;
            if (playersOnly && !(entity instanceof net.minecraft.entity.player.PlayerEntity)) continue;
            if (selector.kind() == EntitySelector.Kind.SELF && entity != player) continue;
            if (selector.kind() == EntitySelector.Kind.ENTITIES
                    && !selector.matchesFilters(entity)) continue;
            if (!selector.withinDistance(Math.sqrt(entity.squaredDistanceTo(player)))) continue;
            matches.add(entity);
        }
        matches.sort(java.util.Comparator.comparingDouble(player::squaredDistanceTo));
        net.minecraft.entity.Entity target = Indexed.select(matches, index);
        if (target == null) {
            source.sendError(Text.literal("entity[" + index + "]: "
                    + Indexed.rangeHint(matches.size())));
            return 0;
        }
        source.sendFeedback(Text.literal(String.format(Locale.ROOT,
                "§bentity[%d]§f = %s @ %.1f %.1f %.1f (%.1fm)", index,
                dev.glade.client.rules.EventRuleEngine.entityLabel(target),
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
