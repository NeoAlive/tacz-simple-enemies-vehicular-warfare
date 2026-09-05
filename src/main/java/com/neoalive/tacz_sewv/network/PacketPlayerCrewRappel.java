package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.entity.ai.support.PlayerRappelTracker;

/** C→S: player driver of a helicopter drops eligible units on ropes immediately. */
public final class PacketPlayerCrewRappel {

    public PacketPlayerCrewRappel() {
    }

    public PacketPlayerCrewRappel(FriendlyByteBuf buf) {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;
            PlayerRappelTracker.startCrew(player);
        });
        ctx.get().setPacketHandled(true);
    }
}
