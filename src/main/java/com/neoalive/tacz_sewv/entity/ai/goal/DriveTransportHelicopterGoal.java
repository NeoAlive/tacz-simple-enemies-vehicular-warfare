package com.neoalive.tacz_sewv.entity.ai.goal;

import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Unarmed / troop-lift helicopters (FCP Huey, Ash UH-60, VVP Mi-8 / NH-90, …).
 *
 * <p>Same flight model as {@link DriveHelicopterGoal}, but never enters orbit/strafe combat —
 * transit, follow, land and rappel only. Combat {@link DriveHelicopterGoal} self-excludes these
 * hulls via {@link com.neoalive.tacz_sewv.compat.NpcVehicleOverrides#isTransportHeli}.
 */
public final class DriveTransportHelicopterGoal extends DriveHelicopterGoal {

    public DriveTransportHelicopterGoal(AbstractUnit unit) {
        super(unit, true);
    }
}
