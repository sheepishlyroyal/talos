package dev.glade.mixin;

import dev.glade.client.rules.EventRuleEngine;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Decoded high-value packets for the event-rule engine. Handlers are entered twice (netty
 * thread schedules onto the client thread), so every hook no-ops off-thread; the on-thread
 * pass runs at HEAD — for pickups that is before vanilla discards the item entity, which is
 * what makes exact stack + collector attribution possible.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onItemPickupAnimation", at = @At("HEAD"))
    private void talos$onItemPickup(ItemPickupAnimationS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        EventRuleEngine.onItemPickup(packet.getEntityId(), packet.getCollectorEntityId(),
                packet.getStackAmount());
    }

    @Inject(method = "onExplosion", at = @At("HEAD"))
    private void talos$onExplosion(ExplosionS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        EventRuleEngine.onExplosion(packet.center());
    }

    @Inject(method = "onBossBar", at = @At("HEAD"))
    private void talos$onBossBar(BossBarS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        packet.accept(new BossBarS2CPacket.Consumer() {
            @Override public void add(UUID uuid, Text name, float percent, BossBar.Color color,
                    BossBar.Style style, boolean darkenSky, boolean dragonMusic, boolean fog) {
                EventRuleEngine.onBossBarAdd(name.getString(), percent);
            }

            @Override public void remove(UUID uuid) {
                EventRuleEngine.onBossBarRemove();
            }

            @Override public void updateProgress(UUID uuid, float percent) {
                EventRuleEngine.onBossBarProgress(percent);
            }

            @Override public void updateName(UUID uuid, Text name) {
                EventRuleEngine.onBossBarName(name.getString());
            }
        });
    }
}
