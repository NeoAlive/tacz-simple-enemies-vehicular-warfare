package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.support.FormationSubjects;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * Shared SEM-order apply path for quick commands — same stand-downs as {@code MixinPacketIssueOrder}
 * so FOLLOW / HOLD / FORM stick over patrols and escorts.
 */
public final class QuickSemOrders {

    private QuickSemOrders() {}

    /**
     * The chat feedback id of a SEM order ({@code message.tacz_sewv.order.<id>} is its label,
     * {@code message.tacz_sewv.sem_order.<id>.done/.none} its text), shared by the wheel and SEM's
     * own order packet so the same order reads the same from either.
     */
    public static String feedbackId(OrderType order) {
        return switch (order) {
            case FOLLOW_COMMANDER -> "follow";
            case HOLD_POSITION -> "hold";
            case MOVE_TO_POSITION -> "move";
            case CEASE_FIRE -> "cease_fire";
            case FREE_FIRE -> "free_fire";
            case ATTACK_THAT_TARGET -> "attack";
            default -> "formation";
        };
    }

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
            // The same formation policy SEM's own order packet goes through: a player formation survives
            // FOLLOW and is dropped by the rest, a UNIT formation's orders go to its leader.
            if (FormationSubjects.routeOrder(issuer, pmc, order, null, -1, false)) {
                ok++;
                continue;
            }
            pmc.setOrder(order);
            if (pmc.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == pmc) {
                CrewRadio.play(hull, CrewRadio.Line.ORDER_DISPATCH);
            }
            ok++;
        }
        return ok;
    }
}
