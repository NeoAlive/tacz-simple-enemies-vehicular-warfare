package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.init.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.util.ThreatDecoy;

/**
 * In-flight guided missiles (Javelin / Igla / AGM / Kh-39 / 9M336) play
 * {@code MISSILE_WARNING} on the target's {@code onPos}. Same soft-kill reaction as lock-on.
 */
@Mixin(Level.class)
public abstract class MixinMissileWarningDecoy {

    @Shadow
    public abstract boolean isClientSide();

    @Inject(
            method = "playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"))
    private void tacz_sewv$decoyOnMissileWarning(Player player, BlockPos pos, SoundEvent sound,
            SoundSource source, float volume, float pitch, CallbackInfo ci) {
        if (this.isClientSide()) return;
        if (sound != ModSounds.MISSILE_WARNING.get()) return;
        ThreatDecoy.popNear((Level) (Object) this, pos);
    }
}
