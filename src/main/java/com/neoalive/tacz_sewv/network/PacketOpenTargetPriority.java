package com.neoalive.tacz_sewv.network;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.editor.TargetPriorityClient;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Server → client: open the target-priority editor. */
public class PacketOpenTargetPriority {

    private final Map<TankFaction, Set<String>> excluded;
    private final Map<TankFaction, Set<String>> defaults;
    private final List<String> catalog;

    public PacketOpenTargetPriority(Map<TankFaction, Set<String>> excluded,
                                    Map<TankFaction, Set<String>> defaults,
                                    List<String> catalog) {
        this.excluded = excluded;
        this.defaults = defaults;
        this.catalog = catalog;
    }

    public PacketOpenTargetPriority(FriendlyByteBuf buf) {
        this.excluded = readFactionSets(buf);
        this.defaults = readFactionSets(buf);
        this.catalog = PacketOpenPoolEditor.readStringList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        writeFactionSets(buf, this.excluded);
        writeFactionSets(buf, this.defaults);
        PacketOpenPoolEditor.writeStringList(buf, this.catalog);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> TargetPriorityClient.openScreen(this.excluded, this.defaults, this.catalog)));
        ctx.get().setPacketHandled(true);
    }

    static Map<TankFaction, Set<String>> readFactionSets(FriendlyByteBuf buf) {
        Map<TankFaction, Set<String>> map = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            map.put(faction, new LinkedHashSet<>(PacketOpenPoolEditor.readStringList(buf)));
        }
        return map;
    }

    static void writeFactionSets(FriendlyByteBuf buf, Map<TankFaction, Set<String>> map) {
        for (TankFaction faction : TankFaction.values()) {
            Set<String> set = map.get(faction);
            PacketOpenPoolEditor.writeStringList(buf, set == null ? List.of() : List.copyOf(set));
        }
    }
}
