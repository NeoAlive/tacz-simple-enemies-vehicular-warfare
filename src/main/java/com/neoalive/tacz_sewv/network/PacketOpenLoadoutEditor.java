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

import com.neoalive.tacz_sewv.client.editor.LoadoutEditorClient;
import com.neoalive.tacz_sewv.loadout.LoadoutManager;
import com.neoalive.tacz_sewv.loadout.LoadoutMerge;
import com.neoalive.tacz_sewv.loadout.LoadoutRow;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Server → client: open the loadout manager with the world layer, SEM's inherited pool and the catalogs. */
public class PacketOpenLoadoutEditor {

    /** Everything the screen needs; built server-side, decoded client-side. */
    public record Data(boolean hookActive, boolean extended, boolean configMod,
                       Map<TankFaction, LoadoutMerge.Faction> layers,
                       Map<TankFaction, List<LoadoutManager.Inherited>> inherited,
                       List<String> guns, List<String> attachments, List<String> armor) {}

    private final Data data;

    public PacketOpenLoadoutEditor(Data data) {
        this.data = data;
    }

    public PacketOpenLoadoutEditor(FriendlyByteBuf buf) {
        boolean hook = buf.readBoolean();
        boolean extended = buf.readBoolean();
        boolean config = buf.readBoolean();
        Map<TankFaction, LoadoutMerge.Faction> layers = new EnumMap<>(TankFaction.class);
        Map<TankFaction, List<LoadoutManager.Inherited>> inherited = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            layers.put(faction, LoadoutWire.readFaction(buf));
            int n = LoadoutWire.checked(buf.readVarInt(), LoadoutWire.MAX_HIDDEN);
            List<LoadoutManager.Inherited> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String file = buf.readUtf(LoadoutWire.ID_LEN);
                String name = buf.readUtf(LoadoutWire.ID_LEN);
                String pack = buf.readUtf(LoadoutWire.ID_LEN);
                LoadoutRow row = LoadoutWire.readRow(buf);
                if (row != null) list.add(new LoadoutManager.Inherited(file, name, pack, row));
            }
            inherited.put(faction, list);
        }
        this.data = new Data(hook, extended, config, layers, inherited,
                LoadoutWire.readIds(buf), LoadoutWire.readIds(buf), LoadoutWire.readIds(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.data.hookActive());
        buf.writeBoolean(this.data.extended());
        buf.writeBoolean(this.data.configMod());
        for (TankFaction faction : TankFaction.values()) {
            LoadoutWire.writeFaction(buf, this.data.layers().get(faction));
            List<LoadoutManager.Inherited> list = this.data.inherited().get(faction);
            int n = Math.min(list.size(), LoadoutWire.MAX_HIDDEN);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                LoadoutManager.Inherited in = list.get(i);
                buf.writeUtf(in.fileId(), LoadoutWire.ID_LEN);
                buf.writeUtf(in.name(), LoadoutWire.ID_LEN);
                buf.writeUtf(in.packId(), LoadoutWire.ID_LEN);
                LoadoutWire.writeRow(buf, in.row());
            }
        }
        LoadoutWire.writeIds(buf, this.data.guns());
        LoadoutWire.writeIds(buf, this.data.attachments());
        LoadoutWire.writeIds(buf, this.data.armor());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> LoadoutEditorClient.openScreen(this.data)));
        ctx.get().setPacketHandled(true);
    }
}
