package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.GraceClient;

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
