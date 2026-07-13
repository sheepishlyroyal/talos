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
                    "Observables: " + String.join(", ", GETTERS.keySet())));
            return 1;
        }));
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

    private interface BoolGetter {
        boolean get(MinecraftClient client, ClientPlayerEntity player);
    }

    private static BiFunction<MinecraftClient, ClientPlayerEntity, String> bool(BoolGetter getter) {
        return (client, player) -> Boolean.toString(getter.get(client, player));
    }
}
