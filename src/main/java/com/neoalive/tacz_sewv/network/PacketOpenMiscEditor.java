package com.neoalive.tacz_sewv.network;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.editor.MiscEditorClient;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/** Server → client: open the misc cue/armor editor. */
public class PacketOpenMiscEditor {

    private final Map<CueKind, List<String>> cues;
    private final Map<CueKind, List<String>> cueDefaults;
    private final Map<TankFaction, List<String>> armor;
    private final Map<TankFaction, List<String>> armorDefaults;
    private final List<String> armorCatalog;

    public PacketOpenMiscEditor(Map<CueKind, List<String>> cues,
                                Map<CueKind, List<String>> cueDefaults,
                                Map<TankFaction, List<String>> armor,
                                Map<TankFaction, List<String>> armorDefaults,
                                List<String> armorCatalog) {
        this.cues = cues;
        this.cueDefaults = cueDefaults;
        this.armor = armor;
        this.armorDefaults = armorDefaults;
        this.armorCatalog = armorCatalog;
    }

    public PacketOpenMiscEditor(FriendlyByteBuf buf) {
        this.cues = readCues(buf);
        this.cueDefaults = readCues(buf);
        this.armor = readArmor(buf);
        this.armorDefaults = readArmor(buf);
        this.armorCatalog = PacketOpenPoolEditor.readStringList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        writeCues(buf, this.cues);
        writeCues(buf, this.cueDefaults);
        writeArmor(buf, this.armor);
        writeArmor(buf, this.armorDefaults);
        PacketOpenPoolEditor.writeStringList(buf, this.armorCatalog);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MiscEditorClient.openScreen(
                        this.cues, this.cueDefaults, this.armor, this.armorDefaults, this.armorCatalog)));
        ctx.get().setPacketHandled(true);
    }

    private static Map<CueKind, List<String>> readCues(FriendlyByteBuf buf) {
        Map<CueKind, List<String>> map = new EnumMap<>(CueKind.class);
        for (CueKind kind : CueKind.values()) {
            map.put(kind, PacketOpenPoolEditor.readStringList(buf));
        }
        return map;
    }

    private static void writeCues(FriendlyByteBuf buf, Map<CueKind, List<String>> map) {
        for (CueKind kind : CueKind.values()) {
            PacketOpenPoolEditor.writeStringList(buf, map.get(kind));
        }
    }

    private static Map<TankFaction, List<String>> readArmor(FriendlyByteBuf buf) {
        Map<TankFaction, List<String>> map = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            map.put(faction, PacketOpenPoolEditor.readStringList(buf));
        }
        return map;
    }

    private static void writeArmor(FriendlyByteBuf buf, Map<TankFaction, List<String>> map) {
        for (TankFaction faction : TankFaction.values()) {
            PacketOpenPoolEditor.writeStringList(buf, map.get(faction));
        }
    }
}
