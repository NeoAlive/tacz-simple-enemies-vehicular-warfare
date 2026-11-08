package com.neoalive.tacz_sewv.crew;

import java.util.EnumMap;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.bridge.IHullRadio;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;
import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.init.ModSounds.SoundPool;

/**
 * One radio voice per hull for <b>mounted</b> SEM crews. On-foot infantry keep SEM's own lines;
 * this class must not speak for them. The driver ({@code getFirstPassenger}) speaks for the whole
 * crew unless a caller names another speaker. Gated by {@link SewvConfig#VEHICLE_VOICELINES_ENABLED}
 * on every entry point.
 *
 * <p>Lines go through the hull's {@link RadioQueue}: one clip at a time, two waiting, never two of a
 * {@link Group} at once, ordered and spaced by {@link Tier}. A per-line cooldown still applies. A line's clips are the pool
 * {@code <line.pool>_<faction>[_panicked]}; a line with no clips for a faction falls back along
 * {@link Line#fallback}, and is silent when the chain runs out.
 */
public final class CrewRadio {

    private static final float VOICELINE_VOLUME = 1.8F;
    /** Hull health fraction below which low-health / panicked shoot pools apply (&lt; 60%). */
    public static final float LOW_HEALTH_FRACTION = 0.6F;
    /** Hull health fraction below which the critical (burning) line applies. */
    public static final float CRITICAL_FRACTION = 0.25F;
    /** A hit smaller than this share of max health is plinking, not "we've been hit". */
    private static final float HIT_FLOOR_FRACTION = 0.02F;
    /** A kill this recent lets the crew call the area clear once the fight ends. */
    private static final int AREA_CLEAR_WINDOW = 1200;
    private static final double ALLY_LOST_RANGE = 64.0;
    private static final int GUN_READY_MIN_TICKS = 40;
    private static final float NOPEN_FRACTION = 0.01F;

    public enum Faction { RU, US, PMC }

    public enum Line {
        ORDER_DISPATCH("order_dispatch", 60),
        /** RU/US scored plan change with no plan-specific clips. Heavier safety net; airtime also applies. */
        ORDER_DISPATCH_PLAN("order_dispatch", 240, "plan", Tier.SOFT, null),
        PLAN_ADVANCE("plan_advance", 240, "plan", Tier.SOFT, ORDER_DISPATCH_PLAN),
        PLAN_HOLD("plan_hold", 240, "plan", Tier.SOFT, ORDER_DISPATCH_PLAN),
        PLAN_RETREAT("plan_retreat", 240, "plan", Tier.SOFT, ORDER_DISPATCH_PLAN),
        PLAN_FLANK("plan_flank", 240, "plan", Tier.SOFT, ORDER_DISPATCH_PLAN),
        ENGINE_START("engine_start", 600, null, Tier.SOFT, null),

        TARGET_GENERIC("target_generic", 90),
        TARGET_HELICOPTER("target_helicopter", 90, null, Tier.HARD, TARGET_GENERIC),
        TARGET_PLANE("target_plane", 90, null, Tier.HARD, TARGET_GENERIC),
        TARGET_SHIP("target_ship", 90, null, Tier.HARD, TARGET_GENERIC),
        TARGET_TANK("target_tank", 90, null, Tier.HARD, TARGET_GENERIC),
        TRAVERSE_LEFT("traverse_left", 120, "traverse", Tier.HARD, null),
        TRAVERSE_RIGHT("traverse_right", 120, "traverse", Tier.HARD, null),

        UNIT_DEPLOY_DRONE("unit_deploy_drone", 80),
        UNIT_DIG("unit_dig", 80),
        UNIT_HEAL("unit_heal", 80),
        UNIT_REPAIR("unit_repair", 80),

        VEHICLE_BAIL("vehicle_bail", 40, null, Tier.URGENT, null),
        VEHICLE_IDLE("vehicle_idle", 600, null, Tier.SOFT, null),
        VEHICLE_LOW_HEALTH("vehicle_low_health", 200),
        VEHICLE_CRITICAL("vehicle_critical", 400, null, Tier.HARD, VEHICLE_LOW_HEALTH),
        VEHICLE_HIT("vehicle_hit", 100, "hit", Tier.URGENT, null),
        DAMAGE_TRACK("damage_track", 200, "damage", Tier.HARD, null),
        DAMAGE_ENGINE("damage_engine", 200, "damage", Tier.HARD, null),
        DAMAGE_TURRET("damage_turret", 200, "damage", Tier.HARD, null),
        DAMAGE_REPAIRED("damage_repaired", 200),
        CREW_DOWN("crew_down", 100, "crew", Tier.URGENT, null),
        CREW_COMMAND("crew_command", 100, "crew", Tier.URGENT, CREW_DOWN),

