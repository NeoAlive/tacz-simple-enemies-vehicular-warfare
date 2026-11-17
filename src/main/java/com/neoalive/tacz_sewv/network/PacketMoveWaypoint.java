package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import com.neoalive.tacz_sewv.map.MarkerOrder;
import com.neoalive.tacz_sewv.order.WaypointEdits;
import com.neoalive.tacz_sewv.platform.NetworkEvent;

/**
 * A map waypoint drag: move the point of every unit's order in {@code batch} so the first unit's
 * point lands on {@code to}. Sent a few times a second while dragging and once more with
 * {@code fin} on release. {@code detach} pulls the listed units out of their batch.
 */
public class PacketMoveWaypoint {

    private final MarkerOrder.Type kind;
    private final long batch;
    private final List<Integer> unitIds;
    private final int node;
    private final BlockPos to;
    private final boolean fin;
    private final boolean detach;

    public PacketMoveWaypoint(MarkerOrder.Type kind, long batch, List<Integer> unitIds, int node, BlockPos to,
                              boolean fin, boolean detach) {
        this.kind = kind;
        this.batch = batch;
        this.unitIds = unitIds;
        this.node = node;
        this.to = to;
        this.fin = fin;
        this.detach = detach;
    }

    public PacketMoveWaypoint(FriendlyByteBuf buf) {
        this.kind = MarkerOrder.Type.byId(buf.readByte());
        this.batch = buf.readLong();
        this.unitIds = PacketLists.readUnitIds(buf);
        this.node = buf.readVarInt();
        this.to = BlockPos.of(buf.readLong());
        byte flags = buf.readByte();
        this.fin = (flags & 1) != 0;
        this.detach = (flags & 2) != 0;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(this.kind.ordinal());
        buf.writeLong(this.batch);
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(this.node);
        buf.writeLong(this.to.asLong());
        buf.writeByte((this.fin ? 1 : 0) | (this.detach ? 2 : 0));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            WaypointEdits.apply(sender, this.kind, this.batch, this.unitIds, this.node, this.to, this.fin,
                    this.detach);
        });
        ctx.get().setPacketHandled(true);
    }
}
