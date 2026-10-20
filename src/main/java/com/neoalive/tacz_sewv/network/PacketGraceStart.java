package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.GraceClient;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;

/** S->C: show the grace period's "Gathering Storm" popup with the days left. */
public class PacketGraceStart {

    private final int days;

    public PacketGraceStart(int days) {
        this.days = days;
    }

    public PacketGraceStart(FriendlyByteBuf buf) {
        this.days = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.days);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> GraceClient.open(this.days)));
        ctx.get().setPacketHandled(true);
    }
}
