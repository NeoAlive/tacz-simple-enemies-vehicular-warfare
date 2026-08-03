package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.client.VehicleSkinClient;
import com.neoalive.tacz_sewv.util.CrewFacts;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/** S→C: sticky faction skin on a hull (or clear). PersistentData is not client-visible. */
public final class PacketVehicleSkin {

    private final int entityId;
    /** -1 = clear / stock. */
    private final int factionOrdinal;

    public PacketVehicleSkin(int entityId, @Nullable CrewFacts.Faction faction) {
        this.entityId = entityId;
        this.factionOrdinal = faction == null ? -1 : faction.ordinal();
    }

    public PacketVehicleSkin(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.factionOrdinal = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeVarInt(this.factionOrdinal);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient));
        ctx.get().setPacketHandled(true);
    }

    private void applyClient() {
        CrewFacts.Faction faction = this.factionOrdinal < 0 ? null : CrewFacts.Faction.byId(this.factionOrdinal);
        VehicleSkinClient.put(this.entityId, faction);
    }
}
