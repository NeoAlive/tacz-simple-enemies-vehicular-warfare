package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.zone.SpawnZoneData;
import com.neoalive.tacz_sewv.zone.ZoneGeometry;
import com.neoalive.tacz_sewv.zone.ZoneType;

/** Client to server: one map brush stroke on the manual zone layer of the sender's dimension. Operators only. */
public class PacketZoneEdit {

    public static final byte ERASE = 0, SET_PRIORITY = 1, SET_NOZONE = 2;
    public static final int MAX_CHUNKS = 4096;

    private final byte action;
    private final long[] chunks;

    public PacketZoneEdit(byte action, long[] chunks) {
        this.action = action;
        this.chunks = chunks;
    }

    public PacketZoneEdit(FriendlyByteBuf buf) {
        this.action = buf.readByte();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_CHUNKS) throw new IllegalArgumentException("zone stroke too large: " + n);
        this.chunks = new long[n];
        for (int i = 0; i < n; i++) this.chunks[i] = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(this.action);
        buf.writeVarInt(this.chunks.length);
        for (long k : this.chunks) buf.writeLong(k);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null || !player.hasPermissions(2)) return;
            ZoneType type = switch (this.action) {
                case SET_PRIORITY -> ZoneType.PRIORITY;
                case SET_NOZONE -> ZoneType.NOZONE;
                case ERASE -> ZoneType.NEUTRAL;
                default -> null;
            };
            if (type == null) return;
            SpawnZoneData data = SpawnZoneData.get(player.serverLevel());
            int changed = 0;
            for (long k : this.chunks) {
                if (data.setManual(ZoneGeometry.x(k), ZoneGeometry.z(k), type)) changed++;
            }
            int n = changed;
            player.displayClientMessage(Component.translatable(type == ZoneType.NEUTRAL
                    ? "gui.tacz_sewv.zones.draw.erased" : "gui.tacz_sewv.zones.draw.painted", n), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
