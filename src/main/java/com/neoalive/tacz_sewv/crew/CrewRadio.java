package com.neoalive.tacz_sewv.crew;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.init.ModSounds.SoundPool;

/**
 * One radio voice per hull (mounted crew) or per unit (on-foot support lines). The driver
 * ({@code getFirstPassenger}) speaks for the whole crew, picking a non-repeating clip from its
 * faction's pool and holding a shared cooldown so nothing overlaps -- damaged, spotted and orders
 * all share the one channel. BAIL bypasses that overlap gate so a dying hull's "we're out" is not
 * starved by DAMAGED spam. Off-foot support lines (fixing / healing / drone) use
 * {@link #speakUnit}.
 */
public final class CrewRadio {

    private static final float VOICELINE_VOLUME = 1.8F;
    /**
     * Per-line minimum gap (ticks) between two of the SAME line on one hull, on top of the shared
     * anti-overlap. DAMAGED is throttled hard because it fires on every hit -- without this it would
     * hold the one channel and starve spotted/bail/decoy, which is what made lines feel rare.
     * IDLE is longer so chatter stays frequent but not constant.
     */
    public enum Line {
        DAMAGED(160), SPOTTED(90), ORDERS(60), TAKEOFF(60), BAIL(40), DECOY(60), IFV(90), IDLE(900),
        TOW(100), FIXING(80), HEALING(80), DRONE(80), INVESTIGATING(90), AMMO(60);
        final int cooldown;
        Line(int cooldown) { this.cooldown = cooldown; }
    }

    // ponytail: ~longest typical clip; a rare 6.6s line can tail-overlap. Per-hull, from getPersistentData.
    private static final int OVERLAP_TICKS = 90;

    private static final String OVERLAP_KEY = "tacz_sewv:radio_cd";
    private static final String TYPE_KEY = "tacz_sewv:radio_";

    private CrewRadio() {}

