package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.entity.ai.support.BailOutSupport;

/**
 * Air assault drop: expand seed unit ids to owned PMC passengers that pass the same seat filter
 * as rappel ({@link com.neoalive.tacz_sewv.entity.ai.support.RappelSupport#isRappelEligible} —
 * weaponless cargo only), then queue the parachute bail pathway. Pilot / gunners stay aboard.
 */
public final class PacketParatroop {

    private final List<Integer> unitIds;

    public PacketParatroop(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketParatroop(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            int queued = BailOutSupport.requestManualBailExpanded(
                    player, this.unitIds, BailOutSupport.rappelEligible());

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.paratroop", queued,
                    ChatFormatting.GREEN, queued);
        });
        ctx.get().setPacketHandled(true);
    }
}
