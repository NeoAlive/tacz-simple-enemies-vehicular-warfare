package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.skin.VehicleSkinClient;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/** S→C: sticky faction skin (+ RNG salt) on a hull (or clear). PersistentData is not client-visible. */
public final class PacketVehicleSkin {

    private final int entityId;
    /** -1 = clear / stock. */
    private final int factionOrdinal;
    /** Sticky salt for numbered skin pools ({@code salt % poolSize} on the client). */
    private final int salt;

    public PacketVehicleSkin(int entityId, @Nullable CrewFacts.Faction faction, int salt) {
        this.entityId = entityId;
        this.factionOrdinal = faction == null ? -1 : faction.ordinal();
        this.salt = salt;
    }

    public PacketVehicleSkin(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.factionOrdinal = buf.readVarInt();
        this.salt = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeVarInt(this.factionOrdinal);
        buf.writeVarInt(this.salt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient));
        ctx.get().setPacketHandled(true);
    }

    private void applyClient() {
        CrewFacts.Faction faction = this.factionOrdinal < 0 ? null : CrewFacts.Faction.byId(this.factionOrdinal);
        VehicleSkinClient.put(this.entityId, faction, this.salt);
    }
}
