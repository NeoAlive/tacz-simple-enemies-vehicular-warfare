package com.neoalive.tacz_sewv.entity.ai;

import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Shared goal wiring for any unit that can drive/gun a vehicle, PmcUnitEntity
 * (player-commandable) as well as RUunitEntity/USunitEntity (hostile, no order
 * queue). BoardVehicleGoal is deliberately not included here: it depends on the
 * IVehicleBoarder network bridge, which only exists on player-owned PmcUnitEntity,
 * RU/US crews are placed directly into their vehicle at spawn instead.
 */
public final class VehicleAiGoals {

    private VehicleAiGoals() {}

    public static void addDriveGoals(AbstractUnit unit) {
        unit.goalSelector.addGoal(1, new DriveVehicleGoal(unit));
        unit.goalSelector.addGoal(1, new VehicleMinRangeGoal(unit));
    }
}
