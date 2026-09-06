package com.neoalive.tacz_sewv.command.quick;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ICaptureMedic;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.OrderAuth;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderGuard;
import com.neoalive.tacz_sewv.order.OrderReport;

/** Quick Capture Medic — arms {@link ICaptureMedic} on radius PMCs (same as TDT). */
public final class QuickCaptureMedicPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!SewvConfig.MEDIC_CAPTURE_ENABLED.get()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.capture_medic", 0,
                    ChatFormatting.RED);
            return;
        }
        double radius = SewvConfig.PMC_CAPTURE_MEDIC_RADIUS.get();
        int ordered = 0;
        for (int unitId : context.unitIds()) {
            Entity e = issuer.level().getEntity(unitId);
            if (!(e instanceof PmcUnitEntity pmc)) continue;
            if (!OrderAuth.check(issuer, pmc, "QuickCaptureMedic")) continue;
            if (OrderGuard.rejectIfDowned(issuer, pmc)) continue;
            if (!hasMedicInRange(pmc, radius)) {
                OrderReport.fail(issuer, OrderFailure.NO_MEDIC_IN_RANGE, pmc);
                continue;
            }
            ((ICaptureMedic) pmc).tacz_sewv$setCaptureMedicOrdered(true);
            ordered++;
        }
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.capture_medic", ordered,
                ChatFormatting.GREEN, ordered);
    }

    private static boolean hasMedicInRange(PmcUnitEntity pmc, double radius) {
        List<AbstractUnit> candidates = pmc.level().getEntitiesOfClass(
                AbstractUnit.class,
                pmc.getBoundingBox().inflate(radius),
                candidate -> VehicleTargeting.isMedic(candidate) && candidate.isAlive());
        return !candidates.isEmpty();
    }
}
