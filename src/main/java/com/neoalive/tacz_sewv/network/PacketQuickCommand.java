package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.command.quick.QuickCommandPipeline;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;

/** Client → server: fire a quick-command pipeline by id with resolved board-candidate unit ids. */
public class PacketQuickCommand {

    private final String pipelineId;
    private final List<Integer> unitIds;

    public PacketQuickCommand(String pipelineId, List<Integer> unitIds) {
        this.pipelineId = pipelineId;
        this.unitIds = List.copyOf(unitIds);
    }

    public PacketQuickCommand(FriendlyByteBuf buf) {
        this.pipelineId = buf.readUtf();
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.pipelineId);
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!com.neoalive.tacz_sewv.item.TacticalTerminal.canUseQuickWheel(player)) return;
            QuickCommandPipeline pipeline = QuickCommandRegistry.get(this.pipelineId);
            if (pipeline == null) return;
            pipeline.execute(player, new QuickCommandPipeline.QuickCommandContext(this.unitIds));
        });
        ctx.get().setPacketHandled(true);
    }
}
