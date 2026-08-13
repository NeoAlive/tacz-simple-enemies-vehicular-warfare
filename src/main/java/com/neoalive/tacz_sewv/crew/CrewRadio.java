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
        TOW(100), FIXING(80), HEALING(80), DRONE(80);
        final int cooldown;
        Line(int cooldown) { this.cooldown = cooldown; }
    }

    private static final String OVERLAP_KEY = "tacz_sewv:radio_cd"; // shared: nothing else speaks while a clip plays
    private static final String TYPE_KEY = "tacz_sewv:radio_";      // + line name: per-line pacing
    // ponytail: ~longest typical clip; a rare 6.6s line can tail-overlap. Per-hull, from getPersistentData.
    private static final int OVERLAP_TICKS = 90;

    private CrewRadio() {}

    /**
     * The hull's crew speaks -- for hull-level events (damaged, spotted, orders, decoy, ifv). The
     * first AI crewman is the voice: the driver if it is AI, otherwise a gunner, so a hull a PLAYER
     * is driving still calls out through its AI crew instead of going silent.
     */
    public static void play(VehicleEntity hull, Line line) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit crew) {
                speak(hull, crew, line);
                return;
            }
        }
    }

    /** A specific crew member speaks (e.g. the one bailing out), still one line per hull. */
    public static void speak(VehicleEntity hull, AbstractUnit speaker, Line line) {
        if (hull.level().isClientSide || !SewvConfig.VEHICLE_VOICELINES_ENABLED.get()) return;
        SoundPool pool = poolFor(speaker, line, HullFacts.isShipHull(hull));
        if (pool == null) return;

        long now = hull.level().getGameTime();
        CompoundTag data = hull.getPersistentData();
        String typeKey = TYPE_KEY + line.name();
        // BAIL is an emergency: DAMAGED holds OVERLAP_KEY under fire and would otherwise mute it
        // until the crew is already dead outside the hull.
        if (line != Line.BAIL && now < data.getLong(OVERLAP_KEY)) return;
        if (now < data.getLong(typeKey)) return;
        data.putLong(OVERLAP_KEY, now + OVERLAP_TICKS);
        data.putLong(typeKey, now + line.cooldown);
        // SoundSource.VOICE puts these on the dedicated Voice/Speech slider, separate from combat noise.
        // Bound to the HULL, not a coordinate: the entity overload sends ClientboundSoundEntityPacket,
        // so the clip tracks the vehicle client-side instead of being left behind by a hull moving at
        // 30 m/s. The hull rather than the speaker because it outlives a crewman who bails or dies
        // mid-line, and while seated the two positions are the same.
        hull.level().playSound(null, hull, pool.next(), SoundSource.VOICE, VOICELINE_VOLUME, 1.0f);
    }

    /**
     * On-foot support call-out (medic / engineer). Cooldown lives on the unit; plays bound to the
     * speaker so the clip walks with them.
     */
    public static void speakUnit(AbstractUnit speaker, Line line) {
        if (speaker.level().isClientSide || !SewvConfig.VEHICLE_VOICELINES_ENABLED.get()) return;
        SoundPool pool = poolFor(speaker, line, false);
        if (pool == null) return;

        long now = speaker.level().getGameTime();
        CompoundTag data = speaker.getPersistentData();
        String typeKey = TYPE_KEY + line.name();
        if (now < data.getLong(OVERLAP_KEY)) return;
        if (now < data.getLong(typeKey)) return;
        data.putLong(OVERLAP_KEY, now + OVERLAP_TICKS);
        data.putLong(typeKey, now + line.cooldown);
        speaker.level().playSound(null, speaker, pool.next(), SoundSource.VOICE, VOICELINE_VOLUME, 1.0f);
    }

    /**
     * A one-shot reply to something the player just did — the refusal clips picked by
     * {@code OrderFailure}, and nothing ambient.
     *
     * <p>Deliberately <b>outside</b> the {@link Line} pacing above. That machinery exists to stop
     * repeated ambient chatter from clumping, and running an answer through it would let idle
     * chatter swallow the explanation for an order the player is waiting on. The caller does its own
     * throttling, which it must anyway: it is rate-limiting the reason, not the voice.
     */
    public static void speakRefusal(AbstractUnit speaker, net.minecraft.sounds.SoundEvent clip) {
        if (speaker.level().isClientSide || !SewvConfig.VEHICLE_VOICELINES_ENABLED.get()) return;
        speaker.level().playSound(null, speaker, clip, SoundSource.VOICE, VOICELINE_VOLUME, 1.0f);
    }

    /**
     * The faction's pool for this line, with the <b>navy</b> variants standing in on a boat: a
     * ground crew's idle chatter and contact calls talk about tanks and ground targets, which reads
     * as nonsense from a gunboat. Only the two lines that name what they are looking at are
     * swapped — being hit, bailing out and popping smoke sound the same at sea.
     */
    private static SoundPool poolFor(AbstractUnit unit, Line line, boolean navy) {
        if (unit instanceof RUunitEntity) return switch (line) {
            case DAMAGED -> ModSounds.RU_DAMAGED;
            case SPOTTED -> navy ? ModSounds.RU_NAVY_TARGET : ModSounds.RU_SPOTTED;
            case BAIL    -> ModSounds.RU_BAIL;
            case DECOY   -> ModSounds.RU_DECOY;
            case IFV     -> ModSounds.RU_IFV;
            case IDLE    -> navy ? ModSounds.RU_NAVY_IDLE : ModSounds.RU_IDLE;
            case TOW     -> ModSounds.RU_TOW;
            case FIXING  -> ModSounds.RU_FIXING;
            case HEALING -> ModSounds.RU_HEALING;
            case DRONE   -> ModSounds.RU_DRONE;
            case ORDERS, TAKEOFF -> null;
        };
        if (unit instanceof USunitEntity) return switch (line) {
            case DAMAGED -> ModSounds.US_DAMAGED;
            case SPOTTED -> navy ? ModSounds.US_NAVY_TARGET : ModSounds.US_SPOTTED;
            case BAIL    -> ModSounds.US_BAIL;
            case DECOY   -> ModSounds.US_DECOY;
            case IFV     -> ModSounds.US_IFV;
            case IDLE    -> navy ? ModSounds.US_NAVY_IDLE : ModSounds.US_IDLE;
            case TOW     -> ModSounds.US_TOW;
            case FIXING  -> ModSounds.US_FIXING;
            case HEALING -> ModSounds.US_HEALING;
            case DRONE   -> ModSounds.US_DRONE;
            case ORDERS, TAKEOFF -> null;
        };
        return switch (line) { // PMC
            case DAMAGED -> ModSounds.PMC_DAMAGED;
            case SPOTTED -> navy ? ModSounds.PMC_NAVY_TARGET : ModSounds.PMC_SPOTTED;
            case ORDERS  -> ModSounds.PMC_ORDERS;
            case TAKEOFF -> ModSounds.PMC_TAKEOFF;
            case BAIL    -> ModSounds.PMC_BAIL;
            case DECOY   -> ModSounds.PMC_DECOY;
            case IDLE    -> navy ? ModSounds.PMC_NAVY_IDLE : ModSounds.PMC_IDLE;
            case TOW     -> ModSounds.PMC_TOW;
            case FIXING  -> ModSounds.PMC_FIXING;
            case HEALING -> ModSounds.PMC_HEALING;
            case IFV, DRONE -> null; // PMC IFVs / drones field no dedicated line
        };
    }
}
