package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.HeliRollClient;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;

/**
 * S->C: roll of an AI-flown helicopter. SBW does not sync roll (it is a plain field the client
 * re-derives by running its own engine on synced stick inputs), and our hulls no longer use those
 * inputs. Sent only when roll moves by more than the publish deadband, so a steady hover sends
 * nothing.
 */
public final class PacketHeliRoll {

    private final int entityId;
    private final float roll;

    public PacketHeliRoll(int entityId, float roll) {
        this.entityId = entityId;
        this.roll = roll;
    }

    public PacketHeliRoll(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.roll = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeFloat(this.roll);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> HeliRollClient.apply(this.entityId, this.roll)));
        ctx.get().setPacketHandled(true);
    }
}
