package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.PlaneLandingDebugClient;

/**
 * S->C: drop a plane's cached Dubins entry-arc wireframe — sent once the entry arc that produced it
 * is actually cleared server-side (arc completed, go-around, order/landing cancelled, goal detach),
 * rather than left for a client-side staleness timeout. Same explicit-lifecycle shape as
 * {@code PacketHeliRunPhase}/{@code HeliRunPhaseClient#clear}.
 */
public final class PacketClearPlaneLandingDebug {

    private final int entityId;

    public PacketClearPlaneLandingDebug(int entityId) {
        this.entityId = entityId;
    }

    public PacketClearPlaneLandingDebug(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlaneLandingDebugClient.clear(this.entityId)));
        ctx.get().setPacketHandled(true);
    }
}
