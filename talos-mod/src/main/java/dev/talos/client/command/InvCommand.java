package dev.talos.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory automation over the CURRENT screen handler: the player inventory when nothing is
 * open, or the full chest/double-chest layout while a container screen is open. Slot numbers
 * are the handler indices printed by {@code /talos inv list}.
 */
public final class InvCommand {
    private InvCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        return ClientCommands.literal("inv")
                .then(ClientCommands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(ClientCommands.literal("move")
                        .then(ClientCommands.argument("from", IntegerArgumentType.integer(0))
                                .then(ClientCommands.argument("to", IntegerArgumentType.integer(0))
                                        .executes(context -> move(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "from"),
                                                IntegerArgumentType.getInteger(context, "to"))))))
                .then(ClientCommands.literal("hotbar")
                        .then(ClientCommands.argument("from", IntegerArgumentType.integer(0))
                                .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                                        .executes(context -> hotbar(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "from"),
                                                IntegerArgumentType.getInteger(context, "slot"))))))
                .then(ClientCommands.literal("deposit")
                        .then(ClientCommands.literal("all")
                                .executes(context -> transfer(context.getSource(), null, true)))
                        .then(ClientCommands.argument("item", IdArgumentType.itemId())
                                .executes(context -> transfer(context.getSource(),
                                        StringArgumentType.getString(context, "item"), true))))
                .then(ClientCommands.literal("withdraw")
                        .then(ClientCommands.literal("all")
                                .executes(context -> transfer(context.getSource(), null, false)))
                        .then(ClientCommands.argument("item", IdArgumentType.itemId())
                                .executes(context -> transfer(context.getSource(),
                                        StringArgumentType.getString(context, "item"), false))))
                .then(ClientCommands.literal("armor")
                        .then(armorPiece("helmet", EquipmentSlot.HEAD))
                        .then(armorPiece("chestplate", EquipmentSlot.CHEST))
                        .then(armorPiece("leggings", EquipmentSlot.LEGS))
                        .then(armorPiece("boots", EquipmentSlot.FEET)));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> armorPiece(String name,
            EquipmentSlot slot) {
        return ClientCommands.literal(name)
                .then(ClientCommands.argument("from", IntegerArgumentType.integer(0))
                        .executes(context -> equipArmor(context.getSource(),
                                IntegerArgumentType.getInteger(context, "from"), slot)));
    }

    private static int list(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        AbstractContainerMenu handler = handler(source);
        if (handler == null) return 0;
        int shown = 0;
        StringBuilder container = new StringBuilder();
        StringBuilder inventory = new StringBuilder();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            StringBuilder target = slot.container == client.player.getInventory()
                    ? inventory : container;
            target.append(target.isEmpty() ? "" : ", ").append('[').append(i).append("] ")
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())
                    .append('x').append(stack.getCount());
            shown++;
        }
        if (!container.isEmpty()) source.sendFeedback(Component.literal("§bContainer:§f " + container));
        if (!inventory.isEmpty()) source.sendFeedback(Component.literal("§bInventory:§f " + inventory));
        if (shown == 0) source.sendFeedback(Component.literal("Every visible slot is empty"));
        return 1;
    }

    private static int move(FabricClientCommandSource source, int from, int to) {
        Minecraft client = source.getClient();
        AbstractContainerMenu handler = handler(source);
        if (handler == null) return 0;
        if (from >= handler.slots.size() || to >= handler.slots.size()) {
            source.sendError(Component.literal("Slot out of range (use /talos inv list)"));
            return 0;
        }
        click(client, handler, from, 0, ContainerInput.PICKUP);
        click(client, handler, to, 0, ContainerInput.PICKUP);
        if (!handler.getCarried().isEmpty()) click(client, handler, from, 0, ContainerInput.PICKUP);
        source.sendFeedback(Component.literal("Moved slot " + from + " -> " + to));
        return 1;
    }

    private static int hotbar(FabricClientCommandSource source, int from, int hotbarSlot) {
        Minecraft client = source.getClient();
        AbstractContainerMenu handler = handler(source);
        if (handler == null) return 0;
        if (from >= handler.slots.size()) {
            source.sendError(Component.literal("Slot out of range (use /talos inv list)"));
            return 0;
        }
        // SWAP with button=hotbar index is vanilla's "press a number key over a slot".
        click(client, handler, from, hotbarSlot - 1, ContainerInput.SWAP);
        source.sendFeedback(Component.literal("Swapped slot " + from + " into hotbar " + hotbarSlot));
        return 1;
    }

    /** Quick-moves matching stacks between the player inventory and the open container. */
    private static int transfer(FabricClientCommandSource source, String itemId, boolean deposit) {
        Minecraft client = source.getClient();
        AbstractContainerMenu handler = handler(source);
        if (handler == null) return 0;
        boolean containerOpen = handler.slots.stream()
                .anyMatch(slot -> slot.container != client.player.getInventory());
        if (!containerOpen) {
            source.sendError(Component.literal("Open a chest first — there is no container to "
                    + (deposit ? "deposit into" : "withdraw from")));
            return 0;
        }
        Identifier filter = null;
        if (itemId != null) {
            filter = Identifier.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
            if (filter == null) {
                source.sendError(Component.literal("Invalid item id: " + itemId));
                return 0;
            }
        }
        int moved = 0;
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            boolean playerSide = slot.container == client.player.getInventory();
            if (playerSide != deposit) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (filter != null && !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(filter)) continue;
            click(client, handler, i, 0, ContainerInput.QUICK_MOVE);
            moved++;
        }
        source.sendFeedback(Component.literal((deposit ? "Deposited " : "Withdrew ") + moved
                + " stack" + (moved == 1 ? "" : "s")
                + (filter != null ? " of " + filter.getPath() : "")));
        return 1;
    }

    private static int equipArmor(FabricClientCommandSource source, int from, EquipmentSlot armor) {
        Minecraft client = source.getClient();
        AbstractContainerMenu handler = handler(source);
        if (handler == null) return 0;
        int inventoryIndex = switch (armor) {
            case FEET -> 36; case LEGS -> 37; case CHEST -> 38; case HEAD -> 39;
            default -> -1;
        };
        int destination = -1;
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            if (slot.container == client.player.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                destination = i;
                break;
            }
        }
        if (destination < 0) {
            source.sendError(Component.literal("The open screen does not expose armor slots"
                    + " — close the chest and use the player inventory"));
            return 0;
        }
        if (from >= handler.slots.size()) {
            source.sendError(Component.literal("Slot out of range (use /talos inv list)"));
            return 0;
        }
        click(client, handler, from, 0, ContainerInput.PICKUP);
        click(client, handler, destination, 0, ContainerInput.PICKUP);
        if (!handler.getCarried().isEmpty()) click(client, handler, from, 0, ContainerInput.PICKUP);
        source.sendFeedback(Component.literal("Equipped " + armor.getName().toLowerCase(Locale.ROOT)));
        return 1;
    }

    private static AbstractContainerMenu handler(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        if (client.player == null || client.gameMode == null) {
            source.sendError(Component.literal("No player is loaded"));
            return null;
        }
        return client.player.containerMenu;
    }

    private static void click(Minecraft client, AbstractContainerMenu handler, int slot, int button,
            ContainerInput action) {
        client.gameMode.handleContainerInput(handler.containerId, slot, button, action, client.player);
    }
}
