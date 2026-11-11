package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.gui.RadarContactsClient;
import com.neoalive.tacz_sewv.fpas.RadarContact;

/** Server to client: the MTI contacts of the FPAS whose panel the viewer has open. Replaces the last list. */
public class PacketRadarContacts {

    private static final int MAX = 256;

    public final BlockPos pos;
    public final List<RadarContact> contacts;

    public PacketRadarContacts(BlockPos pos, List<RadarContact> contacts) {
        this.pos = pos;
        this.contacts = contacts;
    }

    public PacketRadarContacts(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX) throw new IllegalArgumentException("radar contacts too large: " + n);
        List<RadarContact> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new RadarContact(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    RadarContact.Cls.byOrdinal(buf.readByte())));
        }
        this.contacts = out;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        int n = Math.min(this.contacts.size(), MAX);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            RadarContact c = this.contacts.get(i);
            buf.writeVarInt(c.targetId());
            buf.writeFloat((float) c.x());
            buf.writeFloat((float) c.y());
            buf.writeFloat((float) c.z());
            buf.writeByte(c.cls().ordinal());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> RadarContactsClient.accept(this.pos, this.contacts)));
        ctx.get().setPacketHandled(true);
    }
}
