package com.neoalive.tacz_sewv.command.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderGuard;

/** Quick Dismount — same stand-down as the TDT dismount key, for radius units. */
public final class QuickDismountPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        double radius = SewvConfig.QUICK_LAND_RADIUS.get();
        double r2 = radius * radius;
        int dismounted = 0;
        for (int unitId : context.unitIds()) {
            Entity e = issuer.level().getEntity(unitId);
            if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive()) continue;
            if (!OrderAuth.check(issuer, pmc, "QuickDismount")) continue;
            if (pmc.distanceToSqr(issuer) > r2) continue;
            if (OrderGuard.rejectIfDowned(issuer, pmc)) continue;
            boolean wasMounted = pmc.getVehicle() != null;
            if (wasMounted) {
                pmc.stopRiding();
            }
            IVehicleBoarder boarder = (IVehicleBoarder) pmc;
            boarder.tacz_sewv$setBoarding(false);
            boarder.tacz_sewv$setMountTargetId(-1);
            MortarSupport.releaseClaim(pmc);
            PatrolSupport.clearSweepMembership(pmc, "QuickDismount");
            ((IEscort) pmc).tacz_sewv$setEscortTargetId(-1);
            TowRecoverySupport.clearIfTowering(pmc);
            if (wasMounted) dismounted++;
        }
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.dismount", dismounted,
                ChatFormatting.YELLOW, dismounted);
    }
}
