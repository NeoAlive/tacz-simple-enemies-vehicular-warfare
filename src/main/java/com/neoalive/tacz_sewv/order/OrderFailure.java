package com.neoalive.tacz_sewv.order;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.RegistryObject;

import com.neoalive.tacz_sewv.init.ModSounds;

/**
 * Why an order was refused, in the one vocabulary every refusal path shares.
 *
 * <p>Before this existed a rejected order was a bare {@code continue} in a packet handler followed
 * by one aggregate "No eligible units", so wrong hull, out of range, not the driver and no airport
 * all read identically — and the {@code setTarget} vetoes said nothing at all. A single enum is what
 * lets thirty-odd scattered rejection sites share one renderer, one throttle and one voice mapping
 * instead of each growing its own message.
 *
 * <p><b>Only six constants carry audio, and that is a fact about the recordings, not a design
 * choice to revisit.</b> Six clips exist; every other reason is text-only and takes a {@code null}
 * sound. A reason must never borrow a neighbouring clip to sound complete — a crew answering "target
 * destroyed" to "you are not the driver" is worse than saying nothing.
 */
public enum OrderFailure {

    // --- Eligibility: the unit itself cannot take this order ---
    NOT_OWNED("not_owned"),
    NOT_A_UNIT("not_a_unit"),
    MALFORMED("malformed"),
    UNIT_DEAD("unit_dead"),
    NOT_DRIVER("not_driver"),
    NOT_MOUNTED("not_mounted"),
    WRONG_HULL("wrong_hull"),
    OUT_OF_RANGE("out_of_range"),
    BUSY_MORTAR("busy_mortar"),
    BUSY_CREWING("busy_crewing"),

    // --- Target: the thing named cannot be engaged ---
    TARGET_GONE("target_gone", ModSounds.PMC_TARGET_GONE),
    TARGET_NOT_VEHICLE("target_not_vehicle", ModSounds.PMC_TARGET_NOT_VEHICLE),
    TARGET_FRIENDLY("target_friendly", ModSounds.PMC_TARGET_FRIENDLY),
    TARGET_IS_MEDIC("target_is_medic"),
    TARGET_EXCLUDED("target_excluded"),
    TARGET_OUT_OF_AREA("target_out_of_area"),
    TARGET_OBSTRUCTED("target_obstructed", ModSounds.PMC_TARGET_OBSTRUCTED),
    TARGET_UNDERGROUND("target_underground", ModSounds.PMC_UNDERGROUND),
    SELF_IS_MEDIC("self_is_medic", ModSounds.PMC_SELF_IS_MEDIC),
    SELF_NO_SIDEARM("self_no_sidearm"),

    // --- Destination: there is nowhere to send it ---
    NO_AIRPORT("no_airport"),
    NO_PAD("no_pad"),
    NO_RUNWAY("no_runway"),
    NO_ROUTE("no_route"),
    WRONG_DIMENSION("wrong_dimension"),
    NO_TRENCH("no_trench"),
    NO_GUARD_POST("no_guard_post"),
    MORTAR_TAKEN("mortar_taken"),
    MORTAR_GONE("mortar_gone"),
    VEHICLE_FULL("vehicle_full"),
    VEHICLE_WRECKED("vehicle_wrecked"),
    VEHICLE_GONE("vehicle_gone"),
    UNREACHABLE("unreachable"),

    // --- Permission: the player may not give this order ---
    ORDERS_LOCKED("orders_locked"),
    NOT_OPERATOR("not_operator"),
    NO_RADIO("no_radio");

    private final String key;
    @Nullable private final RegistryObject<SoundEvent> sound;

    OrderFailure(String key) {
        this(key, null);
    }

    OrderFailure(String key, @Nullable RegistryObject<SoundEvent> sound) {
        this.key = key;
        this.sound = sound;
    }

    /** The reason phrase for one unit. {@link OrderReport} adds the count when several agree. */
    public Component text() {
        return Component.translatable("message.tacz_sewv.fail." + this.key);
    }

    /** The recorded reply, or null for the reasons nothing was recorded for. */
    @Nullable
    public SoundEvent sound() {
        return this.sound == null ? null : this.sound.get();
    }
}
