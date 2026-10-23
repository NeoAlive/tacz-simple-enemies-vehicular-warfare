package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;

@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.procedural.events.type.CombatEvent")
public abstract class MixinCombatEvent {

    /** How much further back from the line a helicopter spawns than the ground vehicle. */
    private static final int ROTOR_BACKSET = 8;

    /** One vehicle roll: the class switch and pool are checked before the dice, so an empty pool costs nothing. */
    private static void rollVehicle(ServerLevel level, BlockPos at, TankSpawner.TankFaction faction,
                                    WorldVehiclePools.Category category, double chance) {
        boolean allowed = category == WorldVehiclePools.Category.ROTOR
                ? TankSpawner.rotorEventsAllowed(level, faction)
                : TankSpawner.groundEventsAllowed(level, faction);
        if (!allowed || level.random.nextDouble() >= chance) return;
        TankSpawner.spawnFromPool(level, TankSpawner.adjustHeight(level, at), faction, category, null, null);
    }

    @Inject(method = "execute", at = @At("TAIL"), remap = false)
    private void tacz_sewv$maybeSpawnVehicles(
            ServerLevel level, ServerPlayer player, BlockPos centerPos,
            CallbackInfoReturnable<Boolean> cir) {

        // Only proceed if the event actually succeeded (returned true)
        if (cir.getReturnValue() == null || !cir.getReturnValue()) return;

        int separation = 24;

        if (EasyMode.farCombatVehiclesAllowed()) {
            // Ground and rotor are separate rolls with separate switches: a fight can bring a tank,
            // a helicopter, both, or neither. The helicopter is set a little further back so the two
            // never contest one spawn spot.
            rollVehicle(level, centerPos.offset(separation, 0, 0), TankSpawner.TankFaction.RU,
                    WorldVehiclePools.Category.GROUND, SewvConfig.GROUND_SPAWN_CHANCE_RU.get());
            rollVehicle(level, centerPos.offset(-separation, 0, 0), TankSpawner.TankFaction.US,
                    WorldVehiclePools.Category.GROUND, SewvConfig.GROUND_SPAWN_CHANCE_US.get());
            rollVehicle(level, centerPos.offset(separation + ROTOR_BACKSET, 0, 0), TankSpawner.TankFaction.RU,
                    WorldVehiclePools.Category.ROTOR, SewvConfig.ROTOR_SPAWN_CHANCE_RU.get());
            rollVehicle(level, centerPos.offset(-separation - ROTOR_BACKSET, 0, 0), TankSpawner.TankFaction.US,
                    WorldVehiclePools.Category.ROTOR, SewvConfig.ROTOR_SPAWN_CHANCE_US.get());
        }

        // Rare CAS overhead — independent of the ground/rotor rolls, from the dedicated plane pools.
        if (SewvConfig.PLANES_IN_EVENTS.get() && EasyMode.farCombatVehiclesAllowed()) {
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
