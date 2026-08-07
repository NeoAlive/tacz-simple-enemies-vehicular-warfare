package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.editor.PoolEditorClient;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/** Server → client: open the pool editor with the current world snapshot + vehicle catalog. */
public class PacketOpenPoolEditor {

    private final Map<TankFaction, Map<Category, List<String>>> pools;
    private final Map<TankFaction, Map<Category, List<String>>> defaults;
    private final List<String> catalog;

    public PacketOpenPoolEditor(Map<TankFaction, Map<Category, List<String>>> pools,
                                Map<TankFaction, Map<Category, List<String>>> defaults,
                                List<String> catalog) {
        this.pools = pools;
        this.defaults = defaults;
        this.catalog = catalog;
    }

    public PacketOpenPoolEditor(FriendlyByteBuf buf) {
        this.pools = readPools(buf);
        this.defaults = readPools(buf);
        this.catalog = readStringList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        writePools(buf, this.pools);
        writePools(buf, this.defaults);
        writeStringList(buf, this.catalog);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PoolEditorClient.openScreen(this.pools, this.defaults, this.catalog)));
        ctx.get().setPacketHandled(true);
    }

    private static Map<TankFaction, Map<Category, List<String>>> readPools(FriendlyByteBuf buf) {
        Map<TankFaction, Map<Category, List<String>>> map = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category cat : Category.values()) {
                byCat.put(cat, readStringList(buf));
            }
            map.put(faction, byCat);
        }
        return map;
    }

    private static void writePools(FriendlyByteBuf buf, Map<TankFaction, Map<Category, List<String>>> map) {
        for (TankFaction faction : TankFaction.values()) {
            for (Category cat : Category.values()) {
                writeStringList(buf, map.get(faction).get(cat));
            }
        }
    }

    static List<String> readStringList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUtf());
        return list;
    }

    static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }
}
