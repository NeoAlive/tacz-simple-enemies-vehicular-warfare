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
        // Priority 0: bailing out of a hull that's about to die outranks everything
        // it could otherwise be doing from inside that hull.
        unit.goalSelector.addGoal(0, new BailOutVehicleGoal(unit));
        // DriveVehicleGoal (ground/ship) and DriveHelicopterGoal (flight) are both
        // registered on every crew; each gates on the mounted vehicle's engine type,
        // so exactly one activates for whatever hull the unit ends up in.
        unit.goalSelector.addGoal(1, new DriveVehicleGoal(unit));
        unit.goalSelector.addGoal(1, new DriveHelicopterGoal(unit));
        unit.goalSelector.addGoal(1, new VehicleMinRangeGoal(unit));
        // Gates on the mounted hull being a TOW, the same way the two drive goals gate on
        // engine type. It belongs here rather than with the PMC-only goals because loading
        // a launcher needs nothing but the crew's own inventory — no network bridge — so
        // any crew that carries missiles can work one.
        unit.goalSelector.addGoal(1, new ManTowGoal(unit));
        // Priority 2: while mounted this owns the TARGET flag over SEM's short
        // vanilla scans (also priority 2+, but strictly less reach), while SEM's
        // HurtByTargetGoal at priority 1 still preempts for retaliation.
        unit.targetSelector.addGoal(2, new VehicleTargetScanGoal(unit));
    }
}
