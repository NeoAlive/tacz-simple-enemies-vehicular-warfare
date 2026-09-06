package com.neoalive.tacz_sewv.entity.ai.support;

import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;

/**
 * Shared writer for standing board orders. {@link com.neoalive.tacz_sewv.entity.ai.goal.BoardVehicleGoal}
 * only <em>executes</em> these; whoever calls {@link #issue} owns the faction / selection logic.
 */
public final class BoardOrders {

    private BoardOrders() {}

    /**
     * Write a standing board order onto a PMC (clears escort / tow-recovery claims that would fight
     * the walk-to-mount goal).
     *
     * @param passengerOnly when true, {@link com.neoalive.tacz_sewv.entity.ai.goal.BoardVehicleGoal}
     *                      waits for {@code boardCleared} before mounting (player first-pick of seat).
     *                      Callers that already have an AI driver seated must clear that latch
     *                      themselves — see {@link #issueCleared}.
     */
    public static void issue(PmcUnitEntity pmc, int vehicleId, boolean passengerOnly) {
        IVehicleBoarder boarder = (IVehicleBoarder) pmc;
        boarder.tacz_sewv$setMountTargetId(vehicleId);
        boarder.tacz_sewv$setPassengerOnly(passengerOnly);
        boarder.tacz_sewv$setBoardCleared(false);
        boarder.tacz_sewv$setBoarding(true);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        TowRecoverySupport.clearIfTowering(pmc);
    }

    /**
     * Passenger board with the clear latch already set — for AI-piloted hulls (Quick Evac) where
     * waiting for the player's "board my vehicle" would leave infantry standing forever.
     */
    public static void issueCleared(PmcUnitEntity pmc, int vehicleId, boolean passengerOnly) {
        issue(pmc, vehicleId, passengerOnly);
        ((IVehicleBoarder) pmc).tacz_sewv$setBoardCleared(true);
    }
}
