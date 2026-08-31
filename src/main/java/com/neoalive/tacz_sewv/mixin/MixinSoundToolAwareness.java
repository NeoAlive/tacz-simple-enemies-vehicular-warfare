package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.tools.SoundTool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;

/**
 * SBW vehicle and emplacement fire uses {@code SoundTool.playDistantSound} — client packets only,
 * never {@code Level.playSound}. This is the server-side registration path for those cues.
 */
@Mixin(value = SoundTool.class, remap = false)
public abstract class MixinSoundToolAwareness {

    @Inject(
            method = "playDistantSound(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/world/phys/Vec3;FFLnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"))
    private static void tacz_sewv$registerDistantCue(
            ServerLevel serverLevel,
            SoundEvent soundEvent,
            Vec3 pos,
            float radius,
            float pitch,
            Entity sender,
            CallbackInfo ci) {
        AwarenessCues.registerDistantSound(serverLevel, soundEvent, pos, sender);
    }
}
