package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.Locale;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Picks SBW cannon / vehicle-gun fire out of the sounds the server plays. Package-visible helpers
 * are exercised by {@code AwarenessCuesSelfCheck}.
 *
 * <p>TaCZ gunfire is <b>not</b> classified here — it never hits a server sound call; see
 * {@link AwarenessCueEvents} / {@code GunFireEvent}.
 */
public final class AwarenessCueSounds {

    private AwarenessCueSounds() {}

    /** {@code Level.playSound} path: is this an SBW cannon sound? */
    public static boolean isCannonSound(SoundEvent sound) {
        return "superbwarfare".equals(sound.getLocation().getNamespace())
                && isCannon(sound.getLocation().getPath().toLowerCase(Locale.ROOT));
    }

    static boolean isCannon(String path) {
        if (path.contains("explosion")) return false;
        if (path.contains("mortar_fire") || path.equals("vehicle_strike")) return true;
        if (path.contains("_fire_3p") || path.contains("fire_3p_")) return true;
        if (path.contains("annihilator_fire") || path.contains("bl_132_fire")) return true;
        if (path.equals("medium_rocket_fire") || path.equals("small_rocket_fire")) return true;
        return isVehicleGunFar(path);
    }

    /**
     * SBW {@code fire3PFar} / {@code fire3PVeryFar} paths ({@code t_90a_far}, …) — not routed
     * through {@code Level.playSound}.
     */
    static boolean isVehicleGunFar(String path) {
        if (path.contains("explosion")) return false;
        if (!path.endsWith("_far") && !path.endsWith("_very_far")) return false;
        if (path.contains("_cannon_") || path.contains("_cannon_far")) return true;
        if (path.contains("mk_42") || path.contains("annihilator") || path.contains("plz_05")) {
            return true;
        }
        if (path.contains("t_90a") || path.contains("m_1a_2") || path.contains("ztz_99a")) return true;
        if (path.contains("bradley") || path.contains("bmp_2") || path.contains("lav_150")) return true;
        if (path.contains("yx_100") || path.contains("mi_28") || path.contains("bofos")) return true;
        return path.contains("mg_17") || path.contains("ah_6");
    }

    /**
     * {@code SoundTool.playDistantSound}: true for vehicle / emplacement fire, not infantry guns or
     * ambient explosions.
     */
    public static boolean isDistantCannon(SoundEvent sound, @Nullable Entity sender) {
        return isDistantCannonPath(sound.getLocation().getPath(), sender);
    }

    /** Path-only distant classification for self-check (no {@link SoundEvent} bootstrap). */
    static boolean isDistantCannonPath(String pathRaw, @Nullable Entity sender) {
        String path = pathRaw.toLowerCase(Locale.ROOT);
        if (!isCannon(path)) return false;
        if (sender instanceof VehicleEntity) return true;
        if (sender instanceof LivingEntity living && living.getVehicle() instanceof VehicleEntity) return true;
        return sender == null && (path.contains("mortar") || isVehicleGunFar(path) || path.contains("_fire_3p"));
    }
}
