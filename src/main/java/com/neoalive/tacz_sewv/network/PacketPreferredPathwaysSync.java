package com.neoalive.tacz_sewv.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.PreferredPathwaysClient;

/** S→C full preferred-pathway sync for one player. */
public class PacketPreferredPathwaysSync {

    private final Map<ResourceKey<Level>, Map<String, List<BlockPos>>> pathsByDimension;

    public PacketPreferredPathwaysSync(Map<ResourceKey<Level>, Map<String, List<BlockPos>>> pathsByDimension) {
        this.pathsByDimension = pathsByDimension;
    }

    public PacketPreferredPathwaysSync(FriendlyByteBuf buf) {
        int dimCount = buf.readVarInt();
        Map<ResourceKey<Level>, Map<String, List<BlockPos>>> map = new HashMap<>();
        for (int d = 0; d < dimCount; d++) {
            ResourceLocation dimId = buf.readResourceLocation();
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);
            int pathCount = buf.readVarInt();
            Map<String, List<BlockPos>> paths = new HashMap<>();
            for (int p = 0; p < pathCount; p++) {
                String pathId = buf.readUtf();
                paths.put(pathId, buf.readList(FriendlyByteBuf::readBlockPos));
            }
            map.put(dim, paths);
        }
        this.pathsByDimension = map;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.pathsByDimension.size());
        for (Map.Entry<ResourceKey<Level>, Map<String, List<BlockPos>>> dimEntry
                : this.pathsByDimension.entrySet()) {
            buf.writeResourceLocation(dimEntry.getKey().location());
            buf.writeVarInt(dimEntry.getValue().size());
            for (Map.Entry<String, List<BlockPos>> pathEntry : dimEntry.getValue().entrySet()) {
                buf.writeUtf(pathEntry.getKey());
                buf.writeCollection(pathEntry.getValue(), FriendlyByteBuf::writeBlockPos);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PreferredPathwaysClient.apply(this.pathsByDimension)));
        ctx.get().setPacketHandled(true);
    }
}