        VEHICLE_MG_SHOOT("vehicle_mg_shoot", 140, null, Tier.SOFT, null),
        VEHICLE_CANNON_SHOOT("vehicle_cannon_shoot", 140, null, Tier.SOFT, null),
        LOAD_AP("load_ap", 160, "load", Tier.SOFT, null),
        LOAD_HE("load_he", 160, "load", Tier.SOFT, null),
        LOAD_HEAT("load_heat", 160, "load", Tier.SOFT, LOAD_AP),
        LOAD_MISSILE("load_missile", 160, "load", Tier.SOFT, null),
        GUN_READY("gun_ready", 160, null, Tier.SOFT, null),
        POP_SMOKE("pop_smoke", 120),

        TARGET_HIT("target_hit", 100, null, Tier.SOFT, null),
        /** Our round hit a hull and did next to nothing. Shares the hit cooldown. */
        TARGET_NOPEN("target_nopen", 100, "TARGET_HIT", Tier.SOFT, null),
        TARGET_DESTROYED("target_destroyed", 80, null, Tier.URGENT, null),
        ALLY_LOST("ally_lost", 400),
        AREA_CLEAR("area_clear", 600),

        /** Calling for CAS. Shares the {@code support} category cooldown. */
        VEHICLE_SUPPORT_AIR("vehicle_support_air", 160, "support", Tier.HARD, null),
        /** Calling for mortar or TOW. Shares the {@code support} category cooldown. */
        VEHICLE_SUPPORT_MORTAR("vehicle_support_mortar", 160, "support", Tier.HARD, null),
        /** Hull first indexed as a hostile contact. */
        VEHICLE_TARGETED("vehicle_targeted", 140, "targeted", Tier.HARD, null),

        /** Radio answer to an accepted mortar or TOW mission. Played on the caller, not a hull. */
        SUPPORT_ACK_MORTAR("support_ack_mortar", 0),
        /** Radio answer to an accepted CAS mission. Played on the caller, not a hull. */
        SUPPORT_ACK_AIR("support_ack_air", 0),
        OBJECTIVE_TAKEN("objective_taken", 200),
        OBJECTIVE_LOST("objective_lost", 200),

        /** Cockpit voice: guided missile inbound. Aircraft only. */
        WARN_MISSILE("warn_missile", 60, null, Tier.URGENT, null),
        /** Cockpit voice: locked or being locked. Aircraft only. */
        WARN_LOCK("warn_lock", 100),
        /** Cockpit voice: too low and sinking. Aircraft only. */
        WARN_ALTITUDE("warn_altitude", 200),
        /** Cockpit voice: gear on the landing approach. Aircraft only. */
        WARN_GEAR("warn_gear", 400);

        final String pool;
        final int cooldown;
        /** Shared NBT cooldown bucket; null = one key per line name. */
        final String category;
        final Tier tier;
        final Line fallback;

        Line(String pool, int cooldown) {
            this(pool, cooldown, null, Tier.HARD, null);
        }

        Line(String pool, int cooldown, String category, Tier tier, Line fallback) {
            this.pool = pool;
            this.cooldown = cooldown;
            this.category = category;
            this.tier = tier;
            this.fallback = fallback;
        }

        String cooldownKey() {
            return category != null ? category : name();
        }

        /** Queue type: two lines of one group never share the playing and waiting slots. */
        private int group;

        static {
            for (Line l : values()) l.group = groupOf(l).ordinal();
        }
    }

    /** Line types for the queue's diversity rule. Lines not listed are a group of their own. */
    enum Group { TARGET, PLAN, RESULT, DAMAGE, HEALTH, HIT, CREW, SHOOT, LOAD, SUPPORT, UNIT, ALLY_LOST, SMOKE, BAIL, IDLE,
        OBJECTIVE, WARNING }

    static Group groupOf(Line l) {
        return switch (l) {
            case TARGET_GENERIC, TARGET_HELICOPTER, TARGET_PLANE, TARGET_SHIP, TARGET_TANK, TRAVERSE_LEFT,
                    TRAVERSE_RIGHT, VEHICLE_TARGETED -> Group.TARGET;
            case ORDER_DISPATCH, ORDER_DISPATCH_PLAN, PLAN_ADVANCE, PLAN_HOLD, PLAN_RETREAT, PLAN_FLANK,
                    ENGINE_START -> Group.PLAN;
            case TARGET_HIT, TARGET_NOPEN, TARGET_DESTROYED, AREA_CLEAR -> Group.RESULT;
            case DAMAGE_TRACK, DAMAGE_ENGINE, DAMAGE_TURRET, DAMAGE_REPAIRED -> Group.DAMAGE;
            case VEHICLE_LOW_HEALTH, VEHICLE_CRITICAL -> Group.HEALTH;
            case VEHICLE_HIT -> Group.HIT;
            case CREW_DOWN, CREW_COMMAND -> Group.CREW;
            case VEHICLE_MG_SHOOT, VEHICLE_CANNON_SHOOT -> Group.SHOOT;
            case LOAD_AP, LOAD_HE, LOAD_HEAT, LOAD_MISSILE, GUN_READY -> Group.LOAD;
            case VEHICLE_SUPPORT_AIR, VEHICLE_SUPPORT_MORTAR, SUPPORT_ACK_MORTAR, SUPPORT_ACK_AIR -> Group.SUPPORT;
            case OBJECTIVE_TAKEN, OBJECTIVE_LOST -> Group.OBJECTIVE;
            case WARN_MISSILE, WARN_LOCK, WARN_ALTITUDE, WARN_GEAR -> Group.WARNING;
            case UNIT_DEPLOY_DRONE, UNIT_DIG, UNIT_HEAL, UNIT_REPAIR -> Group.UNIT;
            case ALLY_LOST -> Group.ALLY_LOST;
            case POP_SMOKE -> Group.SMOKE;
            case VEHICLE_BAIL -> Group.BAIL;
            case VEHICLE_IDLE -> Group.IDLE;
        };
    }

