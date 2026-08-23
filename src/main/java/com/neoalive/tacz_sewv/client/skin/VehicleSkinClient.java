package com.neoalive.tacz_sewv.client.skin;

import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Client mirror of the server sticky {@code sewv:vehicle_skin} (+ salt) tags, synced by packet.
 */
@OnlyIn(Dist.CLIENT)
public final class VehicleSkinClient {

    private static final ConcurrentHashMap<Integer, Applied> APPLIED = new ConcurrentHashMap<>();

    private VehicleSkinClient() {
    }

    public static void put(int entityId, @Nullable CrewFacts.Faction faction, int salt) {
        if (faction == null) {
            APPLIED.remove(entityId);
        } else {
            APPLIED.put(entityId, new Applied(faction, salt));
        }
    }

    @Nullable
    public static CrewFacts.Faction get(int entityId) {
        Applied applied = APPLIED.get(entityId);
        return applied == null ? null : applied.faction;
    }

    /** Sticky salt, or {@code 0} when none (spray-GUI previews / unset). */
    public static int salt(int entityId) {
        Applied applied = APPLIED.get(entityId);
        return applied == null ? 0 : applied.salt;
    }

    public static void clearAll() {
        APPLIED.clear();
    }

    @Nullable
    public static ResourceLocation textureFor(VehicleEntity vehicle) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType());
        if (typeId == null) return null;
        String path = typeId.getPath();

        // Spray-GUI / repair-tool picks use exact catalog ids (ru_0); salt % pool is only for
        // spawn sticky paint where skinId is the bare faction key.
        VehicleSkinRegistry.CatalogId catalog = VehicleSkinRegistry.parseSkinId(vehicle.getSkinId());
        if (catalog != null && catalog.variant() >= 0) {
            ResourceLocation exact = VehicleSkinRegistry.getExact(path, catalog.faction(), catalog.variant());
            if (exact != null) return exact;
        }

        Applied applied = APPLIED.get(vehicle.getId());
        CrewFacts.Faction faction = applied != null
                ? applied.faction
                : (catalog != null ? catalog.faction() : null);
        if (faction == null) return null;
        int salt = applied != null ? applied.salt : 0;
        return VehicleSkinRegistry.get(path, faction, salt);
    }

    private record Applied(CrewFacts.Faction faction, int salt) {
    }
}
