package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;

/**
 * Client→server: "I am currently holding attack while looking at entity {@code targetId}" ({@code -1}
 * = not holding / not a valid target), sent every client tick by {@code ReviveHoldInput} while
 * holding, plus one final packet on release. {@code PmcDownedSupport.handleHoldRevive} does all the
 * actual timing/validation/completion — this packet carries no trust of its own, every value is
 * re-checked server-side on arrival.
 */
public class PacketHoldRevive {

    private final int targetId;

    public PacketHoldRevive(int targetId) {
        this.targetId = targetId;
    }

    public PacketHoldRevive(FriendlyByteBuf buf) {
        this.targetId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.targetId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PmcDownedSupport.handleHoldRevive(player, this.targetId);
        });
        ctx.get().setPacketHandled(true);
    }
}
