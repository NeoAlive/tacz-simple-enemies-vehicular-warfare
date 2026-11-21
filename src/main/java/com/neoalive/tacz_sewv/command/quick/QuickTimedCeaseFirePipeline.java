package com.neoalive.tacz_sewv.command.quick;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.TimedCeaseFire;
import com.neoalive.tacz_sewv.network.NetworkHandler;

/** Cease Fire for a set time ({@link TimedCeaseFire}); the plain leaf stays on {@link QuickSemOrderPipeline}. */
public final class QuickTimedCeaseFirePipeline implements QuickCommandPipeline {

    private final int seconds;

    public QuickTimedCeaseFirePipeline(int seconds) {
        this.seconds = seconds;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        // First sighting wins: a leader reached through several followers already reads CEASE_FIRE after the first.
        Map<PmcUnitEntity, OrderType> landed = new LinkedHashMap<>();
        int ok = QuickSemOrders.apply(issuer, context.unitIds(),
                context.unitRadius(SewvConfig.QUICK_LAND_RADIUS.get()), OrderType.CEASE_FIRE, landed::putIfAbsent);
        landed.forEach((pmc, before) -> TimedCeaseFire.write(pmc, before, this.seconds));
        NetworkHandler.orderFeedback(issuer, "cease_fire", "message.tacz_sewv.sem_order.cease_fire_timed", ok,
                ChatFormatting.GREEN, this.seconds);
    }
}
