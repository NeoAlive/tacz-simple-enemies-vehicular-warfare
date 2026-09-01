package com.neoalive.tacz_sewv.entity.ai.support;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ICaptureMedic;
import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;

/**
 * One place to drop every player-issued order state on a PMC — downed lock, dismiss, bail, dismount.
 */
public final class OrderStandDown {

    private OrderStandDown() {}

    public static void clearAll(PmcUnitEntity pmc, String reason) {
        pmc.setTarget(null);
        pmc.setOrder(OrderType.CEASE_FIRE);

        PatrolSupport.clearSweepMembership(pmc, reason);
        EntrenchSupport.clear(pmc);
        GuardSupport.clearReach(pmc);

        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);

        IVehicleBoarder boarder = (IVehicleBoarder) pmc;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);

        if (pmc instanceof ICaptureOrder capture && capture.sewv$hasCaptureOrder()) {
            capture.sewv$clearCaptureOrder();
        }
        if (pmc instanceof ICaptureMedic medic && medic.tacz_sewv$isCaptureMedicOrdered()) {
            medic.tacz_sewv$setCaptureMedicOrdered(false);
        }

        MortarSupport.releaseClaim(pmc);
        TowRecoverySupport.clearIfTowering(pmc);

        IHelicopterPilot pilot = (IHelicopterPilot) pmc;
        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
        pilot.sewv$setHeliLandPos(null);

        ((IPathwayInfantry) pmc).sewv$clearPathway();

        if (pmc.getVehicle() != null) {
            pmc.stopRiding();
        }
    }

    /**
     * Stand-down subset for vehicle bail — does not force {@code CEASE_FIRE}; the caller issues
     * {@code MOVE_TO_POSITION} to the scramble point.
     */
    public static void clearForVehicleBail(PmcUnitEntity pmc) {
        pmc.setTarget(null);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        PatrolSupport.clearSweepMembership(pmc, "BailOutSupport");
        GuardSupport.clearReach(pmc);
        ((IPathwayInfantry) pmc).sewv$clearPathway();
    }

    /** Clears escort, sweep, patrol, and pathway before assigning a new pathway funnel. */
    public static void clearForPathwayAssign(PmcUnitEntity pmc) {
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        if (((com.neoalive.tacz_sewv.bridge.IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null
                || ((com.neoalive.tacz_sewv.bridge.ISweepInfantry) pmc).sewv$hasInfantrySweep()) {
            PatrolSupport.clearSweepMembership(pmc, "PathwayAssign");
        }
        ((IPathwayInfantry) pmc).sewv$clearPathway();
    }
}
