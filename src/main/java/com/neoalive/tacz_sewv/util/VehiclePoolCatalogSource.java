package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.VehicleData;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Shared vehicle-id scan for the pool editor (server packet + client autocomplete cache).
 */
public final class VehiclePoolCatalogSource {

    private VehiclePoolCatalogSource() {}

    public static List<String> scan() {
        List<String> filtered = scanInternal(true);
        if (!filtered.isEmpty()) return filtered;
        return scanInternal(false);
    }

    private static List<String> scanInternal(boolean applyEngineFilter) {
        List<String> out = new ArrayList<>();

        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (id == null) continue;
            try {
                if (!isVehicleType(type)) continue;
                EngineType engine = engineTypeOf(type);
                if (applyEngineFilter && engine == EngineType.EMPTY) continue;
                out.add(id.toString());
            } catch (Throwable ignored) {
                // Malformed registry entry — skip.
            }
        }

        out.sort(String::compareTo);
        return out;
    }

    /**
     * {@link VehicleEntity} assignable from the factory base class, or SBW {@link VehicleData} knows
     * the type (covers odd Forge registrations where {@code getBaseClass()} is too generic).
     */
    private static boolean isVehicleType(EntityType<?> type) {
        try {
            if (VehicleEntity.class.isAssignableFrom(type.getBaseClass())) return true;
        } catch (Throwable ignored) {
            // getBaseClass() can throw on malformed registry entries — fall through to datapack probe.
        }
        try {
            engineTypeOf(type);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The engine class of a registered entity type ({@code WHEEL}, {@code HELICOPTER}, ...), or null when
     * the id is not registered or SBW has no vehicle data for it. The pool editor uses it to flag an id
     * sitting in the wrong class of pool.
     */
    @Nullable
    public static EngineType engineOf(ResourceLocation id) {
        try {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.containsKey(id) ? ForgeRegistries.ENTITY_TYPES.getValue(id) : null;
            return type == null ? null : engineTypeOf(type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static EngineType engineTypeOf(EntityType<?> type) {
        return VehicleData.getDefault(type).getEngineType();
    }
}
