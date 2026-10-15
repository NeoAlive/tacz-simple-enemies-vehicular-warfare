package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCueSounds;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;

/**
 * Registers SBW cannon fire played through {@code Level.playSound} as a cue idle crews can hear.
 * Rejects on the namespace before anything else, since every sound in the game passes here. TaCZ
 * gunfire is <b>not</b> captured here — see {@code AwarenessCueEvents}.
 */
@Mixin(Level.class)
public abstract class MixinLevelAwarenessSound {

    @Inject(
            method = "playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"))
    private void tacz_sewv$cueAtPos(Player player, BlockPos pos, SoundEvent sound, SoundSource source,
            float volume, float pitch, CallbackInfo ci) {
        tacz_sewv$register(null, pos, sound);
    }

    @Inject(
            method = "playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"))
    private void tacz_sewv$cueAtXYZ(Player player, double x, double y, double z, SoundEvent sound,
            SoundSource source, float volume, float pitch, CallbackInfo ci) {
        tacz_sewv$register(null, BlockPos.containing(x, y, z), sound);
    }

    @Inject(
            method = "playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"))
    private void tacz_sewv$cueOnEntity(Player player, Entity entity, SoundEvent sound, SoundSource source,
            float volume, float pitch, CallbackInfo ci) {
        tacz_sewv$register(entity, entity.blockPosition(), sound);
    }

    private void tacz_sewv$register(@Nullable Entity bound, BlockPos pos, SoundEvent sound) {
        if (!((Object) this instanceof ServerLevel server)) return;
        if (!AwarenessCueSounds.isCannonSound(sound)) return;
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;
        AwarenessCues.registerCannon(server, pos, bound);
    }
}
