package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.skin.CamoClient;

/** S→C: biome camo number on a unit or hull ({@code -1} = none). PersistentData is not client-visible. */
public final class PacketEntityCamo {

    private final int entityId;
    private final int camo;

    public PacketEntityCamo(int entityId, int camo) {
        this.entityId = entityId;
        this.camo = camo;
    }

    public PacketEntityCamo(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.camo = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeVarInt(this.camo);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CamoClient.put(this.entityId, this.camo)));
        ctx.get().setPacketHandled(true);
    }
}
