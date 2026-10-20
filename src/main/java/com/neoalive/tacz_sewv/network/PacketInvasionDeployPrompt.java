package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.invasion.InvasionDeployClient;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;

/** Server → client: choose how to deploy into the invasion, within {@code seconds}. */
public class PacketInvasionDeployPrompt {

    private final List<ForceKind> options;
    private final int seconds;

    public PacketInvasionDeployPrompt(List<ForceKind> options, int seconds) {
        this.options = List.copyOf(options);
        this.seconds = seconds;
    }

    public PacketInvasionDeployPrompt(FriendlyByteBuf buf) {
        int mask = buf.readVarInt();
        List<ForceKind> out = new ArrayList<>();
        for (ForceKind kind : ForceKind.values()) {
            if ((mask & (1 << kind.ordinal())) != 0) out.add(kind);
        }
        this.options = out;
        this.seconds = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        int mask = 0;
        for (ForceKind kind : this.options) mask |= 1 << kind.ordinal();
        buf.writeVarInt(mask);
        buf.writeVarInt(this.seconds);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                InvasionDeployClient.open(this.options, this.seconds)));
        ctx.get().setPacketHandled(true);
    }
}
