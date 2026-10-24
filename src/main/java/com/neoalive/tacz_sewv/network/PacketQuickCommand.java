package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.command.quick.QuickCommandPipeline;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;

/**
 * Client → server: fire a quick-command pipeline by id with the client-resolved unit ids, whether
 * those ids are the player's explicit wheel selection (range check waived), and the block under
 * the crosshair when the wheel opened (Entrench reads it).
 */
public class PacketQuickCommand {

    private final String pipelineId;
    private final List<Integer> unitIds;
    private final boolean explicit;
    @Nullable
    private final BlockPos aimBlock;

    public PacketQuickCommand(String pipelineId, List<Integer> unitIds) {
        this(pipelineId, unitIds, false, null);
    }

    public PacketQuickCommand(String pipelineId, List<Integer> unitIds, boolean explicit,
                              @Nullable BlockPos aimBlock) {
        this.pipelineId = pipelineId;
        this.unitIds = List.copyOf(unitIds);
        this.explicit = explicit;
        this.aimBlock = aimBlock;
    }

    public PacketQuickCommand(FriendlyByteBuf buf) {
        this.pipelineId = buf.readUtf();
        this.unitIds = PacketLists.readUnitIds(buf);
        this.explicit = buf.readBoolean();
        this.aimBlock = buf.readNullable(FriendlyByteBuf::readBlockPos);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.pipelineId);
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
        buf.writeBoolean(this.explicit);
        buf.writeNullable(this.aimBlock, FriendlyByteBuf::writeBlockPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (com.neoalive.tacz_sewv.command.quick.OrderQueue.capture(player, () -> handle(ctx))) return;
            if (!com.neoalive.tacz_sewv.item.TacticalTerminal.canUseQuickWheel(player)) return;
            QuickCommandPipeline pipeline = QuickCommandRegistry.get(this.pipelineId);
            if (pipeline == null) return;
            pipeline.execute(player, new QuickCommandPipeline.QuickCommandContext(
                    this.unitIds, this.explicit, this.aimBlock));
        });
        ctx.get().setPacketHandled(true);
    }
}
