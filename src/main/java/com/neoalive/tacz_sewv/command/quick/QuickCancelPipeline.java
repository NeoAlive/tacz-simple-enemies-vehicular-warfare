package com.neoalive.tacz_sewv.command.quick;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IDelayedFire;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;
import com.neoalive.tacz_sewv.entity.ai.support.FlightOrders;
import com.neoalive.tacz_sewv.entity.ai.support.FormationSubjects;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Root Cancel: abort active Quick Evac / Refill, dismiss area / board / entrench / capture-medic
 * state on radius PMCs, and drop them to FREE_FIRE. Does not force a dismount.
 */
public final class QuickCancelPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;

        boolean abortedRefill = QuickRefillTracker.cancelPlayer(issuer.getUUID(), level);
        boolean abortedEvac = QuickEvacTracker.cancelPlayer(issuer.getUUID(), level);

        double radius = context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get());
        int cancelled = 0;
        for (PmcUnitEntity pmc : QuickCommandUnits.owned(issuer, level, context.unitIds(), radius)) {
            dismissToFreeFire(pmc);
            cancelled++;
        }
        // A battery is usually out past the nearby radius, so with no selection Cancel also stands
        // down every fire mission in radio range — the same reach the call had.
        if (!context.explicit()) {
            cancelled += FireMissionSupport.standDown(level, issuer.getUUID(), issuer.position(),
                    EasyMode.mortarRadioRange());
        }
        if (cancelled == 0 && !abortedRefill && !abortedEvac) {
            NetworkHandler.orderFeedback(issuer, "cancel", "message.tacz_sewv.quick_cancel.none", 0,
                    ChatFormatting.RED);
            return;
        }
        int reported = Math.max(cancelled, (abortedRefill || abortedEvac) ? 1 : 0);
        NetworkHandler.orderFeedback(issuer, "cancel", "message.tacz_sewv.quick_cancel.started", reported,
                ChatFormatting.YELLOW, reported);
    }

    private static void dismissToFreeFire(PmcUnitEntity pmc) {
        PatrolSupport.clearSweepMembership(pmc, "QuickCancel");
        EntrenchSupport.clear(pmc);
        GuardSupport.clearReach(pmc);
        MortarSupport.releaseClaim(pmc);
        // FREE_FIRE alone leaves a grid mark live: artillery lays on it under FREE_FIRE.
        ((IMortarCrew) pmc).sewv$setFireMission(null);
        ((IDelayedFire) pmc).sewv$setFireDelayUntil(0L);
        TowRecoverySupport.clearIfTowering(pmc);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        ((IPathwayInfantry) pmc).sewv$clearPathway();
        if (pmc instanceof ICaptureOrder capture && capture.sewv$hasCaptureOrder()) {
            capture.sewv$clearCaptureOrder();
        }
        FobSupport.clearRoutePending(pmc);


        IVehicleBoarder boarder = (IVehicleBoarder) pmc;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);

        // Cancel is what ends a formation. A leader takes its whole formation with it; a follower leaves alone
        // and the rest close up. Also clears the slot axis/anchor so FREE_FIRE leaves nothing stale behind.
        FormationSubjects.release(pmc);

        if (pmc instanceof IHelicopterPilot
                && pmc.getVehicle() instanceof VehicleEntity hull
                && hull.getFirstPassenger() == pmc) {
            FlightOrders.clearCommand(pmc, hull);
        }

        pmc.setTarget(null);
        pmc.setOrder(OrderType.FREE_FIRE);
        if (pmc.getVehicle() == null) {
            pmc.getNavigation().stop();
        }
    }
}
