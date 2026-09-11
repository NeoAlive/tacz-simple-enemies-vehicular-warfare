package com.neoalive.tacz_sewv.crew;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.init.ModSounds.SoundPool;

/**
 * One radio voice per hull for <b>mounted</b> SEM crews. On-foot infantry keep SEM's own lines —
 * this class must not speak for them. The driver ({@code getFirstPassenger}) speaks for the whole
 * crew. Gated by {@link SewvConfig#VEHICLE_VOICELINES_ENABLED} on every entry point.
 *
 * <p>Tier A lines use per-hull overlap + per-line cooldown; Tier B (idle / plan-dispatch / shoot)
 * also pass a local airtime gate so dense packs do not chorus.
 */
public final class CrewRadio {

    private static final float VOICELINE_VOLUME = 1.8F;
    /** Hull health fraction below which low-health / panicked shoot pools apply (&lt; 60%). */
    public static final float LOW_HEALTH_FRACTION = 0.6F;

    public enum Line {
        ORDER_DISPATCH(60),
        /** RU/US scored plan change — heavier safety net; airtime also applies. */
        ORDER_DISPATCH_PLAN(240),
        TARGET_GENERIC(90),
        TARGET_HELICOPTER(90),
        TARGET_PLANE(90),
        TARGET_SHIP(90),
        TARGET_TANK(90),
        UNIT_DEPLOY_DRONE(80),
        UNIT_DIG(80),
        UNIT_HEAL(80),
        UNIT_REPAIR(80),
        VEHICLE_BAIL(40),
        VEHICLE_IDLE(600),
        VEHICLE_LOW_HEALTH(200),
        VEHICLE_MG_SHOOT(140),
        VEHICLE_CANNON_SHOOT(140);

        final int cooldown;
        Line(int cooldown) { this.cooldown = cooldown; }

        boolean soft() {
            return this == VEHICLE_IDLE || this == ORDER_DISPATCH_PLAN
                    || this == VEHICLE_MG_SHOOT || this == VEHICLE_CANNON_SHOOT;
        }

        boolean bypassOverlap() {
            return this == VEHICLE_BAIL;
        }

        boolean registersAwareness() {
            return this != VEHICLE_IDLE && this != UNIT_HEAL && this != UNIT_REPAIR && this != UNIT_DIG;
        }
    }

    private static final int OVERLAP_TICKS = 90;
    private static final String OVERLAP_KEY = "tacz_sewv:radio_cd";
    private static final String TYPE_KEY = "tacz_sewv:radio_";
    static final String LOW_HEALTH_SPOKEN_KEY = "tacz_sewv:radio_low_hp";

    /** Recent soft-line speak positions for the local airtime gate (per dimension). */
    private static final int AIRTIME_HISTORY = 32;
    private static final double AIRTIME_RANGE_SQ = 48.0 * 48.0;
    private static final int AIRTIME_WINDOW_TICKS = 50;
    private static final java.util.WeakHashMap<ServerLevel, AirtimeRing> AIRTIME = new java.util.WeakHashMap<>();

    private CrewRadio() {}

    /** Master switch — every public entry point must honour this. */
    public static boolean enabled() {
        return SewvConfig.VEHICLE_VOICELINES_ENABLED.get();
    }

