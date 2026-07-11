package dev.glade.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Selects a conservative melee choice from the hotbar. */
public final class WeaponSelector {
    private WeaponSelector() {
    }

    public static int findBestMeleeHotbarSlot(ClientPlayerEntity player) {
        int bestSlot = player.getInventory().getSelectedSlot();
        int bestScore = score(player.getInventory().getStack(bestSlot));
        for (int slot = 0; slot < 9; slot++) {
            int score = score(player.getInventory().getStack(slot));
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    public static void select(MinecraftClient client, int hotbarSlot) {
        if (client.player == null || client.interactionManager == null
                || hotbarSlot < 0 || hotbarSlot >= 9) {
            return;
        }
        // Setting the selected slot is enough; the client syncs it to the server on its next tick
        // (ClientPlayerInteractionManager.syncSelectedSlot is private and fires automatically).
        client.player.getInventory().setSelectedSlot(hotbarSlot);
    }

    static int score(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        // The 1.21 item refactor removed SwordItem (AxeItem remains), so identify swords/maces by
        // their registry id path. Deterministic sword > mace > axe > other melee heuristic (v1).
        Identifier id = Registries.ITEM.getId(stack.getItem());
        String path = id.getPath();
        if (path.contains("sword")) {
            return 300;
        }
        if (path.equals("mace")) {
            return 250;
        }
        if (stack.getItem() instanceof AxeItem) {
            return 200;
        }
        if (path.contains("trident")) {
            return 150;
        }
        return 0;
    }
}
