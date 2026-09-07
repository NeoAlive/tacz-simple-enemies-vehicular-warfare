package com.neoalive.tacz_sewv.command.quick;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ICaptureMedic;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.FlightOrders;
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

        double radius = SewvConfig.QUICK_LAND_RADIUS.get();
        int cancelled = 0;
        for (PmcUnitEntity pmc : QuickCommandUnits.owned(issuer, level, context.unitIds(), radius)) {
            dismissToFreeFire(pmc);
            cancelled++;
        }
        if (cancelled == 0 && !abortedRefill && !abortedEvac) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_cancel.none", 0,
                    ChatFormatting.RED);
            return;
        }
        int reported = Math.max(cancelled, (abortedRefill || abortedEvac) ? 1 : 0);
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_cancel.started", reported,
                ChatFormatting.YELLOW, reported);
    }

    private static void dismissToFreeFire(PmcUnitEntity pmc) {
        PatrolSupport.clearSweepMembership(pmc, "QuickCancel");
        EntrenchSupport.clear(pmc);
        GuardSupport.clearReach(pmc);
        MortarSupport.releaseClaim(pmc);
        TowRecoverySupport.clearIfTowering(pmc);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        ((IPathwayInfantry) pmc).sewv$clearPathway();
        FobSupport.clearRoutePending(pmc);

        if (pmc instanceof ICaptureMedic medic && medic.tacz_sewv$isCaptureMedicOrdered()) {
            medic.tacz_sewv$setCaptureMedicOrdered(false);
        }

        IVehicleBoarder boarder = (IVehicleBoarder) pmc;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);

        // Clear vehicle-formation NBT so FREE_FIRE doesn't leave a stale slot axis.
        pmc.getPersistentData().remove(IFormationMember.TAG_FORMATION_AXIS);
        pmc.getPersistentData().remove(IFormationMember.TAG_FORMATION_SHAPE);
        pmc.getPersistentData().remove(IFormationMember.TAG_FORMATION_ROWSIZE);
        pmc.getPersistentData().remove(IFormationMember.TAG_FORMATION_WIDTH);
        pmc.getPersistentData().remove(IFormationMember.TAG_FORMATION_LENGTH);

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
