package com.neoalive.tacz_sewv.order;

import javax.annotation.Nullable;

import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.init.ModSounds;

/**
 * Why an order was refused, in the one vocabulary every refusal path shares.
 *
 * <p>Before this existed a rejected order was a bare {@code continue} in a packet handler followed
 * by one aggregate "No eligible units", so wrong hull, out of range, not the driver and no airport
 * all read identically — and the {@code setTarget} vetoes said nothing at all. A single enum is what
 * lets thirty-odd scattered rejection sites share one log line, one throttle and one voice mapping
 * instead of each growing its own message.
 *
 * <p><b>The constant name is the whole message.</b> Refusals are reported to the server console, not
 * to chat, so there is nothing to translate: these went through a {@code message.tacz_sewv.fail.*}
 * lang key each, and every one of those keys was the constant's own name in lower case, which is a
 * translation table that translates nothing and a second place to forget to add a reason.
 *
 * <p><b>Only six constants carry audio, and that is a fact about the recordings, not a design
 * choice to revisit.</b> Six clips exist; every other reason is silent and takes a {@code null}
 * sound. A reason must never borrow a neighbouring clip to sound complete — a crew answering "target
 * destroyed" to "you are not the driver" is worse than saying nothing.
 */
public enum OrderFailure {

    // --- Eligibility: the unit itself cannot take this order ---
    NOT_OWNED,
    NOT_A_UNIT,
    MALFORMED,
    UNIT_DEAD,
    UNIT_DOWNED,
    NOT_DRIVER,
    NOT_MOUNTED,
    WRONG_HULL,
    OUT_OF_RANGE,
    BUSY_MORTAR,
    BUSY_CREWING,

    // --- Target: the thing named cannot be engaged ---
    TARGET_GONE(ModSounds.PMC_TARGET_GONE),
    TARGET_NOT_VEHICLE(ModSounds.PMC_TARGET_NOT_VEHICLE),
    TARGET_FRIENDLY(ModSounds.PMC_TARGET_FRIENDLY),
    TARGET_IS_MEDIC,
    TARGET_EXCLUDED,
    TARGET_OUT_OF_AREA,
    TARGET_OBSTRUCTED(ModSounds.PMC_TARGET_OBSTRUCTED),
    TARGET_UNDERGROUND(ModSounds.PMC_UNDERGROUND),
    SELF_IS_MEDIC(ModSounds.PMC_SELF_IS_MEDIC),
    SELF_NO_SIDEARM,

    // --- Destination: there is nowhere to send it ---
    NO_AIRPORT,
    NO_PAD,
    NO_RUNWAY,
    NO_ROUTE,
    WRONG_DIMENSION,
    NO_TRENCH,
    NO_GUARD_POST,
    NO_MEDIC_IN_RANGE,
    MORTAR_TAKEN,
    MORTAR_GONE,
    VEHICLE_FULL,
    VEHICLE_WRECKED,
    VEHICLE_GONE,
    UNREACHABLE,

    // --- Permission: the player may not give this order ---
    ORDERS_LOCKED,
    FOB_COMMAND,
    NOT_OPERATOR,
    NO_RADIO,

    // --- Platoon ---
    NOT_IN_PLATOON,
    NOT_COMMANDER,
    ALREADY_IN_PLATOON,
    NO_PLATOON_HERE,
    MUST_BE_ON_FOOT,
    MUST_BE_MOUNTED,
    PLATOON_FULL;

    @Nullable private final RegistryObject<SoundEvent> sound;

    OrderFailure() {
        this(null);
    }

    OrderFailure(@Nullable RegistryObject<SoundEvent> sound) {
        this.sound = sound;
    }

    /** The recorded reply, or null for the reasons nothing was recorded for. */
    @Nullable
    public SoundEvent sound() {
        return this.sound == null ? null : this.sound.get();
    }
}
