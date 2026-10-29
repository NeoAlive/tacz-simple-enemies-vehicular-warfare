package com.neoalive.tacz_sewv.config;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeConfigSpec;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport.SbwClass;
import com.neoalive.tacz_sewv.invasion.InvasionSession;

/**
 * Runtime overlay for the Balancing → Easy Mode pack. Does not mutate Forge config values;
 * call sites read through these helpers so the pack only applies while {@link #active()} and
 * the relevant child toggle are on. The numbers are in {@link EasyTuning}.
 *
 * <p><b>Every lever asks who it is for.</b> {@link #nerfs} is a hostile AI actor (an RU/US unit
 * not made friendly by SEM's toggle, an ownerless PMC, or a hull such a unit drives); {@link #buffs}
 * is the player's side (a player, a PMC with an owner, or a hull either is aboard). Nothing in
 * Easy Mode may be faction-blind: v1's spread, rate of fire and hesitation were, and slowed the
 * player's own PMC crews exactly as much as the enemy's.
 *
 * <p>Easy Mode never applies while an invasion match runs anywhere — a PvP match must be even.
 */
public final class EasyMode {

    private EasyMode() {}

    /**
     * Read from hot, partly client-side paths (turret slew, engine thrust), so an unbaked config —
     * a client before the server config has synced — answers "off" instead of throwing.
     */
    public static boolean active() {
        try {
            return SewvConfig.EASY_MODE.get() && !InvasionSession.anyActive();
        } catch (IllegalStateException unbaked) {
            return false;
        }
    }

    private static boolean on(ForgeConfigSpec.BooleanValue child) {
        return active() && child.get();
    }

    // --- Audience ---

    /** A hostile AI actor: what Easy Mode slows down and softens. Ignores the master switch. */
    public static boolean isHostileActor(@Nullable Entity e) {
        if (e instanceof VehicleEntity hull) {
            return isHostileActor(hull.getFirstPassenger());
        }
        if (e instanceof PmcUnitEntity pmc) return pmc.getOwnerUUID() == null;
        if (e instanceof AbstractUnit unit) return !VehicleTargeting.isFactionFriendly(unit);
        return false;
    }

    /** The player's side: what Easy Mode protects. Ignores the master switch. */
    public static boolean isFriendlySide(@Nullable Entity e) {
        if (e instanceof Player) return true;
        if (e instanceof PmcUnitEntity pmc) return pmc.getOwnerUUID() != null;
        if (e instanceof VehicleEntity hull) {
            for (Entity passenger : hull.getPassengers()) {
                if (isFriendlySide(passenger)) return true;
            }
        }
        return false;
    }

    public static boolean nerfs(@Nullable Entity e) {
        return active() && isHostileActor(e);
    }

    public static boolean buffs(@Nullable Entity e) {
        return active() && isFriendlySide(e);
    }

    private static boolean nerfs(@Nullable Entity e, ForgeConfigSpec.BooleanValue child) {
        return on(child) && isHostileActor(e);
    }

    /** Turret, drive, repath and scan slowdown applies to this actor. */
    public static boolean slow(@Nullable Entity e) {
        return nerfs(e, SewvConfig.EASY_SLOW_AI);
    }

    /** Morale overlay (confidence malus, action bias, SEM retreat) applies to this actor. */
    public static boolean timid(@Nullable Entity e) {
        return nerfs(e, SewvConfig.EASY_TIMID_AI);
    }

    /** SEM rifle, grenade, health and bullet-damage softening applies to this unit. */
    public static boolean softInfantry(@Nullable Entity e) {
        return nerfs(e, SewvConfig.EASY_SOFT_INFANTRY);
    }

    /** Multiplier on incoming suppression: timid hostiles flinch harder, the player's side less. */
    public static float suppressionScale(@Nullable Entity receiver) {
        if (timid(receiver)) return EasyTuning.SUPPRESSION_HOSTILE_MULT;
        if (buffs(receiver)) return EasyTuning.SUPPRESSION_FRIENDLY_MULT;
        return 1.0f;
    }

    public static boolean softAim(@Nullable Entity e) {
        return nerfs(e, SewvConfig.EASY_SOFT_AI_AIM);
    }