    public static void play(VehicleEntity hull, Line line) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit crew) {
                speak(hull, crew, line);
                return;
            }
        }
    }

    public static void speak(VehicleEntity hull, AbstractUnit speaker, Line line) {
        if (hull.level().isClientSide || !SewvConfig.VEHICLE_VOICELINES_ENABLED.get()) return;
        SoundPool pool = poolFor(speaker, line, HullFacts.isShipHull(hull));
        if (pool == null) return;
        playPool(hull, speaker, line, pool, hull.getPersistentData(), true);
    }

    /** Ammo lines use a pool chosen by {@link AmmoVoicelines}, not {@link #poolFor}. */
    public static void playAmmo(VehicleEntity hull, AbstractUnit speaker, SoundPool pool) {
        if (hull.level().isClientSide || !SewvConfig.VEHICLE_VOICELINES_ENABLED.get()) return;
        playPool(hull, speaker, Line.AMMO, pool, hull.getPersistentData(), false);
    }

    public static void speakUnit(AbstractUnit speaker, Line line) {
        if (speaker.level().isClientSide || !SewvConfig.VEHICLE_VOICELINES_ENABLED.get()) return;
        SoundPool pool = poolFor(speaker, line, false);
        if (pool == null) return;
        playPool(speaker, speaker, line, pool, speaker.getPersistentData(), registersAwareness(line));
    }

    public static void speakRefusal(AbstractUnit speaker, net.minecraft.sounds.SoundEvent clip) {
        if (speaker.level().isClientSide || !SewvConfig.VEHICLE_VOICELINES_ENABLED.get()) return;
        speaker.level().playSound(null, speaker, clip, SoundSource.VOICE, VOICELINE_VOLUME, 1.0f);
    }

    private static void playPool(VehicleEntity hull, AbstractUnit speaker, Line line, SoundPool pool,
            CompoundTag data, boolean boundToHull) {
        long now = hull.level().getGameTime();
        String typeKey = TYPE_KEY + line.name();
        if (line != Line.BAIL && now < data.getLong(OVERLAP_KEY)) return;
        if (now < data.getLong(typeKey)) return;
        data.putLong(OVERLAP_KEY, now + OVERLAP_TICKS);
        data.putLong(typeKey, now + line.cooldown);
        Entity soundEntity = boundToHull ? hull : speaker;
        soundEntity.level().playSound(null, soundEntity, pool.next(), SoundSource.VOICE, VOICELINE_VOLUME, 1.0f);
        if (registersAwareness(line) && hull.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            AwarenessCues.registerCrewVoice(sl, speaker, hull.blockPosition());
        }
    }

    private static void playPool(AbstractUnit speaker, AbstractUnit voiceEntity, Line line, SoundPool pool,
            CompoundTag data, boolean registerAwareness) {
        long now = speaker.level().getGameTime();
        String typeKey = TYPE_KEY + line.name();
        if (now < data.getLong(OVERLAP_KEY)) return;
        if (now < data.getLong(typeKey)) return;
        data.putLong(OVERLAP_KEY, now + OVERLAP_TICKS);
        data.putLong(typeKey, now + line.cooldown);
        speaker.level().playSound(null, voiceEntity, pool.next(), SoundSource.VOICE, VOICELINE_VOLUME, 1.0f);
        if (registerAwareness && speaker.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            AwarenessCues.registerCrewVoice(sl, speaker, speaker.blockPosition());
        }
    }

    private static boolean registersAwareness(Line line) {
        return line != Line.INVESTIGATING && line != Line.AMMO;
    }

    private static SoundPool poolFor(AbstractUnit unit, Line line, boolean navy) {
        if (unit instanceof RUunitEntity) return switch (line) {
            case DAMAGED -> ModSounds.RU_DAMAGED;
            case SPOTTED -> navy ? ModSounds.RU_NAVY_TARGET : ModSounds.RU_SPOTTED;
            case ORDERS -> ModSounds.RU_ORDERS;
            case INVESTIGATING -> ModSounds.RU_INVESTIGATING;
            case BAIL -> ModSounds.RU_BAIL;
            case DECOY -> ModSounds.RU_DECOY;
            case IFV -> ModSounds.RU_IFV;
            case IDLE -> navy ? ModSounds.RU_NAVY_IDLE : ModSounds.RU_IDLE;
            case TOW -> ModSounds.RU_TOW;
            case FIXING -> ModSounds.RU_FIXING;
            case HEALING -> ModSounds.RU_HEALING;
            case DRONE -> ModSounds.RU_DRONE;
            case TAKEOFF, AMMO -> null;
        };
        if (unit instanceof USunitEntity) return switch (line) {
            case DAMAGED -> ModSounds.US_DAMAGED;
            case SPOTTED -> navy ? ModSounds.US_NAVY_TARGET : ModSounds.US_SPOTTED;
            case ORDERS -> ModSounds.US_ORDERS;
            case INVESTIGATING -> ModSounds.US_INVESTIGATING;
            case BAIL -> ModSounds.US_BAIL;
            case DECOY -> ModSounds.US_DECOY;
            case IFV -> ModSounds.US_IFV;
            case IDLE -> navy ? ModSounds.US_NAVY_IDLE : ModSounds.US_IDLE;
            case TOW -> ModSounds.US_TOW;
            case FIXING -> ModSounds.US_FIXING;
            case HEALING -> ModSounds.US_HEALING;
            case DRONE -> ModSounds.US_DRONE;
            case TAKEOFF, AMMO -> null;
        };
        return switch (line) {
            case DAMAGED -> ModSounds.PMC_DAMAGED;
            case SPOTTED -> navy ? ModSounds.PMC_NAVY_TARGET : ModSounds.PMC_SPOTTED;
            case ORDERS -> ModSounds.PMC_ORDERS;
            case TAKEOFF -> ModSounds.PMC_TAKEOFF;
            case BAIL -> ModSounds.PMC_BAIL;
            case DECOY -> ModSounds.PMC_DECOY;
            case IDLE -> navy ? ModSounds.PMC_NAVY_IDLE : ModSounds.PMC_IDLE;
            case TOW -> ModSounds.PMC_TOW;
            case FIXING -> ModSounds.PMC_FIXING;
            case HEALING -> ModSounds.PMC_HEALING;
            case IFV, DRONE, INVESTIGATING, AMMO -> null;
        };
    }
}
