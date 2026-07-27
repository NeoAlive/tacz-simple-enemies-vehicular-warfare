package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenPoolEditor;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Shared open path for the op-only pool clipboard and {@code /sewv pool}. */
public final class PoolEditorAccess {

    private PoolEditorAccess() {}

    public static boolean mayEdit(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    public static int open(ServerPlayer player) {
        if (!mayEdit(player)) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.pool.denied"), true);
            return 0;
        }
        WorldVehiclePools data = WorldVehiclePools.get(player.serverLevel());
        Map<TankFaction, Map<Category, List<String>>> snapshot = new EnumMap<>(TankFaction.class);
        Map<TankFaction, Map<Category, List<String>>> defaults = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            Map<Category, List<String>> defCat = new EnumMap<>(Category.class);
            for (Category cat : Category.values()) {
                byCat.put(cat, new ArrayList<>(data.list(faction, cat)));
                defCat.put(cat, new ArrayList<>(WorldVehiclePools.builtInDefaults(faction, cat)));
            }
            snapshot.put(faction, byCat);
            defaults.put(faction, defCat);
        }
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketOpenPoolEditor(snapshot, defaults, catalog()));
        return 1;
    }

    /** Registry ids whose entity class is a SuperbWarfare vehicle (covers MCSP/ASH/FCP too). */
    public static List<String> catalog() {
        List<String> out = new ArrayList<>();
        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES) {
            if (!VehicleEntity.class.isAssignableFrom(type.getBaseClass())) continue;
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (id != null) out.add(id.toString());
        }
        out.sort(String::compareTo);
        return out;
    }
}
