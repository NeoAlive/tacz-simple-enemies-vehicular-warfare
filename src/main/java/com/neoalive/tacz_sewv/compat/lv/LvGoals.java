package com.neoalive.tacz_sewv.compat.lv;

import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * The LV half of {@code VehicleAiGoals.addDriveGoals}, called from there only when LV is loaded.
 * Every goal self-gates on the unit sitting in (or being ordered onto) an LV hull, so on a unit
 * that never meets one each costs an {@code instanceof} per evaluation. Priorities mirror their
 * SBW counterparts.
 */
public final class LvGoals {

    private LvGoals() {}

    public static void add(AbstractUnit unit) {
        unit.goalSelector.addGoal(0, new LvBailOutGoal(unit));
        unit.goalSelector.addGoal(1, new LvDriveGoal(unit));
        unit.goalSelector.addGoal(1, new LvShipGoal(unit));
        unit.goalSelector.addGoal(1, new LvRotorGoal(unit));
        unit.goalSelector.addGoal(1, new LvGunnerGoal(unit));
        unit.goalSelector.addGoal(1, new LvBoardGoal(unit));
        unit.targetSelector.addGoal(2, new LvTargetScanGoal(unit));
    }
}
