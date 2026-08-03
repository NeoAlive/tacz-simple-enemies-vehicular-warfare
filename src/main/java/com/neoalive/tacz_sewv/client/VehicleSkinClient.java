package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.util.CrewFacts;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client mirror of the server sticky {@code sewv:vehicle_skin} tag (synced by packet).
 * Resolved texture lookup is cached as the faction tag itself until a clear/apply packet.
 */
@OnlyIn(Dist.CLIENT)
public final class VehicleSkinClient {

    private static final ConcurrentHashMap<Integer, CrewFacts.Faction> APPLIED = new ConcurrentHashMap<>();

    private VehicleSkinClient() {
    }

    public static void put(int entityId, @Nullable CrewFacts.Faction faction) {
        if (faction == null) {
            APPLIED.remove(entityId);
        } else {
            APPLIED.put(entityId, faction);
        }
    }

    @Nullable
    public static CrewFacts.Faction get(int entityId) {
        return APPLIED.get(entityId);
    }

    public static void clearAll() {
        APPLIED.clear();
    }

    @Nullable
    public static ResourceLocation textureFor(VehicleEntity vehicle) {
        CrewFacts.Faction faction = APPLIED.get(vehicle.getId());
        if (faction == null) return null;
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType());
        if (typeId == null) return null;
        return VehicleSkinRegistry.get(typeId.getPath(), faction);
    }
}
