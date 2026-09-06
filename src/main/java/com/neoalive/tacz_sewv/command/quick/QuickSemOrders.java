package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Shared SEM-order apply path for quick commands — same stand-downs as {@code MixinPacketIssueOrder}
 * so FOLLOW / HOLD / FORM stick over patrols and escorts.
 */
public final class QuickSemOrders {

    private QuickSemOrders() {}

    /** @return how many units accepted the order */
    public static int apply(ServerPlayer issuer, List<Integer> unitIds, OrderType order) {
        int ok = 0;
        for (int id : unitIds) {
            Entity e = issuer.level().getEntity(id);
            if (!(e instanceof PmcUnitEntity pmc)) continue;
            if (!OrderAuth.check(issuer, pmc, "QuickSemOrder")) continue;
            if (FobSupport.blocksOrders(pmc)) {
                OrderReport.fail(issuer, OrderFailure.FOB_COMMAND, pmc);
                continue;
            }
            if (FobSupport.hasRoutePending(pmc)) {
                OrderReport.fail(issuer, OrderFailure.ROUTE_ACTIVE, pmc);
                continue;
            }
            if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) {
                OrderReport.fail(issuer, OrderFailure.UNIT_DOWNED, pmc);
                continue;
            }
            clearConflicting(pmc);
            pmc.setOrder(order);
            if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
                CrewRadio.play(hull, CrewRadio.Line.ORDERS);
            }
            ok++;
        }
        return ok;
    }

    public static void clearConflicting(PmcUnitEntity pmc) {
        if (((IVehiclePatrol) pmc).sewv$getPatrolOrigin() != null
                || ((ISweepInfantry) pmc).sewv$hasInfantrySweep()) {
            PatrolSupport.clearSweepMembership(pmc, "QuickSemOrder");
        }
        EntrenchSupport.clear(pmc);
        GuardSupport.clearReach(pmc);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        TowRecoverySupport.clearIfTowering(pmc);
        if (pmc instanceof ICaptureOrder capture && capture.sewv$hasCaptureOrder()) {
            capture.sewv$clearCaptureOrder();
        }
        ((IPathwayInfantry) pmc).sewv$clearPathway();
    }
}