    /**
     * How a line waits in the hull's {@link RadioQueue}: URGENT goes to the front with the shortest gap,
     * HARD next, SOFT last with the longest gap and the local airtime gate on top.
     */
    enum Tier {
        HARD(RadioQueue.HARD), SOFT(RadioQueue.SOFT), URGENT(RadioQueue.URGENT);

        final int rank;

        Tier(int rank) {
            this.rank = rank;
        }
    }

    /**
     * What kind of hull is speaking. A domain can swap a line's clips ({@code <pool>_<domain>_<faction>},
     * falling back to the plain pool) or block the line outright: aircraft have no tracks or loaders,
     * an emplacement has no engine to start, and only aircraft have a cockpit voice.
     */
    enum Domain {
        GROUND(null), AIR("air"), SEA("sea"), FIXED("fixed");

        final String key;

        Domain(String key) {
            this.key = key;
        }
    }

    private static final Domain[] DOMAINS = Domain.values();

    static int blockedMask(Line l) {
        int air = 1 << Domain.AIR.ordinal();
        int sea = 1 << Domain.SEA.ordinal();
        int fixed = 1 << Domain.FIXED.ordinal();
        int ground = 1 << Domain.GROUND.ordinal();
        return switch (l) {
            case DAMAGE_TRACK -> air | sea | fixed;
            case DAMAGE_TURRET, TRAVERSE_LEFT, TRAVERSE_RIGHT, LOAD_AP, LOAD_HE, LOAD_HEAT -> air;
            case ENGINE_START -> fixed;
            case PLAN_ADVANCE, PLAN_HOLD, PLAN_RETREAT, PLAN_FLANK -> air | fixed;
            case WARN_MISSILE, WARN_LOCK, WARN_ALTITUDE, WARN_GEAR -> ground | sea | fixed;
            default -> 0;
        };
    }

    private static final int[] BLOCKED = new int[Line.values().length];

    static {
        for (Line l : Line.values()) BLOCKED[l.ordinal()] = blockedMask(l);
    }

    static boolean blocked(Line l, Domain d) {
        return (BLOCKED[l.ordinal()] & (1 << d.ordinal())) != 0;
    }

    /** Resolved once per hull and kept on its radio queue. */
    static Domain domainOf(VehicleEntity hull) {
        RadioQueue q = ((IHullRadio) hull).sewv$radio();
        if (q.domain < 0) q.domain = resolveDomain(hull).ordinal();
        return DOMAINS[q.domain];
    }

    private static Domain resolveDomain(VehicleEntity hull) {
        com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType t;
        try {
            t = com.neoalive.tacz_sewv.compat.NpcVehicleOverrides.applyEngineHint(
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(hull.getType()).toString(),
                    com.neoalive.tacz_sewv.entity.ai.core.HullFacts.engineType(hull));
        } catch (Throwable e) {
            return Domain.GROUND;
        }
        if (t == null) return Domain.GROUND;
        return switch (t) {
            case HELICOPTER, AIRCRAFT -> Domain.AIR;
            case SHIP -> Domain.SEA;
            case FIXED -> Domain.FIXED;
            default -> Domain.GROUND;
        };
    }

    private static final Line[] LINES = Line.values();

    private static final String TYPE_KEY = "tacz_sewv:radio_";
    static final String LOW_HEALTH_SPOKEN_KEY = "tacz_sewv:radio_low_hp";
    private static final String CRITICAL_SPOKEN_KEY = "tacz_sewv:radio_crit";
    private static final String DAMAGE_BITS_KEY = "tacz_sewv:radio_dmg";
    private static final String KILL_AT_KEY = "tacz_sewv:radio_kill_at";
    private static final String DESTROYED_KEY = "tacz_sewv:radio_destroyed";
    private static final String ENGINE_SPOKEN_KEY = "tacz_sewv:radio_engine";

    private static final int BIT_TRACK = 1;
    private static final int BIT_ENGINE = 2;
    private static final int BIT_TURRET = 4;

    /** Recent soft-line speak positions for the local airtime gate (per dimension). */
    private static final int AIRTIME_HISTORY = 32;
    private static final double AIRTIME_RANGE_SQ = 48.0 * 48.0;
    private static final int AIRTIME_WINDOW_TICKS = 50;
    private static final java.util.WeakHashMap<ServerLevel, AirtimeRing> AIRTIME = new java.util.WeakHashMap<>();

