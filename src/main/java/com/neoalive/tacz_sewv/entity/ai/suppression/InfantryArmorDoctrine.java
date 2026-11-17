package com.neoalive.tacz_sewv.entity.ai.suppression;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * What SEM's infantry decision layer should make of a target that is riding a vehicle.
 *
 * <p>SEM's {@code CombatDecisionContext.weaponThreat} scores a target by substring-matching the item
 * in its hand, so a T-90's crewman with a rifle — or nothing (0.05) — reads as harmless, and SEM's
 * riflemen PUSH tanks. This turns the hull's class into a threat tier: a rifleman's pressure rises
 * and its confidence falls (so SEM's own COVER / RETREAT thresholds trip), while a unit holding an
 * anti-armour launcher gains confidence instead, so it closes into range rather than hiding with
 * the rest.
 *
 * <p>Only the numbers live here; {@code MixinSemCombatDecision} applies them to SEM's record. The
 * maths is pure, so {@code selfCheckSuppression} pins it.
 */
public final class InfantryArmorDoctrine {

    /** Pressure a rifleman gains against a full-tier hull. */
    public static final float RIFLEMAN_PRESSURE = 0.45f;
    /** Confidence a rifleman loses against a full-tier hull. */
    public static final float RIFLEMAN_CONFIDENCE = 0.35f;
    /** Confidence an anti-armour gunner gains against a full-tier hull. */
    public static final float GUNNER_CONFIDENCE = 0.25f;

    private InfantryArmorDoctrine() {}

    /** Threat tier of whatever {@code target} is riding, 0 (on foot / nothing) to 1 (a tank). */
    public static float tierOf(@Nullable LivingEntity target) {
        if (target == null) return 0.0f;
        Entity mount = target.getRootVehicle();
        if (mount instanceof VehicleEntity hull) {
            if (hull.isRemoved()) return 0.0f;
            return tier(HullFacts.engineType(hull), HullFacts.isIfvHull(hull));
        }
        return LvCompat.isArmouredHull(mount) ? 0.8f : 0.0f;
    }

    // --- pure (headless) ---

    /** A tank is the full tier; an IFV, a gunship and a fixed emplacement are most of one. */
    public static float tier(@Nullable EngineType engine, boolean ifv) {
        if (engine == null) return 0.0f;
        return switch (engine) {
            case TRACK -> ifv ? 0.8f : 1.0f;
            case WHEEL -> ifv ? 0.8f : 0.5f;
            case HELICOPTER, AIRCRAFT -> 0.8f;
            case FIXED -> 0.7f;
            case SHIP -> 0.6f;
            default -> 0.0f;
        };
    }

    public static float riflemanPressure(float pressure, float tier) {
        return clamp01(pressure + RIFLEMAN_PRESSURE * tier);
    }

    public static float riflemanConfidence(float confidence, float tier) {
        return clamp01(confidence - RIFLEMAN_CONFIDENCE * tier);
    }

    public static float gunnerConfidence(float confidence, float tier) {
        return clamp01(confidence + GUNNER_CONFIDENCE * tier);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : Math.min(v, 1.0f);
    }
}
