package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.HeliRunPhaseClient;

/** S→C: AI heli run phase (firing-run + RAPPEL) for overlay / client readers (persistentData is not synced). */
public final class PacketHeliRunPhase {

    private final int entityId;
    private final int phaseOrdinal;

    public PacketHeliRunPhase(int entityId, int phaseOrdinal) {
        this.entityId = entityId;
        this.phaseOrdinal = phaseOrdinal;
    }

    public PacketHeliRunPhase(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.phaseOrdinal = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeVarInt(this.phaseOrdinal);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> HeliRunPhaseClient.put(this.entityId, this.phaseOrdinal)));
        ctx.get().setPacketHandled(true);
    }
}
