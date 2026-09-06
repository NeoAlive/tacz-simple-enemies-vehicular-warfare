package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.block.TrenchNetworks;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/**
 * Quick Entrench: radius PMCs dig in. Multiple nearby trench networks split units by nearest
 * network so each trench fills from its local cluster.
 */
public final class QuickEntrenchPipeline implements QuickCommandPipeline {

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        if (!(issuer.level() instanceof ServerLevel level)) return;

        List<AbstractUnit> units = QuickCommandUnits.onFootOwnedAsUnits(issuer, level, context.unitIds());
        if (units.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_entrench.no_units", 0,
                    ChatFormatting.RED);
            return;
        }

        double radius = SewvConfig.QUICK_LAND_RADIUS.get();
        double r2 = radius * radius;
        TrenchNetworks data = TrenchNetworks.get(level);
        List<TrenchNetworks.Network> nearby = new ArrayList<>();
        for (TrenchNetworks.Network net : data.networks()) {
            double dx = net.x() - issuer.getX();
            double dy = net.y() - issuer.getY();
            double dz = net.z() - issuer.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) {
                nearby.add(net);
            }
        }
        if (nearby.isEmpty()) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_entrench.no_trench", 0,
                    ChatFormatting.RED);
            return;
        }

        Map<Integer, List<AbstractUnit>> buckets = new HashMap<>();
        for (TrenchNetworks.Network net : nearby) {
            buckets.put(net.id(), new ArrayList<>());
        }

        List<AbstractUnit> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparingDouble(u -> nearestNetDistSq(u, nearby)));
        for (AbstractUnit unit : sorted) {
            TrenchNetworks.Network best = null;
            double bestDist = Double.MAX_VALUE;
            for (TrenchNetworks.Network net : nearby) {
                double d = unit.distanceToSqr(net.x(), net.y(), net.z());
                if (d < bestDist) {
                    bestDist = d;
                    best = net;
                }
            }
            if (best == null) break;
            buckets.get(best.id()).add(unit);
        }

        int accepted = 0;
        for (TrenchNetworks.Network net : nearby) {
            List<AbstractUnit> slice = buckets.get(net.id());
            if (slice == null || slice.isEmpty()) continue;
            BlockPos anchor = BlockPos.containing(net.x(), net.y(), net.z());
            accepted += EntrenchSupport.assign(level, slice, anchor);
        }

        if (accepted == 0) {
            NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_entrench.no_trench", 0,
                    ChatFormatting.RED);
            return;
        }
        NetworkHandler.orderFeedback(issuer, "message.tacz_sewv.quick_entrench.started", accepted,
                ChatFormatting.GREEN, accepted);
    }

    private static double nearestNetDistSq(AbstractUnit unit, List<TrenchNetworks.Network> nets) {
        double best = Double.MAX_VALUE;
        for (TrenchNetworks.Network net : nets) {
            best = Math.min(best, unit.distanceToSqr(net.x(), net.y(), net.z()));
        }
        return best;
    }
}
