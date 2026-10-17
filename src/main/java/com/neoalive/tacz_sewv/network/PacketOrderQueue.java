package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.command.quick.OrderQueue;

/**
 * Client → server: Quick Wheel order queue control. BEGIN/END bracket the order packets that make
 * up one queued order (see {@link OrderQueue}); SKIP drops the running order; CLEAR empties the queue.
 */
public class PacketOrderQueue {

    public static final int BEGIN = 0;
    public static final int END = 1;
    public static final int SKIP = 2;
    public static final int CLEAR = 3;

    private static final int MAX_STRING = 64;

    private final int action;
    private final String pipelineId;
    private final String label;
    private final List<Integer> unitIds;

    public PacketOrderQueue(int action) {
        this(action, "", "", List.of());
    }

    public PacketOrderQueue(int action, String pipelineId, String label, List<Integer> unitIds) {
        this.action = action;
        this.pipelineId = pipelineId;
        this.label = label;
        this.unitIds = List.copyOf(unitIds);
    }

    public PacketOrderQueue(FriendlyByteBuf buf) {
        this.action = buf.readVarInt();
        this.pipelineId = buf.readUtf(MAX_STRING);
        this.label = buf.readUtf(MAX_STRING);
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.action);
        buf.writeUtf(this.pipelineId, MAX_STRING);
        buf.writeUtf(this.label, MAX_STRING);
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            switch (this.action) {
                case BEGIN -> OrderQueue.begin(player, this.pipelineId, this.label, this.unitIds);
                case END -> OrderQueue.end(player);
                case SKIP -> OrderQueue.skip(player);
                case CLEAR -> OrderQueue.clear(player);
                default -> { }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
