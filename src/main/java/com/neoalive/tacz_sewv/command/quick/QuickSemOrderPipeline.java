package com.neoalive.tacz_sewv.command.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

import com.neoalive.tacz_sewv.network.NetworkHandler;

/** Quick Follow / Hold / SEM FORM_* — applies a SEM {@link OrderType} to radius PMCs. */
public final class QuickSemOrderPipeline implements QuickCommandPipeline {

    private final OrderType order;
    private final String feedbackBase;

    public QuickSemOrderPipeline(OrderType order, String feedbackBase) {
        this.order = order;
        this.feedbackBase = feedbackBase;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        int ok = QuickSemOrders.apply(issuer, context.unitIds(), this.order);
        if (ok == 0) {
            NetworkHandler.orderFeedback(issuer, this.feedbackBase, 0, ChatFormatting.RED);
            return;
        }
        NetworkHandler.orderFeedback(issuer, this.feedbackBase, ok, ChatFormatting.GREEN, ok);
    }
}
