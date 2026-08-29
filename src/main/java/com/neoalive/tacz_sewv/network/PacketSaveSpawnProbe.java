package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.block.SpawnProbeBlockEntity;
import com.neoalive.tacz_sewv.block.SpawnProbeEditor;

/** Client → server: write spawn_probe NBT from the editor Apply button. */
public class PacketSaveSpawnProbe {

    private final BlockPos pos;
    private final List<String> vehicleList;
    private final boolean preCrewedSpawn;

    public PacketSaveSpawnProbe(BlockPos pos, List<String> vehicleList, boolean preCrewedSpawn) {
        this.pos = pos;
        this.vehicleList = vehicleList;
        this.preCrewedSpawn = preCrewedSpawn;
    }

    public PacketSaveSpawnProbe(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.vehicleList = PacketOpenPoolEditor.readStringList(buf);
        this.preCrewedSpawn = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        PacketOpenPoolEditor.writeStringList(buf, this.vehicleList);
        buf.writeBoolean(this.preCrewedSpawn);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !SpawnProbeEditor.mayEdit(player)) return;
            ServerLevel level = player.serverLevel();
            BlockEntity raw = level.getBlockEntity(this.pos);
            if (!(raw instanceof SpawnProbeBlockEntity be)) return;

            be.setVehicleList(this.vehicleList);
            be.setPreCrewedSpawn(this.preCrewedSpawn);

            BlockState state = level.getBlockState(this.pos);
            level.sendBlockUpdated(this.pos, state, state, 3);
            player.displayClientMessage(Component.translatable("message.tacz_sewv.spawn_probe.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
