package dev.talos.mixin;

import dev.talos.client.rules.EventRuleEngine;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds title/subtitle packets into the event-rule engine; vanilla has no callback for them. */
@Mixin(Hud.class)
public abstract class InGameHudTitleMixin {
    @Inject(method = "setTitle(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void talos$onTitle(Component title, CallbackInfo ci) {
        EventRuleEngine.onTitle(title);
    }

    @Inject(method = "setSubtitle(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void talos$onSubtitle(Component subtitle, CallbackInfo ci) {
        EventRuleEngine.onSubtitle(subtitle);
    }
}
