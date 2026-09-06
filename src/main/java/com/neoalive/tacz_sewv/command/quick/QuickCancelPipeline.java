package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Root Cancel: dismiss area / board / entrench state on radius PMCs and drop them to FREE_FIRE.
 * Cheap stand-down for stale quick-command walks without forcing a dismount.
 */
public final class QuickCancelPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;

        QuickRefillTracker.cancelPlayer(issuer.getUUID(), level);

        int cancelled = 0;
        for (int id : context.unitIds()) {
            if (!(level.getEntity(id) instanceof PmcUnitEntity pmc)) continue;
            if (!pmc.isOwnedBy(issuer) || !pmc.isAlive()) continue;
            dismissToFreeFire(pmc);
            cancelled++;
        }
        if (cancelled == 0) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_cancel.none", 0,
                    ChatFormatting.RED);
            return;
        }
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_cancel.started", cancelled,
                ChatFormatting.YELLOW, cancelled);
    }

    private static void dismissToFreeFire(PmcUnitEntity pmc) {
        PatrolSupport.clearSweepMembership(pmc, "QuickCancel");
        EntrenchSupport.clear(pmc);
        GuardSupport.clearReach(pmc);
        MortarSupport.releaseClaim(pmc);
        TowRecoverySupport.clearIfTowering(pmc);
        ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
        ((IPathwayInfantry) pmc).sewv$clearPathway();

        IVehicleBoarder boarder = (IVehicleBoarder) pmc;
        boarder.tacz_sewv$setBoarding(false);
        boarder.tacz_sewv$setMountTargetId(-1);

        pmc.setTarget(null);
        pmc.setOrder(OrderType.FREE_FIRE);
        if (pmc.getVehicle() == null) {
            pmc.getNavigation().stop();
        }
    }
}
