package dev.glade.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Inventory automation over the CURRENT screen handler: the player inventory when nothing is
 * open, or the full chest/double-chest layout while a container screen is open. Slot numbers
 * are the handler indices printed by {@code /glade inv list}.
 */
public final class InvCommand {
    private InvCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        return ClientCommandManager.literal("inv")
                .then(ClientCommandManager.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(ClientCommandManager.literal("move")
                        .then(ClientCommandManager.argument("from", IntegerArgumentType.integer(0))
                                .then(ClientCommandManager.argument("to", IntegerArgumentType.integer(0))
                                        .executes(context -> move(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "from"),
                                                IntegerArgumentType.getInteger(context, "to"))))))
                .then(ClientCommandManager.literal("hotbar")
                        .then(ClientCommandManager.argument("from", IntegerArgumentType.integer(0))
                                .then(ClientCommandManager.argument("slot", IntegerArgumentType.integer(1, 9))
                                        .executes(context -> hotbar(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "from"),
                                                IntegerArgumentType.getInteger(context, "slot"))))))
                .then(ClientCommandManager.literal("deposit")
                        .then(ClientCommandManager.literal("all")
                                .executes(context -> transfer(context.getSource(), null, true)))
                        .then(ClientCommandManager.argument("item", StringArgumentType.string())
                                .executes(context -> transfer(context.getSource(),
                                        StringArgumentType.getString(context, "item"), true))))
                .then(ClientCommandManager.literal("withdraw")
                        .then(ClientCommandManager.literal("all")
                                .executes(context -> transfer(context.getSource(), null, false)))
                        .then(ClientCommandManager.argument("item", StringArgumentType.string())
                                .executes(context -> transfer(context.getSource(),
                                        StringArgumentType.getString(context, "item"), false))))
                .then(ClientCommandManager.literal("armor")
                        .then(armorPiece("helmet", EquipmentSlot.HEAD))
                        .then(armorPiece("chestplate", EquipmentSlot.CHEST))
                        .then(armorPiece("leggings", EquipmentSlot.LEGS))
                        .then(armorPiece("boots", EquipmentSlot.FEET)));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> armorPiece(String name,
            EquipmentSlot slot) {
        return ClientCommandManager.literal(name)
                .then(ClientCommandManager.argument("from", IntegerArgumentType.integer(0))
                        .executes(context -> equipArmor(context.getSource(),
                                IntegerArgumentType.getInteger(context, "from"), slot)));
    }

    private static int list(FabricClientCommandSource source) {
        MinecraftClient client = source.getClient();
        ScreenHandler handler = handler(source);
        if (handler == null) return 0;
        int shown = 0;
        StringBuilder container = new StringBuilder();
        StringBuilder inventory = new StringBuilder();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            StringBuilder target = slot.inventory == client.player.getInventory()
                    ? inventory : container;
            target.append(target.isEmpty() ? "" : ", ").append('[').append(i).append("] ")
                    .append(Registries.ITEM.getId(stack.getItem()).getPath())
                    .append('x').append(stack.getCount());
            shown++;
        }
        if (!container.isEmpty()) source.sendFeedback(Text.literal("§bContainer:§f " + container));
        if (!inventory.isEmpty()) source.sendFeedback(Text.literal("§bInventory:§f " + inventory));
        if (shown == 0) source.sendFeedback(Text.literal("Every visible slot is empty"));
        return 1;
    }

    private static int move(FabricClientCommandSource source, int from, int to) {
        MinecraftClient client = source.getClient();
        ScreenHandler handler = handler(source);
        if (handler == null) return 0;
        if (from >= handler.slots.size() || to >= handler.slots.size()) {
            source.sendError(Text.literal("Slot out of range (use /glade inv list)"));
            return 0;
        }
        click(client, handler, from, 0, SlotActionType.PICKUP);
        click(client, handler, to, 0, SlotActionType.PICKUP);
        if (!handler.getCursorStack().isEmpty()) click(client, handler, from, 0, SlotActionType.PICKUP);
        source.sendFeedback(Text.literal("Moved slot " + from + " -> " + to));
        return 1;
    }

    private static int hotbar(FabricClientCommandSource source, int from, int hotbarSlot) {
        MinecraftClient client = source.getClient();
        ScreenHandler handler = handler(source);
        if (handler == null) return 0;
        if (from >= handler.slots.size()) {
            source.sendError(Text.literal("Slot out of range (use /glade inv list)"));
            return 0;
        }
        // SWAP with button=hotbar index is vanilla's "press a number key over a slot".
        click(client, handler, from, hotbarSlot - 1, SlotActionType.SWAP);
        source.sendFeedback(Text.literal("Swapped slot " + from + " into hotbar " + hotbarSlot));
        return 1;
    }

    /** Quick-moves matching stacks between the player inventory and the open container. */
    private static int transfer(FabricClientCommandSource source, String itemId, boolean deposit) {
        MinecraftClient client = source.getClient();
        ScreenHandler handler = handler(source);
        if (handler == null) return 0;
        boolean containerOpen = handler.slots.stream()
                .anyMatch(slot -> slot.inventory != client.player.getInventory());
        if (!containerOpen) {
            source.sendError(Text.literal("Open a chest first — there is no container to "
                    + (deposit ? "deposit into" : "withdraw from")));
            return 0;
        }
        Identifier filter = null;
        if (itemId != null) {
            filter = Identifier.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
            if (filter == null) {
                source.sendError(Text.literal("Invalid item id: " + itemId));
                return 0;
            }
        }
        int moved = 0;
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            boolean playerSide = slot.inventory == client.player.getInventory();
            if (playerSide != deposit) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            if (filter != null && !Registries.ITEM.getId(stack.getItem()).equals(filter)) continue;
            click(client, handler, i, 0, SlotActionType.QUICK_MOVE);
            moved++;
        }
        source.sendFeedback(Text.literal((deposit ? "Deposited " : "Withdrew ") + moved
                + " stack" + (moved == 1 ? "" : "s")
                + (filter != null ? " of " + filter.getPath() : "")));
        return 1;
    }

    private static int equipArmor(FabricClientCommandSource source, int from, EquipmentSlot armor) {
        MinecraftClient client = source.getClient();
        ScreenHandler handler = handler(source);
        if (handler == null) return 0;
        int inventoryIndex = switch (armor) {
            case FEET -> 36; case LEGS -> 37; case CHEST -> 38; case HEAD -> 39;
            default -> -1;
        };
        int destination = -1;
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            if (slot.inventory == client.player.getInventory() && slot.getIndex() == inventoryIndex) {
                destination = i;
                break;
            }
        }
        if (destination < 0) {
            source.sendError(Text.literal("The open screen does not expose armor slots"
                    + " — close the chest and use the player inventory"));
            return 0;
        }
        if (from >= handler.slots.size()) {
            source.sendError(Text.literal("Slot out of range (use /glade inv list)"));
            return 0;
        }
        click(client, handler, from, 0, SlotActionType.PICKUP);
        click(client, handler, destination, 0, SlotActionType.PICKUP);
        if (!handler.getCursorStack().isEmpty()) click(client, handler, from, 0, SlotActionType.PICKUP);
        source.sendFeedback(Text.literal("Equipped " + armor.getName().toLowerCase(Locale.ROOT)));
        return 1;
    }

    private static ScreenHandler handler(FabricClientCommandSource source) {
        MinecraftClient client = source.getClient();
        if (client.player == null || client.interactionManager == null) {
            source.sendError(Text.literal("No player is loaded"));
            return null;
        }
        return client.player.currentScreenHandler;
    }

    private static void click(MinecraftClient client, ScreenHandler handler, int slot, int button,
            SlotActionType action) {
        client.interactionManager.clickSlot(handler.syncId, slot, button, action, client.player);
    }
}
