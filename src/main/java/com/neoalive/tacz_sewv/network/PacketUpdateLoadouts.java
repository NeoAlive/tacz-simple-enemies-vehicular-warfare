package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.loadout.LoadoutEditorAccess;
import com.neoalive.tacz_sewv.loadout.LoadoutLayer;
import com.neoalive.tacz_sewv.loadout.LoadoutManager;
import com.neoalive.tacz_sewv.loadout.LoadoutMerge;
import com.neoalive.tacz_sewv.loadout.LoadoutRow;
import com.neoalive.tacz_sewv.loadout.LoadoutValidator;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Client → server: replace the world loadout layer with the editor's snapshot, then re-apply it live. */
public class PacketUpdateLoadouts {

    private final Map<TankFaction, LoadoutMerge.Faction> layers;

    public PacketUpdateLoadouts(Map<TankFaction, LoadoutMerge.Faction> layers) {
        this.layers = layers;
    }

    public PacketUpdateLoadouts(FriendlyByteBuf buf) {
        this.layers = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) this.layers.put(faction, LoadoutWire.readFaction(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        for (TankFaction faction : TankFaction.values()) LoadoutWire.writeFaction(buf, this.layers.get(faction));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !LoadoutEditorAccess.mayEdit(player)) return;
            LoadoutLayer layer = LoadoutLayer.get(player.server);
            if (layer == null) return;

            boolean extended = LoadoutManager.extendedPresent();
            List<String> problems = new ArrayList<>();
            int kept = 0;
            for (TankFaction faction : TankFaction.values()) {
                LoadoutMerge.Faction in = this.layers.get(faction);
                LoadoutMerge.Faction clean = new LoadoutMerge.Faction();
                clean.managed = in.managed;
                clean.replace = in.replace;
                clean.hidden.addAll(in.hidden);
                Set<String> names = new HashSet<>();
                for (LoadoutRow row : in.rows) {
                    LoadoutRow ok = LoadoutValidator.sanitize(row, extended, faction != TankFaction.PMC, problems);
                    if (ok == null) continue;
                    // Names are JSON keys of one file: a duplicate would silently overwrite its twin.
                    String base = ok.name;
                    for (int n = 2; !names.add(ok.name); n++) ok.name = base + "_" + n;
                    clean.rows.add(ok);
                    kept++;
                }
                layer.set(LoadoutLayer.folderOf(faction), clean);
            }
            boolean applied = LoadoutManager.reapply(player.server);
            player.displayClientMessage(Component.translatable(
                    applied ? "message.tacz_sewv.loadout.saved" : "message.tacz_sewv.loadout.saved_inactive", kept), true);
            problems.stream().limit(5).forEach(p ->
                    player.sendSystemMessage(Component.literal("[loadout] " + p)));
            if (problems.size() > 5) {
                player.sendSystemMessage(Component.literal("[loadout] ...and " + (problems.size() - 5) + " more"));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
