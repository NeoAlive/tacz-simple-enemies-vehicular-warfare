package com.neoalive.tacz_sewv.config;

import net.minecraftforge.common.ForgeConfigSpec;

import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport.SbwClass;

/**
 * Runtime overlay for the Balancing → Easy Mode pack. Does not mutate Forge config values;
 * call sites read through these helpers so the pack only applies while {@link #active()} and
 * the relevant child toggle are on.
 */
public final class EasyMode {

    private EasyMode() {}

    public static boolean active() {
        return SewvConfig.EASY_MODE.get();
    }

    private static boolean on(ForgeConfigSpec.BooleanValue child) {
        return active() && child.get();
    }

    // --- Ambient ---

    public static boolean shellingEnabled() {
        if (on(SewvConfig.EASY_DISABLE_SHELLING)) return false;
        return SewvConfig.SHELLING_EVENTS_ENABLED.get();
    }

    public static boolean largeCombatEnabled() {
        if (on(SewvConfig.EASY_DISABLE_LARGE_COMBAT)) return false;
        return SewvConfig.LARGE_COMBAT_EVENTS_ENABLED.get();
    }

    public static boolean farCombatTanksAllowed() {
        return !on(SewvConfig.EASY_NO_FAR_COMBAT_TANKS);
    }

    public static boolean garrisonVehiclesEnabled() {
        if (on(SewvConfig.EASY_NO_WORLD_ARMOR)) return false;
        return SewvConfig.GARRISON_VEHICLES_ENABLED.get();
    }

    public static boolean structureVehiclesEnabled() {
        if (on(SewvConfig.EASY_NO_WORLD_ARMOR)) return false;
        return SewvConfig.STRUCTURE_VEHICLES_ENABLED.get();
    }

    public static double failureMultiplier(double base) {
        if (on(SewvConfig.EASY_LOWER_EVENT_ESCALATION)) return base * 0.5;
        return base;
    }

    // --- AI lethality ---

    public static double aiAimSpreadDegrees() {
        if (on(SewvConfig.EASY_SOFT_AI_AIM)) return 8.0;
        return SewvConfig.AI_AIM_SPREAD_DEG.get();
    }

    public static double aiFireAssistConeDeg() {
        if (on(SewvConfig.EASY_SOFT_AI_AIM)) return 18.0;
        return SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get();
    }

    public static int aiFireCooldownTicks() {
        if (on(SewvConfig.EASY_SOFT_AI_ROF)) return 15;
        return SewvConfig.AI_FIRE_COOLDOWN_TICKS.get();
    }

    public static int mortarFireCooldownTicks() {
        if (on(SewvConfig.EASY_SOFT_AI_ROF)) return 100;
        return SewvConfig.MORTAR_FIRE_COOLDOWN_TICKS.get();
    }

    public static int type63FireCooldownTicks() {
        if (on(SewvConfig.EASY_SOFT_AI_ROF)) return 25;
        return SewvConfig.TYPE63_FIRE_COOLDOWN_TICKS.get();
    }

    public static boolean factionInfiniteEnergy() {
        if (on(SewvConfig.EASY_FINITE_ENEMY_LOGISTICS)) return false;
        return SewvConfig.FACTION_INFINITE_ENERGY.get();
    }

    public static boolean factionInfiniteAmmo() {
        if (on(SewvConfig.EASY_FINITE_ENEMY_LOGISTICS)) return false;
        return SewvConfig.FACTION_INFINITE_AMMO.get();
    }

    /** Bail-when-dry only while the finite-logistics child is active. */
    public static boolean bailWhenDry() {
        return on(SewvConfig.EASY_FINITE_ENEMY_LOGISTICS);
    }

    public static double atSecondGunnerChance() {
        if (on(SewvConfig.EASY_SOFT_AT_DISMOUNTS)) return 0.0;
        return SewvConfig.AT_SECOND_GUNNER_CHANCE.get();
    }

    public static int maxAtGunners() {
        if (on(SewvConfig.EASY_SOFT_AT_DISMOUNTS)) return 1;
        return 2;
    }

    public static double atEngageRange() {
        if (on(SewvConfig.EASY_SOFT_AT_DISMOUNTS)) return 32.0;
        return SewvConfig.AT_ENGAGE_RANGE.get();
    }

    /** Launchers normally ignore spread; Easy Mode soft-AT applies {@link SewvConfig#SBW_AI_SPREAD}. */
    public static double sbwAiSpread(SbwClass cls) {
        double base = SewvConfig.SBW_AI_SPREAD.get();
        if (cls == SbwClass.SMALL_ARMS || cls == SbwClass.SNIPER) return base;
        if (on(SewvConfig.EASY_SOFT_AT_DISMOUNTS)
                && (cls == SbwClass.AT || cls == SbwClass.AA)) {
            return base;
        }
        return 0.0;
    }

    public static int droneMaxPerEngineer() {
        if (on(SewvConfig.EASY_NO_RECON_DRONES)) return 0;
        return SewvConfig.DRONE_MAX_PER_ENGINEER.get();
    }

    public static boolean factionOrganicComms() {
        if (on(SewvConfig.EASY_SOFT_SUPPORT_CALLS)) return false;
        return SewvConfig.FACTION_ORGANIC_COMMS.get();
    }

    public static int supportCallIntervalTicks() {
        if (on(SewvConfig.EASY_SOFT_SUPPORT_CALLS)) {
            return Math.max(SewvConfig.SUPPORT_CALL_INTERVAL_TICKS.get(), 400);
        }
        return SewvConfig.SUPPORT_CALL_INTERVAL_TICKS.get();
    }

    public static double mortarRadioRange() {
        if (on(SewvConfig.EASY_SOFT_SUPPORT_CALLS)) {
            return Math.min(SewvConfig.MORTAR_RADIO_RANGE.get(), 384.0);
        }
        return SewvConfig.MORTAR_RADIO_RANGE.get();
    }

    // --- Engagement ---

    public static double tacticalScale() {
        if (on(SewvConfig.EASY_TIGHTER_ENGAGE_RINGS)) return 1.0;
        return SewvConfig.TACTICAL_SCALE.get();
    }

    public static boolean vehicleTargetRequireLos() {
        if (on(SewvConfig.EASY_KEEP_LOS_GATES)) return true;
        return SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get();
    }

    public static double smokeBlockRadius() {
        if (on(SewvConfig.EASY_KEEP_LOS_GATES)) {
            return Math.max(SewvConfig.SMOKE_BLOCK_RADIUS.get(), 10.0);
        }
        return SewvConfig.SMOKE_BLOCK_RADIUS.get();
    }

    // --- Player sustain ---

    public static boolean playerDamageScaleEnabled() {
        return on(SewvConfig.EASY_PLAYER_DAMAGE_SCALE_ENABLED);
    }

    public static float playerDamageScale() {
        return SewvConfig.EASY_PLAYER_DAMAGE_SCALE.get().floatValue();
    }

    public static boolean playerPrioritySoft() {
        return on(SewvConfig.EASY_PLAYER_PRIORITY_SOFT);
    }

    /** Respawn grace before AI may proactively lock the player (game ticks). */
    public static final int PLAYER_RESPAWN_GRACE_TICKS = 800;
}
