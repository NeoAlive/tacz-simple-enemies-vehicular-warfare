package com.neoalive.tacz_sewv.mixin;

import net.nekoyuni.SimpleEnemyMod.procedural.events.system.EventCooldownManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.config.EasyMode;

/**
 * Easy Mode: SEM's one global per-player event cooldown — which covers every registered event,
 * this mod's included — runs twice as long, so events come half as often. {@code require = 0}:
 * a SEM build without this method just loses the lever.
 */
@Mixin(EventCooldownManager.class)
public abstract class MixinEventCooldownEasy {

    @Inject(method = "getCooldownTicks", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void tacz_sewv$easyEventCooldown(CallbackInfoReturnable<Integer> cir) {
        int base = cir.getReturnValue();
        int scaled = EasyMode.eventCooldownTicks(base);
        if (scaled != base) cir.setReturnValue(scaled);
    }
}
