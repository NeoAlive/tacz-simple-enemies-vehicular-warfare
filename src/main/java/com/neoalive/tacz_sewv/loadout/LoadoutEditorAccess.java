package com.neoalive.tacz_sewv.loadout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.invasion.MiscEditorAccess;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenLoadoutEditor;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Op-only open path for {@code /sewv pool weapons}; mirrors {@code PoolEditorAccess}. */
public final class LoadoutEditorAccess {

    private LoadoutEditorAccess() {}

    public static boolean mayEdit(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    /**
     * Writes one faction's layer as a file SEM can read as-is ({@code unit_loadouts/<faction>/…json}),
     * for anyone who wants to ship it as a real datapack. Serialiser only: the format already is SEM's.
     * Returns the file, or null when there is nothing to export or the write failed.
     */
    @Nullable
    public static Path export(MinecraftServer server, TankFaction faction) {
        LoadoutLayer layer = LoadoutLayer.get(server);
        if (layer == null) return null;
        String folder = LoadoutLayer.folderOf(faction);
        LoadoutMerge.Faction f = layer.faction(folder);
        if (f.rows.isEmpty()) return null;
        JsonObject loadouts = new JsonObject();
        f.rows.forEach(r -> loadouts.add(r.name, r.toJson()));
        JsonObject root = new JsonObject();
        root.add("loadouts", loadouts);
        Path out = FMLPaths.CONFIGDIR.get().resolve("tacz_sewv_loadouts_" + folder + ".json");
        try {
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    public static int open(ServerPlayer player) {
        if (!mayEdit(player)) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.pool.denied"), true);
            return 0;
        }
        LoadoutLayer layer = LoadoutLayer.get(player.server);
        if (layer == null) return 0;
        Map<TankFaction, LoadoutMerge.Faction> layers = new EnumMap<>(TankFaction.class);
        Map<TankFaction, List<LoadoutManager.Inherited>> inherited = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            String folder = LoadoutLayer.folderOf(faction);
            layers.put(faction, layer.faction(folder));
            inherited.put(faction, new ArrayList<>(LoadoutManager.inherited(folder)));
        }
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketOpenLoadoutEditor(new PacketOpenLoadoutEditor.Data(
                        LoadoutManager.hookActive(), LoadoutManager.extendedPresent(),
                        LoadoutManager.configModPresent(), layers, inherited,
                        WeaponCatalogSource.gunIds(), WeaponCatalogSource.attachmentIds(),
                        MiscEditorAccess.armorCatalog())));
        return 1;
    }
}