    /** Resolved pools per faction: [domain][line] then [0 = normal, 1 = panicked]. Built on first use. */
    private static volatile EnumMap<Faction, SoundPool[][][]> pools;

    private CrewRadio() {}

    /** Master switch. Every public entry point must honour this. */
    public static boolean enabled() {
        return SewvConfig.VEHICLE_VOICELINES_ENABLED.get();
    }

    // ---------------------------------------------------------------- generic entry points

    public static void play(VehicleEntity hull, Line line) {
        if (!enabled() || hull.level().isClientSide) return;
        AbstractUnit crew = firstCrew(hull, null);
        if (crew != null) speak(hull, crew, line);
    }

    public static boolean speak(VehicleEntity hull, AbstractUnit speaker, Line line) {
        if (!enabled() || hull.level().isClientSide) return false;
        // Speaker must still be riding this hull; a bail mid-dismount must not orphan a line on foot.
        if (speaker.getVehicle() != hull) return false;
        return playLine(hull, speaker, line, false);
    }

    /**
     * Support-role callout (heal / repair / dig / drone). Only when the unit is mounted; on foot
     * SEM owns the voice. Routes through the hull radio channel.
     */
    public static void speakUnit(AbstractUnit speaker, Line line) {
        if (!enabled() || speaker.level().isClientSide) return;
        if (!(speaker.getVehicle() instanceof VehicleEntity hull) || hull.isWreck()) return;
        speak(hull, speaker, line);
    }

    // ---------------------------------------------------------------- weapons

    /**
     * MG / cannon fire callout. Tier B. Panicked pools while hull health &lt; {@link #LOW_HEALTH_FRACTION}.
     *
     * @param weaponRole {@code VehicleWeapons.WEAPON_CANNON} (0) or {@code WEAPON_MG} (1)
     * @return true if a clip actually played
     */
    public static boolean playShoot(VehicleEntity hull, int weaponRole) {
        if (!enabled() || hull.level().isClientSide) return false;
        // Literals avoid a CrewRadio and VehicleWeapons import cycle; keep in sync with WEAPON_*.
        if (weaponRole != 0 && weaponRole != 1) return false;
        Line line = weaponRole == 1 ? Line.VEHICLE_MG_SHOOT : Line.VEHICLE_CANNON_SHOOT;
        AbstractUnit crew = firstCrew(hull, null);
        return crew != null && playLine(hull, crew, line, panicked(hull));
    }

    /**
     * Loader call on a real ammunition change: {@code switchedAmmoId} is the SBW ammo id the
     * weapon selection just loaded. Classified by name, since vehicle weapon slots carry no
     * reliable type metadata.
     */
    public static void onAmmoSwitch(VehicleEntity hull, String switchedAmmoId) {
        if (!enabled() || hull.level().isClientSide || switchedAmmoId == null) return;
        Line line = loadLineFor(switchedAmmoId);
        if (line != null) play(hull, line);
    }

