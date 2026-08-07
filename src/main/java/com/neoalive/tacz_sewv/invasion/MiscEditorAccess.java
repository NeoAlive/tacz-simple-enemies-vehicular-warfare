package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenMiscEditor;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.PoolEditorAccess;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/** Opens the op-only misc cue/armor editor ({@code /sewv pool misc}). */
public final class MiscEditorAccess {

    private MiscEditorAccess() {}

    public static int open(ServerPlayer player) {
        if (!PoolEditorAccess.mayEdit(player)) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.pool.denied"), true);
            return 0;
        }
        WorldVehicleClasses data = WorldVehicleClasses.get(player.serverLevel());
        Map<CueKind, List<String>> cues = new EnumMap<>(CueKind.class);
        Map<CueKind, List<String>> cueDefaults = new EnumMap<>(CueKind.class);
        for (CueKind kind : CueKind.values()) {
            cues.put(kind, new ArrayList<>(data.listCues(kind)));
            cueDefaults.put(kind, new ArrayList<>(WorldVehicleClasses.builtInCues(kind)));
        }
        Map<TankFaction, List<String>> armor = new EnumMap<>(TankFaction.class);
        Map<TankFaction, List<String>> armorDefaults = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            armor.put(faction, new ArrayList<>(data.listArmor(faction)));
            armorDefaults.put(faction, new ArrayList<>(WorldVehicleClasses.builtInArmor(faction)));
        }
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketOpenMiscEditor(cues, cueDefaults, armor, armorDefaults, armorCatalog()));
        return 1;
    }

    /** Armor items for the misc-editor add catalog. */
    public static List<String> armorCatalog() {
        List<String> out = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (!(item instanceof ArmorItem)) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) out.add(id.toString());
        }
        out.sort(String::compareTo);
        return out;
    }
}
