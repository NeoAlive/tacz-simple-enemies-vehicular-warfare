package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.invasion.InvasionBlockEditor;
import com.neoalive.tacz_sewv.invasion.force.TeamBaseConfig;
import com.neoalive.tacz_sewv.platform.NetworkEvent;

/** Client → server: write team_base config from the editor. */
public class PacketSaveTeamBase {

    private final BlockPos pos;
    private final TeamBaseConfig config;

    public PacketSaveTeamBase(BlockPos pos, TeamBaseConfig config) {
        this.pos = pos;
        this.config = config;
    }

    public PacketSaveTeamBase(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.config = TeamBaseConfig.read(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        this.config.write(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !InvasionBlockEditor.mayEdit(player)) return;
            ServerLevel level = player.serverLevel();
            BlockEntity raw = level.getBlockEntity(this.pos);
            if (!(raw instanceof TeamBaseBlockEntity be)) return;

            this.config.applyTo(be);

            BlockState state = level.getBlockState(this.pos);
            level.sendBlockUpdated(this.pos, state, state, 3);
            player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.gui.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