    /** Loader call when a seat swaps onto its missile slot. */
    public static void onMissileSelected(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide) return;
        play(hull, Line.LOAD_MISSILE);
    }

    /** Matches the suffix doctrine VehicleWeapons selects ammo by ({@code _ap}, {@code _he}, {@code _gs}). */
    static Line loadLineFor(String ammoId) {
        String id = ammoId.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("heat")) return Line.LOAD_HEAT;
        if (id.endsWith("_ap") || id.contains("apfsds") || id.contains("sabot")) return Line.LOAD_AP;
        if (id.endsWith("_he") || id.endsWith("_gs") || id.contains("hesh")) return Line.LOAD_HE;
        return null;
    }

    /** Gun up after a reload. Called once per reload from the canShoot edge. */
    public static void gunReady(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide) return;
        play(hull, Line.GUN_READY);
    }

    public static int gunReadyMinTicks() {
        return GUN_READY_MIN_TICKS;
    }

    /** Smoke volley actually leaving the launchers. */
    public static void onSmoke(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide) return;
        play(hull, Line.POP_SMOKE);
    }

    // ---------------------------------------------------------------- targets

    /**
     * New-target callout. When the turret has to swing more than 60 degrees to bring the gun on,
     * half the time the commander calls the traverse instead of naming the target.
     */
    public static void onNewTarget(VehicleEntity hull, LivingEntity target, Line targetLine) {
        if (!enabled() || hull.level().isClientSide) return;
        AbstractUnit crew = firstCrew(hull, null);
        if (crew == null) return;
        if (hull.hasTurret() && crew.getRandom().nextBoolean()) {
            Line traverse = traverseLine(hull, target);
            if (traverse != null && playLine(hull, crew, traverse, false)) return;
        }
        playLine(hull, crew, targetLine, false);
    }

    private static Line traverseLine(VehicleEntity hull, LivingEntity target) {
        Vec3 turret = hull.getTurretVector(1.0F);
        double tx = target.getX() - hull.getX();
        double tz = target.getZ() - hull.getZ();
        if (turret.horizontalDistanceSqr() < 1.0E-6 || tx * tx + tz * tz < 1.0E-6) return null;
        float turretYaw = (float) Math.toDegrees(Math.atan2(-turret.x, turret.z));
        float targetYaw = (float) Math.toDegrees(Math.atan2(-tx, tz));
        float delta = Mth.wrapDegrees(targetYaw - turretYaw);
        if (Math.abs(delta) < 60.0F) return null;
        // Minecraft yaw grows clockwise seen from above, so a positive delta is a swing to the right.
        return delta > 0 ? Line.TRAVERSE_RIGHT : Line.TRAVERSE_LEFT;
    }

    /**
     * Rising-edge "we're being painted": first time this living entity is written onto a hostile
     * contact board while riding a hull.
     */
    public static void maybeBeingTargeted(LivingEntity target) {
        if (!enabled() || target == null || target.level().isClientSide) return;
        if (!(target.getVehicle() instanceof VehicleEntity hull) || hull.isWreck()) return;
        play(hull, Line.VEHICLE_TARGETED);
    }

    /** Non-lethal hit landed by this hull's crew. */
    public static void onHitLanded(VehicleEntity hull) {
        onHitLanded(hull, true);
    }

    /** @param penetrated false when the round hit a hull and did next to nothing */
    public static void onHitLanded(VehicleEntity hull, boolean penetrated) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        play(hull, penetrated ? Line.TARGET_HIT : Line.TARGET_NOPEN);
    }

    /** A hit on a hull below this share of its max health reads as a bounce. */
    public static boolean penetrated(VehicleEntity target, float amount) {
        return amount >= NOPEN_FRACTION * target.getMaxHealth();
    }

    /** Kill credited to this hull's crew. Arms the area-clear call. */
    public static void onKill(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        hull.getPersistentData().putLong(KILL_AT_KEY, hull.level().getGameTime());
        play(hull, Line.TARGET_DESTROYED);
    }

    /** Fight over: clear call if this crew scored a kill recently. Consumes the kill stamp. */
    public static void maybeAreaClear(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide) return;
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(KILL_AT_KEY)) return;
        long killAt = data.getLong(KILL_AT_KEY);
        data.remove(KILL_AT_KEY);
        if (hull.level().getGameTime() - killAt > AREA_CLEAR_WINDOW) return;
        play(hull, Line.AREA_CLEAR);
    }

    public static boolean recentKill(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        return data.contains(KILL_AT_KEY)
                && hull.level().getGameTime() - data.getLong(KILL_AT_KEY) <= AREA_CLEAR_WINDOW;
    }

    /**
     * A hull is going down. Credits the kill to the crew that did it and tells the nearest crew of
     * the same side that they lost one. Once per hull.
     */
    public static void onHullDestroyed(VehicleEntity hull, Entity attacker) {
        if (!enabled() || hull.level().isClientSide) return;
        CompoundTag data = hull.getPersistentData();
        if (data.getBoolean(DESTROYED_KEY)) return;
        data.putBoolean(DESTROYED_KEY, true);
        VehicleEntity killer = crewedHullOf(attacker);
        if (killer != null && killer != hull) onKill(killer);
        Faction side = crewFaction(hull);
        if (side != null) {
            VehicleEntity witness = nearestCrewedHull(hull, side, killer);
            if (witness != null) play(witness, Line.ALLY_LOST);
        }
    }

    /** The hull an attacker is crewing, or the hull itself when the attacker is one. */
    public static VehicleEntity crewedHullOf(Entity attacker) {
        if (attacker instanceof VehicleEntity v) return firstCrew(v, null) != null ? v : null;
        if (attacker instanceof AbstractUnit u && u.getVehicle() instanceof VehicleEntity v) return v;
        return null;
    }

    private static VehicleEntity nearestCrewedHull(VehicleEntity lost, Faction side, VehicleEntity exclude) {
        AABB box = lost.getBoundingBox().inflate(ALLY_LOST_RANGE);
        List<VehicleEntity> near = lost.level().getEntitiesOfClass(VehicleEntity.class, box,
                v -> v != lost && v != exclude && !v.isWreck() && crewFaction(v) == side);
        VehicleEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (VehicleEntity v : near) {
            double d = v.distanceToSqr(lost);
            if (d < bestSq) {
                bestSq = d;
                best = v;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- damage and crew

    /**
     * After SBW applied a hit: "we've been hit", component damage edges, low health and critical.
     * One call per hurt; the component bits are compared against the last ones this hull spoke.
     */
    public static void onHullHurt(VehicleEntity hull, float amount) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        AbstractUnit crew = firstCrew(hull, null);
        if (crew == null) return;
        if (componentEdges(hull, crew)) return;
        if (maybeCritical(hull, crew)) return;
        if (maybeLowHealth(hull, crew)) return;
        if (amount >= HIT_FLOOR_FRACTION * hull.getMaxHealth()) playLine(hull, crew, Line.VEHICLE_HIT, false);
    }

    /** Component state poll for repairs, which never pass through onHurt. */
    public static void pollRepairs(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        CompoundTag data = hull.getPersistentData();
        int last = data.getInt(DAMAGE_BITS_KEY);
        if (last == 0) return;
        int now = damageBits(hull);
        if ((last & ~now) == 0) return;
        data.putInt(DAMAGE_BITS_KEY, now);
        AbstractUnit crew = firstCrew(hull, null);
        if (crew != null) playLine(hull, crew, Line.DAMAGE_REPAIRED, false);
    }

    private static boolean componentEdges(VehicleEntity hull, AbstractUnit crew) {
        CompoundTag data = hull.getPersistentData();
        int last = data.getInt(DAMAGE_BITS_KEY);
        int now = damageBits(hull);
        if (now == last) return false;
        data.putInt(DAMAGE_BITS_KEY, now);
        int fresh = now & ~last;
        if (fresh == 0) return false;
        Line line = (fresh & BIT_TRACK) != 0 ? Line.DAMAGE_TRACK
                : (fresh & BIT_ENGINE) != 0 ? Line.DAMAGE_ENGINE : Line.DAMAGE_TURRET;
        return playLine(hull, crew, line, false);
    }

    private static int damageBits(VehicleEntity hull) {
        int bits = 0;
        if (hull.getLeftWheelDamaged() || hull.getRightWheelDamaged()) bits |= BIT_TRACK;
        if (hull.getMainEngineDamaged() || hull.getSubEngineDamaged()) bits |= BIT_ENGINE;
        if (hull.getTurretDamaged()) bits |= BIT_TURRET;
        return bits;
    }

    private static boolean maybeCritical(VehicleEntity hull, AbstractUnit crew) {
        CompoundTag data = hull.getPersistentData();
        if (hull.getHealth() >= CRITICAL_FRACTION * hull.getMaxHealth()) {
            data.putBoolean(CRITICAL_SPOKEN_KEY, false);
            return false;
        }
        if (data.getBoolean(CRITICAL_SPOKEN_KEY)) return false;
        if (!playLine(hull, crew, Line.VEHICLE_CRITICAL, false)) return false;
        data.putBoolean(CRITICAL_SPOKEN_KEY, true);
        data.putBoolean(LOW_HEALTH_SPOKEN_KEY, true);
        return true;
    }

    /**
     * Rising-edge low-health line. Clears the spoken flag when health recovers above the band so a
     * later dip can speak again. Retries while low if overlap blocked the first attempt.
     */
    private static boolean maybeLowHealth(VehicleEntity hull, AbstractUnit crew) {
        CompoundTag data = hull.getPersistentData();
        if (!panicked(hull)) {
            data.putBoolean(LOW_HEALTH_SPOKEN_KEY, false);
            return false;
        }
        if (data.getBoolean(LOW_HEALTH_SPOKEN_KEY)) return false;
        if (pool(factionOf(crew), domainOf(hull), Line.VEHICLE_LOW_HEALTH, false) == null) {
            data.putBoolean(LOW_HEALTH_SPOKEN_KEY, true);
            return false;
        }
        if (!playLine(hull, crew, Line.VEHICLE_LOW_HEALTH, false)) return false;
        data.putBoolean(LOW_HEALTH_SPOKEN_KEY, true);
        return true;
    }

    /**
     * A crewman died in his seat. Another crewman calls it; when the dead man was the one in
     * command (first passenger), the call is the hand-over instead.
     */
    public static void onCrewDeath(VehicleEntity hull, AbstractUnit dead) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        AbstractUnit speaker = firstCrew(hull, dead);
        if (speaker == null) return;
        Line line = hull.getFirstPassenger() == dead ? Line.CREW_COMMAND : Line.CREW_DOWN;
        playLine(hull, speaker, line, false);
    }

    /** Engine start on a crew taking the hull, once per mount. */
    public static void onCrewTakesHull(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide) return;
        CompoundTag data = hull.getPersistentData();
        if (data.getBoolean(ENGINE_SPOKEN_KEY)) return;
        AbstractUnit crew = firstCrew(hull, null);
        if (crew == null) return;
        if (playLine(hull, crew, Line.ENGINE_START, false)) data.putBoolean(ENGINE_SPOKEN_KEY, true);
    }

    /** Hull left empty: the next crew to take it starts the engine again. */
    public static void onHullEmptied(VehicleEntity hull) {
        hull.getPersistentData().remove(ENGINE_SPOKEN_KEY);
    }

    // ---------------------------------------------------------------- plans and support

    /** RU/US scored plan change, already debounced by the caller. Null-safe for plans with no ack. */
    public static void onPlan(VehicleEntity hull, Action plan) {
        if (!enabled() || hull.level().isClientSide || plan == null) return;
        Line line = planLine(plan);
        if (line != null) play(hull, line);
    }

    static Line planLine(Action plan) {
        return switch (plan) {
            case ADVANCE -> Line.PLAN_ADVANCE;
            case HOLD -> Line.PLAN_HOLD;
            case RETREAT -> Line.PLAN_RETREAT;
            case FLANK_LEFT, FLANK_RIGHT -> Line.PLAN_FLANK;
            // Smoke and the radio calls have lines of their own, spoken when they actually happen.
            case DEPLOY_SMOKE, CALL_MORTARS, CALL_TOW, CALL_CAS, DELEGATE_TARGET -> null;
            default -> Line.ORDER_DISPATCH_PLAN;
        };
    }

    public static boolean panicked(VehicleEntity hull) {
        return hull.getHealth() < LOW_HEALTH_FRACTION * hull.getMaxHealth();
    }

    /**
     * Support callout. {@code CAS} is air; {@code MORTAR}/{@code TOW} the mortar pool. Silent for a
     * faction with no clips. Shares the {@code support} category cooldown.
     */
    public static void playSupport(VehicleEntity hull, FireMissionSupport.Kind kind) {
        if (!enabled() || hull.level().isClientSide || kind == null) return;
        Line line = switch (kind) {
            case CAS -> Line.VEHICLE_SUPPORT_AIR;
            case MORTAR, TOW -> Line.VEHICLE_SUPPORT_MORTAR;
            default -> null;
        };
        if (line != null) play(hull, line);
    }

    // ---------------------------------------------------------------- cockpit and objectives

    /** A guided missile is flying at this hull. Cockpit voice, aircraft only. */
    public static void onMissileInbound(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        play(hull, Line.WARN_MISSILE);
    }

    /** Someone is locking or has locked this hull. Cockpit voice, aircraft only. */
    public static void onLocked(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        play(hull, Line.WARN_LOCK);
    }

    /** Too low and sinking. Rising edge and cooldown are the caller's and the line's. */
    public static void onLowAltitude(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        play(hull, Line.WARN_ALTITUDE);
    }

    /** A plane starting its landing approach. */
    public static void onLandingApproach(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide || hull.isWreck()) return;
        play(hull, Line.WARN_GEAR);
    }

    private static final double OBJECTIVE_RANGE = 96.0;

    /**
     * An invasion point changed hands: the nearest crewed hull of the new holder calls it taken, the
     * nearest of the old holder calls it lost. Team is read off the first crewman's invasion tag.
     */
    public static void onObjective(ServerLevel level, net.minecraft.core.BlockPos pos, String previous, String taken) {
        if (!enabled()) return;
        boolean hasPrevious = previous != null && !previous.isEmpty();
        AABB box = new AABB(pos).inflate(OBJECTIVE_RANGE);
        VehicleEntity winner = null;
        VehicleEntity loser = null;
        double winSq = Double.MAX_VALUE;
        double loseSq = Double.MAX_VALUE;
        for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, box, v -> !v.isWreck())) {
            AbstractUnit crew = firstCrew(hull, null);
            if (crew == null) continue;
            String team = crew.getPersistentData().getString(com.neoalive.tacz_sewv.invasion.InvasionTags.TEAM);
            if (team.isEmpty()) continue;
            double d = hull.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (team.equals(taken) && d < winSq) {
                winSq = d;
                winner = hull;
            } else if (hasPrevious && team.equals(previous) && d < loseSq) {
                loseSq = d;
                loser = hull;
            }
        }
        if (winner != null) play(winner, Line.OBJECTIVE_TAKEN);
        if (loser != null) play(loser, Line.OBJECTIVE_LOST);
    }

    /** @return true if the clip played */
    public static boolean playIdle(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide) return false;
        AbstractUnit crew = firstCrew(hull, null);
        return crew != null && playLine(hull, crew, Line.VEHICLE_IDLE, false);
    }

    // ---------------------------------------------------------------- core

    private static AbstractUnit firstCrew(VehicleEntity hull, Entity except) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger != except && passenger instanceof AbstractUnit crew && crew.isAlive()) return crew;
        }
        return null;
    }

    private static Faction crewFaction(VehicleEntity hull) {
        AbstractUnit crew = firstCrew(hull, null);
        return crew == null ? null : factionOf(crew);
    }

    static Faction factionOf(AbstractUnit unit) {
        if (unit instanceof RUunitEntity) return Faction.RU;
        if (unit instanceof USunitEntity) return Faction.US;
        return Faction.PMC;
    }

    private static boolean playLine(VehicleEntity hull, AbstractUnit speaker, Line line, boolean panicked) {
        Domain domain = domainOf(hull);
        if (blocked(line, domain)) return false;
        Faction faction = factionOf(speaker);
        Line resolved = line;
        SoundPool pool = pool(faction, domain, resolved, panicked);
        while (pool == null && resolved.fallback != null) {
            resolved = resolved.fallback;
            if (blocked(resolved, domain)) return false;
            pool = pool(faction, domain, resolved, panicked);
        }
        if (pool == null) return false;
        return playPool(hull, line, pool, hull.getPersistentData());
    }

    private static SoundPool pool(Faction faction, Domain domain, Line line, boolean panicked) {
        SoundPool[] pair = table().get(faction)[domain.ordinal()][line.ordinal()];
        if (panicked && pair[1] != null) return pair[1];
        return pair[0];
    }

    /** The clips for a line with no hull behind it (radio answers), or null when the faction has none. */
    public static SoundPool poolFor(Faction faction, Line line) {
        return pool(faction, Domain.GROUND, line, false);
    }

    private static EnumMap<Faction, SoundPool[][][]> table() {
        EnumMap<Faction, SoundPool[][][]> t = pools;
        if (t != null) return t;
        t = new EnumMap<>(Faction.class);
        for (Faction f : Faction.values()) {
            String suffix = "_" + f.name().toLowerCase(java.util.Locale.ROOT);
            SoundPool[][][] rows = new SoundPool[DOMAINS.length][LINES.length][2];
            for (Domain d : DOMAINS) {
                for (Line l : LINES) {
                    SoundPool[] pair = rows[d.ordinal()][l.ordinal()];
                    if (d.key != null) {
                        pair[0] = ModSounds.crew(l.pool + "_" + d.key + suffix);
                        pair[1] = ModSounds.crew(l.pool + "_" + d.key + suffix + "_panicked");
                    }
                    if (pair[0] == null) pair[0] = ModSounds.crew(l.pool + suffix);
                    if (pair[1] == null) pair[1] = ModSounds.crew(l.pool + suffix + "_panicked");
                }
            }
            t.put(f, rows);
        }
        pools = t;
        return t;
    }

    /** The cooldown key used is always the requested line's, so a fallback never resets another line's timer. */
    private static boolean playPool(VehicleEntity hull, Line line, SoundPool pool, CompoundTag data) {
        long now = hull.level().getGameTime();
        if (now < data.getLong(TYPE_KEY + line.cooldownKey())) return false;
        RadioQueue q = ((IHullRadio) hull).sewv$radio();
        return q.offer(now, line.ordinal(), line.group, line.tier.rank, pool, hull, SINK);
    }

    /** Plays the hull's next waiting line when its turn comes. Called from the hull tick while one waits. */
    public static void drain(VehicleEntity hull, RadioQueue q) {
        if (hull.level().isClientSide) return;
        if (hull.isWreck() || !enabled()) {
            q.clear();
            return;
        }
        q.drain(hull.level().getGameTime(), hull, SINK);
    }

    private static final RadioQueue.Sink SINK = new RadioQueue.Sink() {
        @Override
        public boolean ready(Object ctx, int tier) {
            if (tier != RadioQueue.SOFT) return true;
            VehicleEntity hull = (VehicleEntity) ctx;
            return !(hull.level() instanceof ServerLevel sl) || airtimeFree(sl, hull.position(), sl.getGameTime());
        }

        @Override
        public int play(Object ctx, int lineId, Object payload) {
            VehicleEntity hull = (VehicleEntity) ctx;
            Line line = LINES[lineId];
            SoundPool pool = (SoundPool) payload;
            long now = hull.level().getGameTime();
            hull.getPersistentData().putLong(TYPE_KEY + line.cooldownKey(), now + line.cooldown);
            int index = pool.draw();
            hull.level().playSound(null, hull, pool.event(index), SoundSource.VOICE, VOICELINE_VOLUME, 1.0f);
            if (line.tier == Tier.SOFT && hull.level() instanceof ServerLevel sl) {
                recordAirtime(sl, hull.position(), now);
            }
            return pool.ticks(index);
        }
    };

    private static boolean airtimeFree(ServerLevel level, Vec3 pos, long now) {
        AirtimeRing ring = AIRTIME.get(level);
        return ring == null || ring.free(pos, now);
    }

    private static void recordAirtime(ServerLevel level, Vec3 pos, long now) {
        AIRTIME.computeIfAbsent(level, l -> new AirtimeRing()).record(pos, now);
    }

    private static final class AirtimeRing {
        private final long[] times = new long[AIRTIME_HISTORY];
        private final double[] x = new double[AIRTIME_HISTORY];
        private final double[] z = new double[AIRTIME_HISTORY];
        private int cursor;

        synchronized boolean free(Vec3 pos, long now) {
            for (int i = 0; i < AIRTIME_HISTORY; i++) {
                if (times[i] == 0L) continue;
                if (now - times[i] > AIRTIME_WINDOW_TICKS) continue;
                double dx = pos.x - x[i];
                double dz = pos.z - z[i];
                if (dx * dx + dz * dz <= AIRTIME_RANGE_SQ) return false;
            }
            return true;
        }

        synchronized void record(Vec3 pos, long now) {
            times[cursor] = now;
            x[cursor] = pos.x;
            z[cursor] = pos.z;
            cursor = (cursor + 1) % AIRTIME_HISTORY;
        }
    }
}
