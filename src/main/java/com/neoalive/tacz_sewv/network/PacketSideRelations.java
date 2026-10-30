package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.SideRelationsClient;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;

/** S->C: this player's resolved relation to the SEM sides, so a click can fail closed locally. */
public class PacketSideRelations {

    private final DiplomacyData.Relation ru;
    private final DiplomacyData.Relation us;

    public PacketSideRelations(DiplomacyData.Relation ru, DiplomacyData.Relation us) {
        this.ru = ru;
        this.us = us;
    }

    public PacketSideRelations(FriendlyByteBuf buf) {
        this.ru = read(buf);
        this.us = read(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(this.ru.ordinal());
        buf.writeByte(this.us.ordinal());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SideRelationsClient.accept(this.ru, this.us)));
        ctx.get().setPacketHandled(true);
    }

    private static DiplomacyData.Relation read(FriendlyByteBuf buf) {
        int ordinal = buf.readByte();
        DiplomacyData.Relation[] values = DiplomacyData.Relation.values();
        if (ordinal < 0 || ordinal >= values.length) return DiplomacyData.Relation.NEUTRAL;
        return values[ordinal];
    }
}
