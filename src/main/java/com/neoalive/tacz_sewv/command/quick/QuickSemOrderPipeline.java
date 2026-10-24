package com.neoalive.tacz_sewv.command.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

import com.neoalive.tacz_sewv.network.NetworkHandler;

/** Quick Follow / Hold / SEM FORM_* — applies a SEM {@link OrderType} to radius PMCs. */
public final class QuickSemOrderPipeline implements QuickCommandPipeline {

    private final OrderType order;

    public QuickSemOrderPipeline(OrderType order) {
        this.order = order;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        int ok = QuickSemOrders.apply(issuer, context.unitIds(),
                context.unitRadius(com.neoalive.tacz_sewv.config.SewvConfig.QUICK_LAND_RADIUS.get()), this.order);
        String id = QuickSemOrders.feedbackId(this.order);
        NetworkHandler.orderFeedback(issuer, id, "message.tacz_sewv.sem_order." + id, ok, ChatFormatting.GREEN);
    }
}
