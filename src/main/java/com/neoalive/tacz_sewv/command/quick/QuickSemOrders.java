package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Shared SEM-order apply path for quick commands — same stand-downs as {@code MixinPacketIssueOrder}
 * so FOLLOW / HOLD / FORM stick over patrols and escorts.
 */
public final class QuickSemOrders {

    private QuickSemOrders() {}

    /** @return how many units accepted the order */
    public static int apply(ServerPlayer issuer, List<Integer> unitIds, double radius, OrderType order) {
        if (!(issuer.level() instanceof ServerLevel level)) return 0;
        int ok = 0;
        for (PmcUnitEntity pmc : QuickCommandUnits.owned(issuer, level, unitIds, radius)) {
            if (QuickCommandUnits.refuseFobOrRoute(issuer, pmc)) continue;
            if (pmc instanceof IPmcDowned d && d.sewv$isDowned()) {
                OrderReport.fail(issuer, OrderFailure.UNIT_DOWNED, pmc);
                continue;
            }
            OrderStandDown.clearForNewOrder(pmc, "QuickSemOrder");
            // SEM's own Hold / Cease / Free drop a standing formation (setFormationIndex); match it.
            if (order != OrderType.FOLLOW_COMMANDER) VehicleFormation.clear(pmc);
            pmc.setOrder(order);
            if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
                CrewRadio.play(hull, CrewRadio.Line.ORDER_DISPATCH);
            }
            ok++;
        }
        return ok;
    }
}
