package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.IdentityHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.VehicleData;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.NpcVehicleOverrides;

/**
 * Who counts as a command-tier crew: the <b>driver</b> (seat 0) of a ground utility hull.
 *
 * <p><b>Read-only on purpose.</b> SBW's {@code getFirstPassenger()} / {@code getNthEntity} /
 * {@code getSeatIndex} all call {@code checkSeatsSize()}, which can resize
 * {@code orderedPassengers} from {@code computed().seats().size}. Doing that on the command
 * cadence made {@code unit.getVehicle()} flap and {@link com.neoalive.tacz_sewv.entity.ai.goal.VehicleTargetScanGoal}
 * drop its lock. This class never takes that path:
 * <ul>
 *   <li>Engine type — {@link VehicleData#getDefault(EntityType)} (static datapack), cached per type.</li>
 *   <li>Driver — {@link VehicleEntity#getPassengers()} + {@link VehicleEntity#getTagSeatIndex}
 *       ({@code SBWSeatIndex} NBT), which does not touch seat layout.</li>
 * </ul>
 */
public final class CommandEligibility {

    /** Engine class is a property of the entity type's datapack, not of a live hull instance. */
    private static final Map<EntityType<?>, EngineType> ENGINE_BY_TYPE = new IdentityHashMap<>();

    private CommandEligibility() {}

    /**
     * The driving unit of an eligible hull, or null if this entity is not a command candidate.
     */
    @Nullable
    public static AbstractUnit eligibleDriver(Entity entity) {
        if (!(entity instanceof VehicleEntity hull)
                || hull instanceof MortarEntity
                || hull instanceof Type63Entity) return null;
        if (!isGroundUtilityEngine(engineType(hull))) return null;
        return seatZeroDriver(hull);
    }

    /**
     * Seat 0's passenger if it is an {@link AbstractUnit}, without calling {@code getFirstPassenger}.
     */
    @Nullable
    static AbstractUnit seatZeroDriver(VehicleEntity hull) {
        try {
            for (Entity passenger : hull.getPassengers()) {
                if (!(passenger instanceof AbstractUnit unit)) continue;
                // getTagSeatIndex reads SBWSeatIndex only — unlike getSeatIndex, no checkSeatsSize.
                if (hull.getTagSeatIndex(passenger) == 0) return unit;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    static boolean isGroundUtilityEngine(EngineType type) {
        return type == EngineType.WHEEL || type == EngineType.TRACK || type == EngineType.FIXED;
    }

    /**
     * Datapack engine type for this hull's entity type. Never calls {@code hull.computed()} —
     * that path shares the VehicleData cache {@code checkSeatsSize} reads for seat count.
     * Ash VTOL Empty engines are remapped via {@link NpcVehicleOverrides}.
     */
    static EngineType engineType(VehicleEntity hull) {
        EntityType<?> type = hull.getType();
        EngineType cached = ENGINE_BY_TYPE.get(type);
        if (cached != null) return cached;
        EngineType engine;
        try {
            engine = VehicleData.getDefault(type).getEngineType();
        } catch (Throwable ignored) {
            engine = EngineType.HELICOPTER;
        }
        String id = null;
        try {
            var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (key != null) id = key.toString();
        } catch (Throwable ignored) {}
        engine = NpcVehicleOverrides.applyEngineHint(id, engine);
        if (engine == null || engine == EngineType.EMPTY) engine = EngineType.HELICOPTER;
        ENGINE_BY_TYPE.put(type, engine);
        return engine;
    }

    static void clearCache() {
        ENGINE_BY_TYPE.clear();
    }
}
