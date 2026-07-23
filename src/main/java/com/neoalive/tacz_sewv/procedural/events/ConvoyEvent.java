package com.neoalive.tacz_sewv.procedural.events;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;

/**
 * A standalone SEM event for a single-faction military convoy. It is deliberately
 * separate from far_combat, so its spawn probability and enable state are independent.
 */
public final class ConvoyEvent extends DynamicEvent {

    public static final String ID = "convoy";
    private static final int INFANTRY_COUNT = 5;
    private static final int MAX_VEHICLES = 3;
    private static final int VEHICLE_SPACING = 18;
    private static final int INFANTRY_SCATTER = 6;

    public ConvoyEvent() {
        super(ID);
    }

    @Override
    public double getBaseChance() {
        return SewvConfig.CONVOY_BASE_CHANCE.get();
    }

    @Override
    public double getFailureMultiplier() {
        return SewvConfig.CONVOY_FAILURE_MULTIPLIER.get();
    }

    @Override
    public int getMinDistance() {
        return 90;
    }

    @Override
    public int getMaxDistance() {
        return 140;
    }

    @Override
    public boolean canExecute(ServerLevel level, ServerPlayer player) {
        return SewvConfig.CONVOY_EVENTS_ENABLED.get();
    }

    @Override
    public boolean execute(ServerLevel level, ServerPlayer player, BlockPos centerPos) {
        if (!SpawnHelper.isValidSpawn(level, centerPos)) return false;

        // A convoy is exclusively one side. PMC is intentionally not a candidate.
        TankSpawner.TankFaction faction = EventSpawns.pickVehicleFaction(level);
        if (faction == null) return false;
        int desiredVehicles = 1 + level.random.nextInt(MAX_VEHICLES);
        boolean alongX = level.random.nextBoolean();
        int direction = level.random.nextBoolean() ? 1 : -1;
        int spawnedVehicles = 0;
        BlockPos firstVehiclePos = null;

        for (int i = 0; i < desiredVehicles; i++) {
            int offset = i * VEHICLE_SPACING * direction;
            BlockPos vehiclePos = alongX ? centerPos.offset(offset, 0, 0) : centerPos.offset(0, 0, offset);
            vehiclePos = TankSpawner.adjustHeight(level, vehiclePos);
            VehicleEntity vehicle = TankSpawner.spawnTankWithCrew(level, vehiclePos, faction, null);
            if (vehicle != null) {
                spawnedVehicles++;
                if (firstVehiclePos == null) firstVehiclePos = vehiclePos;
            }
        }

        // Do not create an infantry-only convoy: an unsuccessful placement simply
        // lets SEM retry this event on a later cycle.
        if (spawnedVehicles == 0 || firstVehiclePos == null) return false;

        for (int i = 0; i < INFANTRY_COUNT; i++) {
            EventSpawns.infantry(level, firstVehiclePos, faction, INFANTRY_SCATTER);
        }
        return true;
    }
}
