package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.zone.SpawnZoneViewers;

/** Client to server: the operator zone map view opened or closed. */
public class PacketZoneView {

    private final boolean open;

    public PacketZoneView(boolean open) {
        this.open = open;
    }

    public PacketZoneView(FriendlyByteBuf buf) {
        this.open = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.open);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) return;
            if (this.open && player.hasPermissions(2)) SpawnZoneViewers.open(player);
            else SpawnZoneViewers.close(player.getUUID());
        });
        ctx.get().setPacketHandled(true);
    }
}
