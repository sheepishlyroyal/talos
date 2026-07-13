package dev.glade.mixin;

import dev.glade.client.rules.EventRuleEngine;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds every sound the client plays into the event-rule engine ('on sound matching ...'). */
@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;I)V", at = @At("HEAD"))
    private void glade$onPlay(SoundInstance sound, int delay, CallbackInfo ci) {
        if (sound != null) EventRuleEngine.onSound(sound.getId().toString());
    }
}
