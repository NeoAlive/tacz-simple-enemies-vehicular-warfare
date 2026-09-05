package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.Locale;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Classifies server {@code playSound} events into {@link AwarenessCues.TriggerKind} for the
 * awareness cue registry. Package-visible helpers are exercised by {@code AwarenessCuesSelfCheck}.
 *
 * <p>TaCZ gunfire is <b>not</b> classified here — it never hits {@code Level.playSound}; see
 * {@link AwarenessCueEvents} / {@code GunFireEvent}.
 */
public final class AwarenessCueSounds {

    private static final String SBW = "superbwarfare";
    private static final String MC = "minecraft";

    private AwarenessCueSounds() {}

    /**
     * @return trigger kind, or {@code null} if this sound should not become a cue
     */
    @Nullable
    public static AwarenessCues.TriggerKind classify(SoundEvent sound, SoundSource source,
            @Nullable Entity boundEntity) {
        ResourceLocation id = sound.getLocation();
        String ns = id.getNamespace();
        String path = id.getPath().toLowerCase(Locale.ROOT);

        if (TaczSewv.MODID.equals(ns) && source == SoundSource.VOICE) {
            return AwarenessCues.TriggerKind.CREW_VOICE;
        }
        if (MC.equals(ns)) {
            return classifyMinecraft(path);
        }
        if (!SBW.equals(ns)) return null;

        if (isDrone(path)) return AwarenessCues.TriggerKind.DRONE;
        if (isEngine(path)) {
            if (!(boundEntity instanceof VehicleEntity vehicle)) return null;
            if (vehicle.getDeltaMovement().horizontalDistanceSqr() <= 0.01) return null;
            return AwarenessCues.TriggerKind.VEHICLE_ENGINE;
        }
        if (isCannon(path)) return AwarenessCues.TriggerKind.VEHICLE_CANNON;
        return null;
    }

    /**
     * Path-only classification for headless self-check (no {@link SoundEvent} bootstrap needed).
     */
    @Nullable
    static AwarenessCues.TriggerKind classifyId(ResourceLocation id, SoundSource source,
            boolean movingVehicle) {
        String ns = id.getNamespace();
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (TaczSewv.MODID.equals(ns) && source == SoundSource.VOICE) {
            return AwarenessCues.TriggerKind.CREW_VOICE;
        }
        if (MC.equals(ns)) {
            return classifyMinecraft(path);
        }
        if (!SBW.equals(ns)) return null;
        if (isDrone(path)) return AwarenessCues.TriggerKind.DRONE;
        if (isEngine(path)) {
            return movingVehicle ? AwarenessCues.TriggerKind.VEHICLE_ENGINE : null;
        }
        if (isCannon(path)) return AwarenessCues.TriggerKind.VEHICLE_CANNON;
        return null;
    }

    @Nullable
    static AwarenessCues.TriggerKind classifyMinecraft(String path) {
        if (path.startsWith("entity.player.hurt") || path.equals("entity.generic.hurt")) {
            return AwarenessCues.TriggerKind.PLAYER_HURT;
        }
        if (path.equals("entity.generic.eat") || path.equals("entity.player.burp")) {
            return AwarenessCues.TriggerKind.PLAYER_EAT;
        }
        return null;
    }

    static boolean isDrone(String path) {
        return path.equals("drone_engine") || path.contains("drone_engine");
    }

    static boolean isEngine(String path) {
        return path.equals("wheel_vehicle_step")
                || path.equals("wheel_vehicle_skip")
                || path.equals("track_vehicle_step")
                || path.equals("track_vehicle_skip")
                || path.equals("vehicle_swim");
    }

    static boolean isCannon(String path) {
        if (path.contains("explosion")) return false;
        if (path.contains("mortar_fire") || path.equals("vehicle_strike")) return true;
        if (path.contains("_fire_3p") || path.contains("fire_3p_")) return true;
        if (path.contains("annihilator_fire") || path.contains("bl_132_fire")) return true;
        if (path.equals("medium_rocket_fire") || path.equals("small_rocket_fire")) return true;
        if (isVehicleGunFar(path)) return true;
        return false;
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
        if (path.contains("mg_17") || path.contains("ah_6")) return true;
        return false;
    }

    /**
     * Classify {@code SoundTool.playDistantSound} — vehicle/emplacement fire only, not infantry
     * guns or ambient explosions.
     */
    @Nullable
    public static AwarenessCues.TriggerKind classifyDistant(SoundEvent sound, @Nullable Entity sender) {
        return classifyDistantPath(sound.getLocation().getPath(), sender);
    }

    /** Path-only distant classification for self-check (no {@link SoundEvent} bootstrap). */
    @Nullable
    static AwarenessCues.TriggerKind classifyDistantPath(String pathRaw, @Nullable Entity sender) {
        String path = pathRaw.toLowerCase(Locale.ROOT);
        if (!isCannon(path)) return null;

        if (sender instanceof VehicleEntity) {
            return AwarenessCues.TriggerKind.VEHICLE_CANNON;
        }
        if (sender instanceof LivingEntity living && living.getVehicle() instanceof VehicleEntity) {
            return AwarenessCues.TriggerKind.VEHICLE_CANNON;
        }
        if (sender == null && (path.contains("mortar") || isVehicleGunFar(path) || path.contains("_fire_3p"))) {
            return AwarenessCues.TriggerKind.VEHICLE_CANNON;
        }
        return null;
    }
}
