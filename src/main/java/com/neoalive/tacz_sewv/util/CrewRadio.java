package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.HullFacts;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.init.ModSounds.SoundPool;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

/**
 * One radio voice per hull. The driver ({@code getFirstPassenger}) speaks for the whole crew,
 * picking a non-repeating clip from its faction's pool and holding a shared cooldown so nothing
 * overlaps -- damaged, spotted and orders all share the one channel. Off-foot units and mortar
 * crews are never passengers of a hull, so this only ever fires from inside a vehicle.
 */
public final class CrewRadio {
    /**
     * Per-line minimum gap (ticks) between two of the SAME line on one hull, on top of the shared
     * anti-overlap. DAMAGED is throttled hard because it fires on every hit -- without this it would
     * hold the one channel and starve spotted/bail/decoy, which is what made lines feel rare.
     */
    public enum Line {
        DAMAGED(160), SPOTTED(90), ORDERS(60), BAIL(60), DECOY(60), IFV(90), IDLE(600), TOW(100);
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
        if (now < data.getLong(OVERLAP_KEY)) return;  // a clip is still playing on this hull
        if (now < data.getLong(typeKey)) return;      // this line spoke too recently
        data.putLong(OVERLAP_KEY, now + OVERLAP_TICKS);
        data.putLong(typeKey, now + line.cooldown);
        // SoundSource.VOICE puts these on the dedicated Voice/Speech slider, separate from combat noise.
        // Bound to the HULL, not a coordinate: the entity overload sends ClientboundSoundEntityPacket,
        // so the clip tracks the vehicle client-side instead of being left behind by a hull moving at
        // 30 m/s. The hull rather than the speaker because it outlives a crewman who bails or dies
        // mid-line, and while seated the two positions are the same.
        float volume = SewvConfig.VEHICLE_VOICELINE_VOLUME.get().floatValue();
        hull.level().playSound(null, hull, pool.next(), SoundSource.VOICE, volume, 1.0f);
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
            case ORDERS  -> null;
        };
        if (unit instanceof USunitEntity) return switch (line) {
            case DAMAGED -> ModSounds.US_DAMAGED;
            case SPOTTED -> navy ? ModSounds.US_NAVY_TARGET : ModSounds.US_SPOTTED;
            case BAIL    -> ModSounds.US_BAIL;
            case DECOY   -> ModSounds.US_DECOY;
            case IFV     -> ModSounds.US_IFV;
            case IDLE    -> navy ? ModSounds.US_NAVY_IDLE : ModSounds.US_IDLE;
            case TOW     -> ModSounds.US_TOW;
            case ORDERS  -> null;
        };
        return switch (line) { // PMC
            case DAMAGED -> ModSounds.PMC_DAMAGED;
            case SPOTTED -> navy ? ModSounds.PMC_NAVY_TARGET : ModSounds.PMC_SPOTTED;
            case ORDERS  -> ModSounds.PMC_ORDERS;
            case BAIL    -> ModSounds.PMC_BAIL;
            case DECOY   -> ModSounds.PMC_DECOY;
            case IDLE    -> navy ? ModSounds.PMC_NAVY_IDLE : ModSounds.PMC_IDLE;
            case TOW     -> ModSounds.PMC_TOW;
            case IFV     -> null; // PMC IFVs field no dedicated line
        };
    }
}
