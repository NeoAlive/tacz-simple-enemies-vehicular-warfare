package com.neoalive.tacz_sewv.command.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import com.neoalive.tacz_sewv.fpas.RadarContact;
import com.neoalive.tacz_sewv.fpas.SignalOrders;
import com.neoalive.tacz_sewv.item.SignalRelayItem;

/** Signal: Attack X. The same order as the FPAS dashboard button, off every radar the carried relay is bound to. */
public final class QuickSignalPipeline implements QuickCommandPipeline {

    private final RadarContact.Cls cls;

    public QuickSignalPipeline(RadarContact.Cls cls) {
        this.cls = cls;
    }

    @Override
    public void execute(ServerPlayer issuer, QuickCommandContext context) {
        ItemStack relay = SignalRelayItem.findBound(issuer);
        if (relay.isEmpty()) {
            issuer.displayClientMessage(Component.translatable("message.tacz_sewv.signal.no_relay")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        SignalOrders.attack(issuer, SignalRelayItem.resolve(issuer, relay), this.cls);
    }
}
