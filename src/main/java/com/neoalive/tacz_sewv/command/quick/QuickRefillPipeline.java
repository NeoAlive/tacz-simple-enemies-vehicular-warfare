package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.fob.FobResupplySupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Quick Refill: radius PMCs walk to the nearest chest with matching TACZ ammo (or FOB stockpile
 * when under command) and only transfer once within 2 blocks — no remote vacuum.
 */
public final class QuickRefillPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;

        List<PmcUnitEntity> units = QuickCommandUnits.onFootOwned(issuer, level, context.unitIds());
        if (units.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_refill.no_units", 0,
                    ChatFormatting.RED);
            return;
        }

        double radius = SewvConfig.QUICK_LAND_RADIUS.get();
        List<QuickRefillTracker.LegSpec> legs = new ArrayList<>();
        for (PmcUnitEntity pmc : units) {
            boolean fob = FobResupplySupport.isUnderFobCommand(pmc);
            BlockPos target = QuickRefillTracker.findRefillTarget(level, pmc, radius, fob);
            if (target == null) continue;

            pmc.setOrder(OrderType.MOVE_TO_POSITION);
            pmc.setMoveToTarget(Vec3.atBottomCenterOf(target));
            legs.add(new QuickRefillTracker.LegSpec(pmc.getId(), target, fob));
        }

        if (legs.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_refill.none", 0,
                    ChatFormatting.RED);
            return;
        }

        QuickRefillTracker.start(issuer.getUUID(), legs, level.getGameTime());
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_refill.started", legs.size(),
                ChatFormatting.GREEN, legs.size());
    }
}
