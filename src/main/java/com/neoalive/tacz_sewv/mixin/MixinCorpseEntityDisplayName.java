package com.neoalive.tacz_sewv.mixin;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.util.UnitCorpseAppearance;

/**
 * Strips the invisible SEWV skin header from CorpseMod's "Corpse of …" display name.
 *
 * <p>String target only — no CorpseMod class literals — so the mixin class loads when Corpse is
 * absent; {@link CorpseMixinPlugin} skips apply in that case.
 */
@Mixin(targets = "de.maxhenkel.corpse.entities.CorpseEntity", remap = false)
public abstract class MixinCorpseEntityDisplayName {

    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true, remap = true)
    private void sewv$stripSkinHeader(CallbackInfoReturnable<Component> cir) {
        try {
            Object raw = this.getClass().getMethod("getCorpseName").invoke(this);
            if (!(raw instanceof String name) || name.isEmpty()) return;
            String display = UnitCorpseAppearance.displayName(name);
            if (display.equals(name)) return; // no SEWV header
            cir.setReturnValue(Component.translatable("entity.corpse.corpse_of", display));
        } catch (Throwable ignored) {
            // Corpse API drift or unexpected state — leave CorpseMod's own name alone.
        }
    }
}