    public static void play(VehicleEntity hull, Line line) {
        if (!enabled() || hull.level().isClientSide) return;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit crew) {
                speak(hull, crew, line);
                return;
            }
        }
    }

    public static void speak(VehicleEntity hull, AbstractUnit speaker, Line line) {
        if (!enabled() || hull.level().isClientSide) return;
        // Speaker must still be riding this hull — bail mid-dismount must not orphan a line on foot.
        if (speaker.getVehicle() != hull) return;
        SoundPool pool = poolFor(speaker, line, panicked(hull));
        if (pool == null) return;
        playPool(hull, speaker, line, pool, hull.getPersistentData());
    }

    /**
     * Support-role callout (heal / repair / dig / drone). Only when the unit is mounted — on foot
     * SEM owns the voice. Routes through the hull radio channel.
     */
    public static void speakUnit(AbstractUnit speaker, Line line) {
        if (!enabled() || speaker.level().isClientSide) return;
        if (!(speaker.getVehicle() instanceof VehicleEntity hull) || hull.isWreck()) return;
        speak(hull, speaker, line);
    }

    /**
     * MG / cannon fire callout. Tier B. Panicked pools while hull health &lt; {@link #LOW_HEALTH_FRACTION}.
     *
     * @param weaponRole {@code VehicleWeapons.WEAPON_CANNON} (0) or {@code WEAPON_MG} (1)
     * @return true if a clip actually played
     */
    public static boolean playShoot(VehicleEntity hull, int weaponRole) {
        if (!enabled() || hull.level().isClientSide) return false;
        // Literals avoid a CrewRadio ↔ VehicleWeapons import cycle; keep in sync with WEAPON_*.
        if (weaponRole != 0 && weaponRole != 1) return false;
        Line line = weaponRole == 1 ? Line.VEHICLE_MG_SHOOT : Line.VEHICLE_CANNON_SHOOT;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit crew) {
                SoundPool pool = poolFor(crew, line, panicked(hull));
                if (pool == null) return false;
                return playPool(hull, crew, line, pool, hull.getPersistentData());
            }
        }
        return false;
    }

    /** @return true if the clip played */
    public static boolean playIdle(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide) return false;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit crew) {
                SoundPool pool = poolFor(crew, Line.VEHICLE_IDLE, false);
                if (pool == null) return false;
                return playPool(hull, crew, Line.VEHICLE_IDLE, pool, hull.getPersistentData());
            }
        }
        return false;
    }

    public static boolean panicked(VehicleEntity hull) {
        return hull.getHealth() < LOW_HEALTH_FRACTION * hull.getMaxHealth();
    }

    /**
     * Rising-edge low-health line. Clears the spoken flag when health recovers above the band so a
     * later dip can speak again. Retries while low if overlap blocked the first attempt.
     */
    public static void maybeLowHealth(VehicleEntity hull) {
        if (!enabled() || hull.level().isClientSide) return;
        CompoundTag data = hull.getPersistentData();
        boolean low = panicked(hull);
        if (!low) {
            data.putBoolean(LOW_HEALTH_SPOKEN_KEY, false);
            return;
        }
        if (data.getBoolean(LOW_HEALTH_SPOKEN_KEY)) return;
        // Empty hull: nothing to say (and no crew to attribute).
        AbstractUnit crew = null;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit u) {
                crew = u;
                break;
            }
        }
        if (crew == null) return;
        SoundPool pool = poolFor(crew, Line.VEHICLE_LOW_HEALTH, false);
        if (pool == null) {
            data.putBoolean(LOW_HEALTH_SPOKEN_KEY, true);
            return;
        }
        if (playPool(hull, crew, Line.VEHICLE_LOW_HEALTH, pool, data)) {
            data.putBoolean(LOW_HEALTH_SPOKEN_KEY, true);
        }
    }

    private static boolean playPool(VehicleEntity hull, AbstractUnit speaker, Line line, SoundPool pool,
            CompoundTag data) {
        long now = hull.level().getGameTime();
        String typeKey = TYPE_KEY + line.name();
        if (!line.bypassOverlap() && now < data.getLong(OVERLAP_KEY)) return false;
        if (now < data.getLong(typeKey)) return false;
        if (line.soft() && hull.level() instanceof ServerLevel sl
                && !airtimeFree(sl, hull.position(), now)) {
            return false;
        }
        data.putLong(OVERLAP_KEY, now + OVERLAP_TICKS);
        data.putLong(typeKey, now + line.cooldown);
        hull.level().playSound(null, hull, pool.next(), SoundSource.VOICE, VOICELINE_VOLUME, 1.0f);
        if (line.soft() && hull.level() instanceof ServerLevel sl) {
            recordAirtime(sl, hull.position(), now);
        }
        if (line.registersAwareness() && hull.level() instanceof ServerLevel sl) {
            AwarenessCues.registerCrewVoice(sl, speaker, hull.blockPosition());
        }
        return true;
    }

    private static boolean airtimeFree(ServerLevel level, Vec3 pos, long now) {
        AirtimeRing ring = AIRTIME.get(level);
        if (ring == null) return true;
        return ring.free(pos, now);
    }

    private static void recordAirtime(ServerLevel level, Vec3 pos, long now) {
        AIRTIME.computeIfAbsent(level, l -> new AirtimeRing()).record(pos, now);
    }

    private static SoundPool poolFor(AbstractUnit unit, Line line, boolean panicked) {
        if (unit instanceof RUunitEntity) return switch (line) {
            case ORDER_DISPATCH, ORDER_DISPATCH_PLAN -> ModSounds.ORDER_DISPATCH_RU;
            case TARGET_GENERIC -> ModSounds.TARGET_GENERIC_RU;
            case TARGET_HELICOPTER -> ModSounds.TARGET_HELICOPTER_RU;
            case TARGET_PLANE -> ModSounds.TARGET_PLANE_RU;
            case TARGET_SHIP -> ModSounds.TARGET_SHIP_RU;
            case TARGET_TANK -> ModSounds.TARGET_TANK_RU;
            case UNIT_DEPLOY_DRONE -> ModSounds.UNIT_DEPLOY_DRONE_RU;
            case UNIT_DIG -> ModSounds.UNIT_DIG_RU;
            case UNIT_HEAL -> ModSounds.UNIT_HEAL_RU;
            case UNIT_REPAIR -> ModSounds.UNIT_REPAIR_RU;
            case VEHICLE_BAIL -> ModSounds.VEHICLE_BAIL_RU;
            case VEHICLE_IDLE -> ModSounds.VEHICLE_IDLE_RU;
            case VEHICLE_LOW_HEALTH -> ModSounds.VEHICLE_LOW_HEALTH_RU;
            case VEHICLE_MG_SHOOT -> panicked
                    ? ModSounds.VEHICLE_MG_SHOOT_RU_PANICKED : ModSounds.VEHICLE_MG_SHOOT_RU;
            case VEHICLE_CANNON_SHOOT -> panicked
                    ? ModSounds.VEHICLE_CANNON_SHOOT_RU_PANICKED : ModSounds.VEHICLE_CANNON_SHOOT_RU;
        };
        if (unit instanceof USunitEntity) return switch (line) {
            case ORDER_DISPATCH, ORDER_DISPATCH_PLAN -> ModSounds.ORDER_DISPATCH_US;
            case TARGET_GENERIC -> ModSounds.TARGET_GENERIC_US;
            case TARGET_HELICOPTER -> ModSounds.TARGET_HELICOPTER_US;
            case TARGET_PLANE -> ModSounds.TARGET_PLANE_US;
            case TARGET_SHIP -> ModSounds.TARGET_SHIP_US;
            case TARGET_TANK -> ModSounds.TARGET_TANK_US;
            case UNIT_DEPLOY_DRONE -> ModSounds.UNIT_DEPLOY_DRONE_US;
            case UNIT_DIG -> ModSounds.UNIT_DIG_US;
            case UNIT_HEAL -> ModSounds.UNIT_HEAL_US;
            case UNIT_REPAIR -> ModSounds.UNIT_REPAIR_US;
            case VEHICLE_BAIL -> ModSounds.VEHICLE_BAIL_US;
            case VEHICLE_IDLE -> ModSounds.VEHICLE_IDLE_US;
            case VEHICLE_LOW_HEALTH -> ModSounds.VEHICLE_LOW_HEALTH_US;
            case VEHICLE_MG_SHOOT -> panicked
                    ? ModSounds.VEHICLE_MG_SHOOT_US_PANICKED : ModSounds.VEHICLE_MG_SHOOT_US;
            case VEHICLE_CANNON_SHOOT -> panicked
                    ? ModSounds.VEHICLE_CANNON_SHOOT_US_PANICKED : ModSounds.VEHICLE_CANNON_SHOOT_US;
        };
        // PMC (and any other AbstractUnit)
        return switch (line) {
            case ORDER_DISPATCH, ORDER_DISPATCH_PLAN -> ModSounds.ORDER_DISPATCH_PMC;
            case TARGET_GENERIC -> ModSounds.TARGET_GENERIC_PMC;
            case TARGET_HELICOPTER -> ModSounds.TARGET_HELICOPTER_PMC;
            case TARGET_PLANE -> ModSounds.TARGET_PLANE_PMC;
            case TARGET_SHIP -> ModSounds.TARGET_SHIP_PMC;
            case TARGET_TANK -> ModSounds.TARGET_TANK_PMC;
            case UNIT_DEPLOY_DRONE -> ModSounds.UNIT_DEPLOY_DRONE_PMC;
            case UNIT_DIG -> null;
            case UNIT_HEAL -> ModSounds.UNIT_HEAL_PMC;
            case UNIT_REPAIR -> ModSounds.UNIT_REPAIR_PMC;
            case VEHICLE_BAIL -> ModSounds.VEHICLE_BAIL_PMC;
            case VEHICLE_IDLE -> ModSounds.VEHICLE_IDLE_PMC;
            case VEHICLE_LOW_HEALTH -> ModSounds.VEHICLE_LOW_HEALTH_PMC;
            case VEHICLE_MG_SHOOT -> panicked
                    ? ModSounds.VEHICLE_MG_SHOOT_PMC_PANICKED : ModSounds.VEHICLE_MG_SHOOT_PMC;
            case VEHICLE_CANNON_SHOOT -> panicked
                    ? ModSounds.VEHICLE_CANNON_SHOOT_PMC_PANICKED : ModSounds.VEHICLE_CANNON_SHOOT_PMC;
        };
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