    public static boolean softRof(@Nullable Entity e) {
        return nerfs(e, SewvConfig.EASY_SOFT_AI_ROF);
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

    public static boolean farCombatVehiclesAllowed() {
        return !on(SewvConfig.EASY_NO_FAR_COMBAT_VEHICLES);
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

    public static boolean fewerEvents() {
        return on(SewvConfig.EASY_FEWER_EVENTS);
    }

    /** SEM's global per-player event cooldown. */
    public static int eventCooldownTicks(int base) {
        if (!fewerEvents()) return base;
        return EasyTuning.scaleTicks(base, EasyTuning.EVENT_COOLDOWN_MULT);
    }

    /** Infantry headcount for an event squad. */
    public static int squadSize(int base) {
        if (!fewerEvents()) return base;
        return EasyTuning.squadSize(base);
    }

    // --- AI lethality ---

    /** Projectile spread added to a vehicle crew's shots, degrees. */
    public static double aiAimSpreadDegrees(@Nullable Entity shooter) {
        double base = SewvConfig.AI_AIM_SPREAD_DEG.get();
        if (softAim(shooter)) return EasyTuning.spread(base);
        return base;
    }

    public static double aiFireAssistConeDeg() {
        return SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get();
    }

    /** Configured AI shot cooldown, for paths with no weapon RPM to stretch (LV guns, bomb sticks). */
    public static int aiFireCooldownTicks(@Nullable Entity shooter) {
        int base = SewvConfig.AI_FIRE_COOLDOWN_TICKS.get();
        if (softRof(shooter)) {
            return Math.max(EasyTuning.FIRE_INTERVAL_FLOOR_TICKS, EasyTuning.scaleTicks(base, EasyTuning.COOLDOWN_MULT));
        }
        return base;
    }

    /**
     * Minimum ticks between a vehicle crew's shots. Normal play keeps the configured cooldown but
     * lets a weapon that cycles faster than it off ({@code min}); Easy Mode stretches the weapon's
     * own interval instead, so it binds on every gun — v1's 15-tick value lost to that min() on every
     * MG and autocannon and slowed nothing.
     *
     * @param naturalTicks the weapon's own RPM interval, or {@code <= 0} when unknown
     */
    public static int aiFireIntervalTicks(@Nullable Entity shooter, int naturalTicks) {
        int configured = SewvConfig.AI_FIRE_COOLDOWN_TICKS.get();
        if (softRof(shooter)) {
            return EasyTuning.fireInterval(naturalTicks > 0 ? naturalTicks : configured);
        }
        return naturalTicks > 0 ? Math.min(configured, naturalTicks) : configured;
    }

    /** {@link #aiFireIntervalTicks(Entity, int)} reading the hull's current weapon RPM. */
    public static int aiFireIntervalTicks(VehicleEntity hull, LivingEntity shooter) {
        int natural = -1;
        try {
            int rpm = hull.vehicleWeaponRpm(shooter);
            if (rpm > 0) natural = Math.max(1, (int) Math.ceil(1200.0 / rpm));
        } catch (Exception ignored) {
            // unreadable weapon data: fall back to the configured cooldown
        }
        return aiFireIntervalTicks(shooter, natural);
    }

    public static int aiFireHesitationTicks(@Nullable Entity shooter) {
        int v = SewvConfig.AI_FIRE_HESITATION_TICKS.get();
        if (softAim(shooter)) return Math.max(v, EasyTuning.HESITATION_FLOOR_TICKS);
        return v;
    }

    public static int mortarFireCooldownTicks(@Nullable Entity shooter) {
        int base = SewvConfig.MORTAR_FIRE_COOLDOWN_TICKS.get();
        if (softRof(shooter)) return EasyTuning.scaleTicks(base, EasyTuning.INDIRECT_COOLDOWN_MULT);
        return base;
    }

    public static int type63FireCooldownTicks(@Nullable Entity shooter) {
        int base = SewvConfig.TYPE63_FIRE_COOLDOWN_TICKS.get();
        if (softRof(shooter)) return EasyTuning.scaleTicks(base, EasyTuning.INDIRECT_COOLDOWN_MULT);
        return base;
    }

    /** Cooldown / reload for crew-served systems (TOW, missile system, artillery). */
    public static int crewServedTicks(@Nullable Entity shooter, int base) {
        if (softRof(shooter)) return EasyTuning.scaleTicks(base, EasyTuning.CREW_SERVED_RELOAD_MULT);
        return base;
    }

    /** Indirect-fire impact scatter radius. */
    public static double indirectDispersion(@Nullable Entity shooter, double base) {
        if (softRof(shooter)) return base * EasyTuning.INDIRECT_DISPERSION_MULT;
        return base;
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

    /** Aim time before a handheld AT/AA/small-arms shot. */
    public static int atAimTicks(@Nullable Entity shooter, int base) {
        if (nerfs(shooter, SewvConfig.EASY_SOFT_AT_DISMOUNTS)) return Math.max(base, EasyTuning.AT_AIM_TICKS);
        return base;
    }

    /** Extra pause after a handheld launcher's reload completes. */
    public static int atPostReloadTicks(@Nullable Entity shooter) {
        return nerfs(shooter, SewvConfig.EASY_SOFT_AT_DISMOUNTS) ? EasyTuning.AT_POST_RELOAD_TICKS : 0;
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

    // --- Mobility & cadence ---

    /** Every AI crew turns its turret slower in Easy; a hostile one (slow-AI child) slower still. */
    public static float turretTraverseMult(@Nullable Entity driver) {
        if (!active()) return 1.0f;
        return EasyTuning.AI_TURRET_TRAVERSE_MULT * (slow(driver) ? EasyTuning.TURRET_TRAVERSE_MULT : 1.0f);
    }

    public static float driveThrustMult(@Nullable Entity driver) {
        return slow(driver) ? EasyTuning.DRIVE_THRUST_MULT : 1.0f;
    }

    /** Repath cooldowns and path max age. */
    public static int pathCadence(@Nullable Entity driver, int base) {
        if (slow(driver)) return EasyTuning.scaleTicks(base, EasyTuning.PATH_CADENCE_MULT);
        return base;
    }

    public static int scanIntervalTicks(@Nullable Entity scanner, int base) {
        if (slow(scanner)) return EasyTuning.scaleTicks(base, EasyTuning.SCAN_INTERVAL_MULT);
        return base;
    }

    public static double scanRadius(@Nullable Entity scanner, double base) {
        if (slow(scanner)) return base * EasyTuning.SCAN_RADIUS_MULT;
        return base;
    }

    /** Wide scan and the contact-board LOS waiver are off for a slowed crew. */
    public static boolean wideScanAllowed(@Nullable Entity scanner) {
        return !slow(scanner);
    }

    // --- Morale ---

    public static double confidenceMalus(@Nullable Entity unit) {
        return timid(unit) ? EasyTuning.CONFIDENCE_MALUS : 0.0;
    }

    /** Additive utility bias; callers check {@link #timid} once per decision. */
    public static double actionBias(String action) {
        return EasyTuning.actionBias(action);
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

    // --- Player & PMC sustain ---

    public static boolean playerDamageScaleEnabled() {
        return on(SewvConfig.EASY_PLAYER_DAMAGE_SCALE_ENABLED);
    }

    public static float playerDamageScale() {
        return SewvConfig.EASY_PLAYER_DAMAGE_SCALE.get().floatValue();
    }

    public static boolean playerPrioritySoft() {
        return on(SewvConfig.EASY_PLAYER_PRIORITY_SOFT);
    }

    public static boolean playerSustain() {
        return on(SewvConfig.EASY_PLAYER_SUSTAIN);
    }

    public static boolean protectVehicles() {
        return on(SewvConfig.EASY_PROTECT_VEHICLES);
    }

    public static boolean buffPmc() {
        return on(SewvConfig.EASY_BUFF_PMC);
    }

    public static boolean highlightThreats() {
        return on(SewvConfig.EASY_HIGHLIGHT_THREATS);
    }

    /** Respawn grace before AI may proactively lock the player (game ticks). */
    public static final int PLAYER_RESPAWN_GRACE_TICKS = EasyTuning.RESPAWN_GRACE_TICKS;
}
