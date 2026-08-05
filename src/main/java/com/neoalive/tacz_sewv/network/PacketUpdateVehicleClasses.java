package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.util.MiscEditorAccess;
import com.neoalive.tacz_sewv.util.PoolEditorAccess;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Client → server: replace world vehicle classes / armor with the misc-editor snapshot. */
public class PacketUpdateVehicleClasses {

    private final Map<CueKind, List<String>> cues;
    private final Map<TankFaction, List<String>> armor;

    public PacketUpdateVehicleClasses(Map<CueKind, List<String>> cues,
                                      Map<TankFaction, List<String>> armor) {
        this.cues = cues;
        this.armor = armor;
    }

    public PacketUpdateVehicleClasses(FriendlyByteBuf buf) {
        this.cues = new EnumMap<>(CueKind.class);
        for (CueKind kind : CueKind.values()) {
            this.cues.put(kind, PacketOpenPoolEditor.readStringList(buf));
        }
        this.armor = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            this.armor.put(faction, PacketOpenPoolEditor.readStringList(buf));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        for (CueKind kind : CueKind.values()) {
            PacketOpenPoolEditor.writeStringList(buf, this.cues.get(kind));
        }
        for (TankFaction faction : TankFaction.values()) {
            PacketOpenPoolEditor.writeStringList(buf, this.armor.get(faction));
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PoolEditorAccess.mayEdit(player)) return;

            WorldVehicleClasses data = WorldVehicleClasses.get(player.serverLevel());
            for (CueKind kind : CueKind.values()) {
                data.setCues(kind, dedupe(this.cues.get(kind)));
            }
            for (TankFaction faction : TankFaction.values()) {
                data.setArmor(faction, dedupe(this.armor.get(faction)));
            }
            player.displayClientMessage(Component.translatable("message.tacz_sewv.misc.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }

    private static List<String> dedupe(List<String> raw) {
        List<String> cleaned = new ArrayList<>();
        if (raw == null) return cleaned;
        for (String id : raw) {
            if (id == null || id.isBlank()) continue;
            if (!cleaned.contains(id)) cleaned.add(id);
        }
        return cleaned;
    }
}
