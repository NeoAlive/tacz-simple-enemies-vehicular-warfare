package com.neoalive.tacz_sewv.util;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/**
 * Which hull-class pool an engine type belongs in. The one rule shared by the pool editor's
 * mismatch warning, the team_base force tabs and invasion start validation, so the three can never
 * disagree about whether a vehicle "is" a helicopter.
 */
public final class VehicleClassify {

    private VehicleClassify() {}

    /**
     * True when {@code engine} belongs in {@code pool}. Only the four hull classes are judged; TOW /
     * mortar / grenade pools hold anything. Ground legitimately takes Fixed mounts.
     */
    public static boolean fits(Category pool, EngineType engine) {
        return switch (pool) {
            case GROUND -> engine != EngineType.HELICOPTER && engine != EngineType.SHIP
                    && engine != EngineType.AIRCRAFT && engine != EngineType.AIRSHIP
                    && engine != EngineType.EMPTY;
            case SURFACE -> engine == EngineType.SHIP;
            case PLANE -> engine == EngineType.AIRCRAFT;
            case ROTOR -> engine == EngineType.HELICOPTER;
            default -> true;
        };
    }

    /** True when {@code id} is a registered SBW vehicle whose data puts it in {@code pool}. */
    public static boolean sbwIdFits(Category pool, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return false;
        EngineType engine = VehiclePoolCatalogSource.engineOf(rl);
        return engine != null && fits(pool, engine);
    }

    /** Hull-class pool an SBW id belongs in, or null when unknown / not a vehicle. */
    @Nullable
    public static Category categoryOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        EngineType engine = VehiclePoolCatalogSource.engineOf(rl);
        if (engine == null || engine == EngineType.EMPTY) return null;
        for (Category c : new Category[] {Category.ROTOR, Category.PLANE, Category.SURFACE, Category.GROUND}) {
            if (fits(c, engine)) return c;
        }
        return null;
    }
}
