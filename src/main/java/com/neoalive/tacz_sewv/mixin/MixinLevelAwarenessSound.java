package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCueSounds;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;

/**
 * Registers audible combat cues for idle crew investigation.
 *
 * <p>Fast-rejects by namespace ({@code superbwarfare} / {@code tacz_sewv} / {@code minecraft})
 * before classification. TaCZ gunfire is <b>not</b> captured here — see {@code AwarenessCueEvents}.
 */
@Mixin(Level.class)
public abstract class MixinLevelAwarenessSound {

    @Shadow
    public abstract boolean isClientSide();

    @Inject(
            method = "playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"))
    private void tacz_sewv$cueAtPos(Player player, BlockPos pos, SoundEvent sound, SoundSource source,
            float volume, float pitch, CallbackInfo ci) {
        tacz_sewv$register(null, pos, sound, source);
    }

    @Inject(
            method = "playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"))
    private void tacz_sewv$cueAtXYZ(Player player, double x, double y, double z, SoundEvent sound,
            SoundSource source, float volume, float pitch, CallbackInfo ci) {
        tacz_sewv$register(null, BlockPos.containing(x, y, z), sound, source);
    }

    @Inject(
            method = "playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"))
    private void tacz_sewv$cueOnEntity(Player player, Entity entity, SoundEvent sound, SoundSource source,
            float volume, float pitch, CallbackInfo ci) {
        tacz_sewv$register(entity, entity.blockPosition(), sound, source);
    }

    private void tacz_sewv$register(@Nullable Entity bound, BlockPos pos, SoundEvent sound,
            SoundSource source) {
        if (this.isClientSide()) return;
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;

        String ns = sound.getLocation().getNamespace();
        if (!("superbwarfare".equals(ns) || "tacz_sewv".equals(ns) || "minecraft".equals(ns))) {
            return;
        }

        AwarenessCues.TriggerKind kind = AwarenessCueSounds.classify(sound, source, bound);
        if (kind == null || kind == AwarenessCues.TriggerKind.OUTER_ENTITY) return;

        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel server)) return;

        CrewFacts.Faction faction = null;
        int unitId = -1;
        if (bound instanceof VehicleEntity vehicle) {
            faction = CrewFacts.factionOf(vehicle);
        } else if (bound instanceof AbstractUnit unit) {
            faction = CrewFacts.factionOfCrew(unit);
            unitId = unit.getId();
        } else if (bound instanceof Player) {
            // No SEM faction — hostility filter uses living entity id when present.
            unitId = bound.getId();
        }

        AwarenessCues.registerSound(server, pos, kind, faction, unitId);
    }
}
