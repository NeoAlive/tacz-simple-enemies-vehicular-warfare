package com.neoalive.tacz_sewv.command.quick.scavenge;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.command.quick.QuickCommandPipeline;
import com.neoalive.tacz_sewv.command.quick.QuickCommandPipeline.QuickCommandContext;
import com.neoalive.tacz_sewv.command.quick.QuickCommandUnits;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Scavenge: on-foot units restock from what lies around them, ammo first, then emplacements, then
 * utilities. A unit with full pockets, or nothing to find, is counted and left alone.
 */
public final class QuickScavengePipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;
        // FOB-commanded units are accepted: restocking is part of the FOB's own logistics, and the
        // walk is dropped when it ends, so command resumes afterwards.
        List<PmcUnitEntity> units = QuickCommandUnits.onFootOwned(issuer, level, context.unitIds(),
                context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get()), true);
        if (units.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "scavenge", "message.tacz_sewv.scavenge.no_units", 0,
                    ChatFormatting.RED);
            return;
        }
        ScavengeTracker.cancelPlayer(issuer.getUUID(), level);

        FobInstance fob = FobManager.get(level).getFobForOwner(issuer.getUUID());
        BlockPos stockpile = fob == null ? null : fob.stockpilePos;
        ScavengeTracker.Run run = ScavengeTracker.newRun(level, issuer, stockpile);
        long now = level.getGameTime();
        ScavengeStage[] stage = new ScavengeStage[1];
        for (PmcUnitEntity pmc : units) {
            ScavengeWants wants = ScavengeWants.of(level, pmc);
            if (wants == null || wants.pocketsFull()) {
                run.full++;
                continue;
            }
            ScavengeSources.Pick first = ScavengeTracker.firstSource(run, wants, stage);
            if (first == null) {
                run.empty++;
                continue;
            }
            OrderStandDown.clearForNewOrder(pmc, "Scavenge");
            ScavengeTracker.addLeg(run, pmc, stage[0], first, now);
        }

        if (run.legs.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "scavenge", "message.tacz_sewv.scavenge.none", 0,
                    ChatFormatting.RED);
            ScavengeTracker.reportRefused(issuer, run);
            return;
        }
        ScavengeTracker.start(run, now);
        NetworkHandler.orderFeedback(issuer, "scavenge", "message.tacz_sewv.scavenge.started", run.legs.size(),
                ChatFormatting.GREEN, run.legs.size());
    }
}
