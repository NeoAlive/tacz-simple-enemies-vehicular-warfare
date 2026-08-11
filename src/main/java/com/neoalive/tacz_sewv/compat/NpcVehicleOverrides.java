package com.neoalive.tacz_sewv.compat;

import java.util.Locale;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * NPC-seated-only addon overrides keyed by registry id. No addon classes are referenced —
 * softcompat jars may be absent. Player-driven hulls never match ({@link #npcSeated}).
 */
public final class NpcVehicleOverrides {

    /** Softskin FCP / Ash wheeled hulls that read too fast under AI. */
    private static final float SOFTSKIN_SPEED = 0.60f;
    private static final float PANTSIR_SPEED = 0.55f;

    /** Raised fire-assist floor for fcp:littlebird_armed. */
    private static final double LITTLEBIRD_CONE_FLOOR_DEG = 45.0;

    public enum EngineHint {
        NONE,
        HELICOPTER,
        AIRCRAFT
    }

    private NpcVehicleOverrides() {}

    public static boolean npcSeated(@Nullable VehicleEntity hull) {
        return hull != null && hull.getFirstPassenger() instanceof AbstractUnit;
    }

    @Nullable
    public static String entityId(@Nullable VehicleEntity hull) {
        if (hull == null) return null;
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(hull.getType());
        return key == null ? null : key.toString().toLowerCase(Locale.ROOT);
    }

    /** Thrust scale stacked on health mobility; 1.0 = no cap. */
    public static float speedScale(@Nullable VehicleEntity hull) {
        if (!npcSeated(hull)) return 1.0f;
        String id = entityId(hull);
        if (id == null) return 1.0f;
        if (id.startsWith("fcp:")) {
            if (contains(id, "uaz") || contains(id, "gaz") || contains(id, "hmmwv")
                    || contains(id, "kozak") || contains(id, "matv")) {
                return SOFTSKIN_SPEED;
            }
        }
        if (id.equals("ashvehicle:pa_pantsir") || contains(id, "pa_pantsir")) {
            return PANTSIR_SPEED;
        }
        return 1.0f;
    }

    /**
     * Unarmed / troop-lift helicopters: transit only, no orbit/strafe combat.
     * Exact ids — armed huey/mi17 variants keep combat AI.
     */
    public static boolean isTransportHeli(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        if (id == null) return false;
        return id.equals("fcp:huey")
                || id.equals("fcp:mi17")
                || id.equals("ashvehicle:uh_60")
                || id.equals("vvp:mi_8")
                || id.equals("vvp:nh_90");
    }

    public static boolean isHeavyHeli(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        if (id == null) return false;
        return id.equals("ashvehicle:ah-64")
                || id.equals("ashvehicle:ka52")
                || contains(id, "ah-64") && id.startsWith("ashvehicle:")
                || contains(id, "ka52") && id.startsWith("ashvehicle:");
    }

    public static boolean isLittlebirdArmed(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        return id != null && id.equals("fcp:littlebird_armed");
    }

    /** Extra NPC fire-assist cone floor (degrees), or 0 if none. */
    public static double heliConeFloorDeg(@Nullable VehicleEntity hull) {
        return isLittlebirdArmed(hull) ? LITTLEBIRD_CONE_FLOOR_DEG : 0.0;
    }

    /** Lock ground weapon slot for the engagement (littlebird Cannon/Rocket thrash). */
    public static boolean heliWeaponLock(@Nullable VehicleEntity hull) {
        return isLittlebirdArmed(hull);
    }

    public static boolean isT80bvKantemir(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        return id != null && id.equals("mcsp:t80bv_kantemir");
    }

    /**
     * Remap datapack {@code Empty} engines for Ash VTOL airframes so spawn/goals route correctly.
     */
    public static EngineHint engineHint(@Nullable String entityId) {
        if (entityId == null) return EngineHint.NONE;
        String id = entityId.toLowerCase(Locale.ROOT);
        if (id.equals("ashvehicle:v-22") || id.equals("ashvehicle:v_22")) {
            return EngineHint.HELICOPTER;
        }
        if (id.equals("ashvehicle:f-35b") || id.equals("ashvehicle:f_35b")) {
            return EngineHint.AIRCRAFT;
        }
        return EngineHint.NONE;
    }

    public static EngineHint engineHint(@Nullable VehicleEntity hull) {
        return engineHint(entityId(hull));
    }

    /** Apply id-keyed Empty remaps; returns {@code declared} unchanged when no hint. */
    @Nullable
    public static EngineType applyEngineHint(@Nullable String entityId, @Nullable EngineType declared) {
        EngineHint hint = engineHint(entityId);
        if (hint == EngineHint.HELICOPTER) return EngineType.HELICOPTER;
        if (hint == EngineHint.AIRCRAFT) return EngineType.AIRCRAFT;
        return declared;
    }

    @Nullable
    public static EngineType applyEngineHint(@Nullable VehicleEntity hull) {
        return applyEngineHint(entityId(hull),
                hull == null ? null : safeEngine(hull));
    }

    @Nullable
    private static EngineType safeEngine(VehicleEntity hull) {
        try {
            return hull.computed().getEngineType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean contains(String id, String clue) {
        return id.contains(clue);
    }
}
