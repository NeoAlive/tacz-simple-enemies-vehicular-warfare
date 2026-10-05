package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.radial.OrderQueueClient;

/** Server → client: the player's queued order labels, running order first. */
public class PacketOrderQueueState {

    private final List<String> labels;

    public PacketOrderQueueState(List<String> labels) {
        this.labels = List.copyOf(labels);
    }

    public PacketOrderQueueState(FriendlyByteBuf buf) {
        this.labels = buf.readList(b -> b.readUtf(64));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.labels, (b, s) -> b.writeUtf(s, 64));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> OrderQueueClient.setLabels(this.labels)));
        ctx.get().setPacketHandled(true);
    }
}
