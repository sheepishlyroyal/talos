package dev.talos.client.action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;

/** Selects a conservative melee choice from the hotbar. */
public final class WeaponSelector {
    private WeaponSelector() {
    }

    public static int findBestMeleeHotbarSlot(LocalPlayer player) {
        int bestSlot = player.getInventory().getSelectedSlot();
        int bestScore = score(player.getInventory().getItem(bestSlot));
        for (int slot = 0; slot < 9; slot++) {
            int score = score(player.getInventory().getItem(slot));
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    public static void select(Minecraft client, int hotbarSlot) {
        if (client.player == null || client.gameMode == null
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
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
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
