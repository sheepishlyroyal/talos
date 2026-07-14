package dev.talos.mixin;

import dev.talos.client.rules.EventRuleEngine;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.BossEvent;
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
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
    /**
     * Script input capture: while a script is awaiting talos.input(), the next PLAIN chat
     * message is consumed here — cancelled before it is sent to the server — and handed to
     * the script. Commands take the sendChatCommand path and are never captured.
     */
    @Inject(method = "sendChat(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void talos$captureScriptInput(String message, CallbackInfo ci) {
        if (dev.talos.client.script.ScriptInputGate.offer(message)) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "§bTalos §7» §finput received: §7" + message));
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
    @Inject(method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V", at = @At("HEAD"))
    private void talos$onPlayerPositionLook(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        dev.talos.client.pathing.sim.SimFollowTask.onServerCorrection();
    }

    /**
     * Script {@code entity_hurt} event: the damage-event packet is how modern servers
     * announce any tracked entity taking damage (the red flash). Resolved to a live
     * entity here so the payload can stay primitive.
     */
    @Inject(method = "handleDamageEvent(Lnet/minecraft/network/protocol/game/ClientboundDamageEventPacket;)V", at = @At("HEAD"))
    private void talos$onEntityDamage(
            net.minecraft.network.protocol.game.ClientboundDamageEventPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || client.level == null) return;
        net.minecraft.world.entity.Entity entity = client.level.getEntity(packet.entityId());
        if (entity == null) return;
        dev.talos.client.script.ScriptGameEvents.onEntityHurt(
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                packet.entityId(), entity.getX(), entity.getY(), entity.getZ());
    }

    @Inject(method = "handleTakeItemEntity(Lnet/minecraft/network/protocol/game/ClientboundTakeItemEntityPacket;)V", at = @At("HEAD"))
    private void talos$onItemPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        EventRuleEngine.onItemPickup(packet.getItemId(), packet.getPlayerId(),
                packet.getAmount());
    }

    @Inject(method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V", at = @At("HEAD"))
    private void talos$onExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        EventRuleEngine.onExplosion(packet.center());
    }

    @Inject(method = "handleEntityEvent(Lnet/minecraft/network/protocol/game/ClientboundEntityEventPacket;)V", at = @At("HEAD"))
    private void talos$onEntityStatus(
            net.minecraft.network.protocol.game.ClientboundEntityEventPacket packet,
            CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || client.level == null) return;
        EventRuleEngine.onEntityStatus(packet.getEntity(client.level), packet.getEventId());
    }

    @Inject(method = "handleParticleEvent(Lnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket;)V", at = @At("HEAD"))
    private void talos$onParticle(
            net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        EventRuleEngine.onParticle(
                net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(
                        packet.getParticle().getType()).toString(),
                packet.getX(), packet.getY(), packet.getZ());
    }

    @Inject(method = "handleBossUpdate(Lnet/minecraft/network/protocol/game/ClientboundBossEventPacket;)V", at = @At("HEAD"))
    private void talos$onBossBar(ClientboundBossEventPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        packet.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override public void add(UUID uuid, Component name, float percent, BossEvent.BossBarColor color,
                    BossEvent.BossBarOverlay style, boolean darkenSky, boolean dragonMusic, boolean fog) {
                EventRuleEngine.onBossBarAdd(name.getString(), percent);
            }

            @Override public void remove(UUID uuid) {
                EventRuleEngine.onBossBarRemove();
            }

            @Override public void updateProgress(UUID uuid, float percent) {
                EventRuleEngine.onBossBarProgress(percent);
            }

            @Override public void updateName(UUID uuid, Component name) {
                EventRuleEngine.onBossBarName(name.getString());
            }
        });
    }
}
