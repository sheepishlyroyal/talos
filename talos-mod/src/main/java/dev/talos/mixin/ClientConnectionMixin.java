package dev.talos.mixin;

import dev.talos.client.rules.EventRuleEngine;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Generic packet trigger: every inbound packet's id ("clientbound/minecraft:explode", ...)
 * is offered to 'on packet_received matching ...' rules. The engine exits immediately when
 * no such rule exists, so the netty hot path stays effectively free.
 */
@Mixin(Connection.class)
public abstract class ClientConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;"
            + "Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void talos$onPacket(ChannelHandlerContext context, Packet<?> packet,
            CallbackInfo ci) {
        EventRuleEngine.onPacket(packet);
    }
}
