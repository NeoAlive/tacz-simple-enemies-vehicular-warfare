package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.PlayerRappelClient;

/** S→C: draw / clear rappel ropes on a hull during player-driven crew drops. */
public final class PacketPlayerRappelWires {

    private final int hullId;
    private final boolean active;

    public PacketPlayerRappelWires(int hullId, boolean active) {
        this.hullId = hullId;
        this.active = active;
    }

    public PacketPlayerRappelWires(FriendlyByteBuf buf) {
        this.hullId = buf.readVarInt();
        this.active = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.hullId);
        buf.writeBoolean(this.active);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlayerRappelClient.setWires(this.hullId, this.active)));
        ctx.get().setPacketHandled(true);
    }
}
