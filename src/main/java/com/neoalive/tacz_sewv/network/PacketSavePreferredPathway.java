package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.entity.ai.support.PathwaySupport;
import com.neoalive.tacz_sewv.map.PreferredPathwayData;

/** C→S save or delete a preferred pathway. Empty nodes with delete=true removes the path. */
public class PacketSavePreferredPathway {

    private final String pathId;
    private final ResourceKey<Level> dimension;
    private final List<BlockPos> nodes;
    private final boolean delete;

    public PacketSavePreferredPathway(String pathId, ResourceKey<Level> dimension,
                                      List<BlockPos> nodes, boolean delete) {
        this.pathId = pathId;
        this.dimension = dimension;
        this.nodes = nodes;
        this.delete = delete;
    }

    private static final int MAX_PATH_ID_LEN = 64;

    public PacketSavePreferredPathway(FriendlyByteBuf buf) {
        this.pathId = buf.readUtf(MAX_PATH_ID_LEN);
        ResourceLocation dimId = buf.readResourceLocation();
        this.dimension = ResourceKey.create(Registries.DIMENSION, dimId);
        int n = buf.readVarInt();
        if (n < 0 || n > PacketPatrolVehicle.MAX_ROUTE_NODES) {
            throw new IllegalArgumentException("pathway node count out of range: " + n);
        }
        java.util.ArrayList<BlockPos> list = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readBlockPos());
        this.nodes = list;
        this.delete = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.pathId, MAX_PATH_ID_LEN);
        buf.writeResourceLocation(this.dimension.location());
        List<BlockPos> route = this.nodes.size() > PacketPatrolVehicle.MAX_ROUTE_NODES
                ? this.nodes.subList(0, PacketPatrolVehicle.MAX_ROUTE_NODES) : this.nodes;
        buf.writeCollection(route, FriendlyByteBuf::writeBlockPos);
        buf.writeBoolean(this.delete);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            String id = this.pathId.isBlank()
                    ? PathwaySupport.suggestPathId(PreferredPathwayData.forOwner(
                            player.level(), player.getUUID(), this.dimension))
                    : this.pathId;

            if (this.delete) {
                if (PreferredPathwayData.deletePath(player, this.dimension, id)) {
                    NetworkHandler.orderFeedback(player, "message.tacz_sewv.pathway.deleted",
                            1, ChatFormatting.YELLOW, id);
                } else {
                    NetworkHandler.orderFeedback(player, "message.tacz_sewv.pathway.save_failed",
                            0, ChatFormatting.RED);
                }
                return;
            }

            if (PreferredPathwayData.savePath(player, this.dimension, id, this.nodes)) {
                NetworkHandler.orderFeedback(player, "message.tacz_sewv.pathway.saved",
                        this.nodes.size(), ChatFormatting.GREEN, id, this.nodes.size());
            } else {
                NetworkHandler.orderFeedback(player, "message.tacz_sewv.pathway.save_failed",
                        0, ChatFormatting.RED);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
