package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.PoolEditorAccess;
import com.neoalive.tacz_sewv.util.WorldVehiclePools;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/** Client → server: replace world vehicle pools with the editor snapshot. */
public class PacketUpdateVehiclePools {

    private final Map<TankFaction, Map<Category, List<String>>> pools;

    public PacketUpdateVehiclePools(Map<TankFaction, Map<Category, List<String>>> pools) {
        this.pools = pools;
    }

    public PacketUpdateVehiclePools(FriendlyByteBuf buf) {
        this.pools = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category cat : Category.values()) {
                byCat.put(cat, PacketOpenPoolEditor.readStringList(buf));
            }
            this.pools.put(faction, byCat);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        for (TankFaction faction : TankFaction.values()) {
            for (Category cat : Category.values()) {
                PacketOpenPoolEditor.writeStringList(buf, this.pools.get(faction).get(cat));
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PoolEditorAccess.mayEdit(player)) return;

            WorldVehiclePools data = WorldVehiclePools.get(player.serverLevel());
            for (TankFaction faction : TankFaction.values()) {
                for (Category cat : Category.values()) {
                    List<String> raw = this.pools.get(faction).get(cat);
                    List<String> cleaned = new ArrayList<>();
                    for (String id : raw) {
                        if (ResourceLocation.tryParse(id) == null) continue;
                        if (!cleaned.contains(id)) cleaned.add(id);
                    }
                    data.set(faction, cat, cleaned);
                }
            }
            player.displayClientMessage(Component.translatable("message.tacz_sewv.pool.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
