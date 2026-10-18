package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.invasion.InvasionDeploy;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;

/** Client → server: the deployment picked on the deploy screen. Validated against the open offer. */
public class PacketInvasionDeployChoice {

    private final ForceKind kind;

    public PacketInvasionDeployChoice(ForceKind kind) {
        this.kind = kind;
    }

    public PacketInvasionDeployChoice(FriendlyByteBuf buf) {
        this.kind = ForceKind.fromOrdinal(buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.kind.ordinal());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) InvasionDeploy.choose(player, this.kind);
        });
        ctx.get().setPacketHandled(true);
    }
}
