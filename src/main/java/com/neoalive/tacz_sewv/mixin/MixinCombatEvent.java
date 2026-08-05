package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.procedural.events.type.CombatEvent")
public abstract class MixinCombatEvent {

    @Inject(method = "execute", at = @At("TAIL"), remap = false)
    private void tacz_sewv$maybeSpawnTanks(
            ServerLevel level, ServerPlayer player, BlockPos centerPos,
            CallbackInfoReturnable<Boolean> cir) {

        // Only proceed if the event actually succeeded (returned true)
        if (cir.getReturnValue() == null || !cir.getReturnValue()) return;

        int separation = 24;

        if (level.getGameRules().getBoolean(ModGameRules.TANKS_IN_EVENTS)) {
            if (level.random.nextDouble() < SewvConfig.TANK_SPAWN_CHANCE_RU.get()) {
                BlockPos posRu = TankSpawner.adjustHeight(level, centerPos.offset(separation, 0, 0));
                TankSpawner.spawnCombatVehicleWithCrew(level, posRu, TankSpawner.TankFaction.RU, null);
            }
            if (level.random.nextDouble() < SewvConfig.TANK_SPAWN_CHANCE_US.get()) {
                BlockPos posUs = TankSpawner.adjustHeight(level, centerPos.offset(-separation, 0, 0));
                TankSpawner.spawnCombatVehicleWithCrew(level, posUs, TankSpawner.TankFaction.US, null);
            }
        }

        // Rare CAS overhead — independent of the tank rolls, from the dedicated plane pools.
        if (SewvConfig.PLANES_IN_EVENTS.get()) {
            int airSep = 32;
            if (level.random.nextDouble() < SewvConfig.PLANE_SPAWN_CHANCE_RU.get()
                    && TankSpawner.hasSpawnablePlane(level, TankSpawner.TankFaction.RU)) {
                BlockPos posRu = TankSpawner.adjustHeight(level, centerPos.offset(airSep, 0, 0));
                TankSpawner.spawnPlaneWithCrew(level, posRu, TankSpawner.TankFaction.RU, null);
            }
            if (level.random.nextDouble() < SewvConfig.PLANE_SPAWN_CHANCE_US.get()
                    && TankSpawner.hasSpawnablePlane(level, TankSpawner.TankFaction.US)) {
                BlockPos posUs = TankSpawner.adjustHeight(level, centerPos.offset(-airSep, 0, 0));
                TankSpawner.spawnPlaneWithCrew(level, posUs, TankSpawner.TankFaction.US, null);
            }
        }
    }
}
