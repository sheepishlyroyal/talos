package dev.talos.mixin;

import dev.talos.client.rules.EventRuleEngine;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
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
    /**
     * Script input capture: while a script is awaiting talos.input(), the next PLAIN chat
     * message is consumed here — cancelled before it is sent to the server — and handed to
     * the script. Commands take the sendChatCommand path and are never captured.
     */
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void talos$captureScriptInput(String message, CallbackInfo ci) {
        if (dev.talos.client.script.ScriptInputGate.offer(message)) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "§bTalos §7» §finput received: §7" + message), false);
            }
            ci.cancel();
        }
    }

    /**
     * Rubberband detection: this packet is the server force-setting the client's position
     * (teleport, anti-cheat correction, lag resync). Every prediction the pathing follower
     * made is now fiction — tell it so it can release inputs, mute its desync watchdog,
     * and resume the closed loop from the corrected position.
     */
    @Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
    private void talos$onPlayerPositionLook(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        dev.talos.client.pathing.sim.SimFollowTask.onServerCorrection();
    }

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

    @Inject(method = "onEntityStatus", at = @At("HEAD"))
    private void talos$onEntityStatus(
            net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket packet,
            CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || client.world == null) return;
        EventRuleEngine.onEntityStatus(packet.getEntity(client.world), packet.getStatus());
    }

    @Inject(method = "onParticle", at = @At("HEAD"))
    private void talos$onParticle(
            net.minecraft.network.packet.s2c.play.ParticleS2CPacket packet, CallbackInfo ci) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        EventRuleEngine.onParticle(
                net.minecraft.registry.Registries.PARTICLE_TYPE.getId(
                        packet.getParameters().getType()).toString(),
                packet.getX(), packet.getY(), packet.getZ());
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
