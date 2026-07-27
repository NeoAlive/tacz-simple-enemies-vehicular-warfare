package com.neoalive.tacz_sewv.entity.ai.command;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Who counts as a command-tier crew: the <b>driver</b> of a ground utility hull.
 *
 * <p>Counts vehicles, not passengers — one seat per hull, the same units
 * {@code DriveVehicleGoal} runs the utility brain on. Engine type is cached per hull identity
 * because {@code computed()} is expensive and the answer never changes.
 */
public final class CommandEligibility {

    private static final Map<VehicleEntity, EngineType> ENGINE_CACHE = new IdentityHashMap<>();

    private CommandEligibility() {}

    /**
     * The driving unit of an eligible hull, or null if this entity is not a command candidate.
     */
    @Nullable
    public static AbstractUnit eligibleDriver(Entity entity) {
        if (!(entity instanceof VehicleEntity hull) || hull instanceof MortarEntity) return null;
        if (!(hull.getFirstPassenger() instanceof AbstractUnit driver)) return null;
        if (!isGroundUtilityEngine(engineType(hull))) return null;
        return driver;
    }

    static boolean isGroundUtilityEngine(EngineType type) {
        return type == EngineType.WHEEL || type == EngineType.TRACK || type == EngineType.FIXED;
    }

    static EngineType engineType(VehicleEntity hull) {
        EngineType cached = ENGINE_CACHE.get(hull);
        if (cached != null) return cached;
        EngineType type;
        try {
            type = hull.computed().getEngineType();
        } catch (Throwable ignored) {
            // Unreadable data: treat as ineligible (safe low-drama fallback).
            type = EngineType.HELICOPTER;
        }
        if (type == null) type = EngineType.HELICOPTER;
        ENGINE_CACHE.put(hull, type);
        return type;
    }

    /** Drop cache entries for hulls that left the world — called from the coordinator scan. */
    static void forget(VehicleEntity hull) {
        ENGINE_CACHE.remove(hull);
    }

    static void clearCache() {
        ENGINE_CACHE.clear();
    }
}
