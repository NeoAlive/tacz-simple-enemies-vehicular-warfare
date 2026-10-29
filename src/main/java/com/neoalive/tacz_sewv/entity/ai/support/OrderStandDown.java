package com.neoalive.tacz_sewv.entity.ai.support;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.fob.FobSupport;

/**
 * One place to drop every player-issued order state on a PMC — downed lock, dismiss, bail, dismount.
 */
public final class OrderStandDown {

    private OrderStandDown() {}

    /**
     * What any new player order replaces: the standing tasks that would otherwise keep MOVE. Escort,
     * sweep, area task and pathway goals all sit at priority 1, and at equal priority the running
     * goal keeps the flag, so an order issued on top of one of them was silently ignored. Leaves
     * boarding and mortar claims alone (the orders that set those manage them) and changes no SEM
     * order, so callers set their own.
     */
    public static void clearForNewOrder(PmcUnitEntity pmc, String reason) {
        if (((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null
                || ((IVehiclePatrol) pmc).sewv$hasSweepRect()
                || ((ISweepInfantry) pmc).sewv$hasInfantrySweep()) {
            PatrolSupport.clearSweepMembership(pmc, reason);
        }
        EntrenchSupport.clear(pmc);
        GuardSupport.clearReach(pmc);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        TowRecoverySupport.clearIfTowering(pmc);
        if (pmc instanceof ICaptureOrder capture && capture.sewv$hasCaptureOrder()) {
            capture.sewv$clearCaptureOrder();
        }
        ((IPathwayInfantry) pmc).sewv$clearPathway();
        SuppressOrders.clear(pmc);
    }

    public static void clearAll(PmcUnitEntity pmc, String reason) {
        pmc.setTarget(null);
        SuppressOrders.clear(pmc);
        pmc.setOrder(OrderType.CEASE_FIRE);

        PatrolSupport.clearSweepMembership(pmc, reason);
        TerritorySupport.dropPost(pmc); // stand-down always wins over a Territory post
        EntrenchSupport.clear(pmc);
        GuardSupport.clearReach(pmc);

        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);

        IVehicleBoarder boarder = (IVehicleBoarder) pmc;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);

        if (pmc instanceof ICaptureOrder capture && capture.sewv$hasCaptureOrder()) {
            capture.sewv$clearCaptureOrder();
        }

        MortarSupport.releaseClaim(pmc);
        TowRecoverySupport.clearIfTowering(pmc);

        IHelicopterPilot pilot = (IHelicopterPilot) pmc;
        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
        pilot.sewv$setHeliLandPos(null);

        ((IPathwayInfantry) pmc).sewv$clearPathway();

        // Route-to-FOB otherwise keeps FollowLeash / arrival logic live after a downed lock.
        FobSupport.clearRoutePending(pmc);
        FobSupport.clearScrambleStoodDown(pmc);

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
        SuppressOrders.clear(pmc);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        PatrolSupport.clearSweepMembership(pmc, "BailOutSupport");
        TerritorySupport.dropPost(pmc);
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
