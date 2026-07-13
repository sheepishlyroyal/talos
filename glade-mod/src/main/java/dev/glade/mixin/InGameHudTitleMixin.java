package dev.glade.mixin;

import dev.glade.client.rules.EventRuleEngine;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds title/subtitle packets into the event-rule engine; vanilla has no callback for them. */
@Mixin(InGameHud.class)
public abstract class InGameHudTitleMixin {
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void glade$onTitle(Text title, CallbackInfo ci) {
        EventRuleEngine.onTitle(title);
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void glade$onSubtitle(Text subtitle, CallbackInfo ci) {
        EventRuleEngine.onSubtitle(subtitle);
    }
}
