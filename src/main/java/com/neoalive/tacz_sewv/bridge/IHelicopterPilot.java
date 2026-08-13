package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import com.neoalive.tacz_sewv.item.PlaneAttackMode;

/**
 * Player-issued flight command carried on a unit entity, read by
 * {@link com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal}. Set server-side by
 * {@link com.neoalive.tacz_sewv.network.PacketHelicopterCommand}; not synced to the
 * client (the goal runs server-side, same as the boarding flag on
 * {@link IVehicleBoarder}).
 *
 * <p>TAKEOFF is a one-shot sequence that clears itself (to NONE) once the
 * helicopter reaches cruise altitude. LANDING transitions to LANDED on touchdown,
 * and LANDED is sticky: the helicopter stays shut down on the ground — ignoring
 * move/follow orders — until a new TAKEOFF or LANDING command replaces it. NONE is
 * the resting airborne state where the goal follows the SEM order queue as usual.
 *
 * <p>The state is stored in the entity's Forge persistent data rather than mixin
 * fields, so it survives world save/load — a helicopter parked via LANDED must
 * still be parked after a reload, not take off on its own because a transient
 * field reset to NONE. The unit mixins only need {@code implements}; these
 * default methods are the whole implementation ({@code getInt} on a missing key
 * returns 0 == HELI_CMD_NONE, which is exactly the right default).
 */
public interface IHelicopterPilot {
    int HELI_CMD_NONE = 0;
    int HELI_CMD_TAKEOFF = 1;
    int HELI_CMD_LANDING = 2;
    int HELI_CMD_LANDED = 3;
    /**
     * Wire-only: "put down somewhere near you". It is <b>never stored</b> — no goal has a case for
     * it, and it must not be, because the difference between it and {@link #HELI_CMD_LANDING} is
     * entirely a difference in <i>which pad gets chosen</i>, which is a decision
     * {@link com.neoalive.tacz_sewv.network.PacketHelicopterCommand} makes once and then writes down
     * as an ordinary landing. Adding a fifth state to the flight machine to express "the same thing,
     * arrived at differently" would give every landing branch a second case that behaves identically.
     */
    int HELI_CMD_EMERGENCY_LAND = 4;

    // Terrain-relative cruise offset a pilot holds, set live by the takeoff order and read by
    // DriveHelicopterGoal (which clamps it to its own 30-50 flight band). This replaced the old
    // HELI_CRUISE_ALTITUDE config so the player can retrim altitude from the TDT without landing,
    // and each takeoff press updates it. Crews that never got a takeoff order (autonomous RU/US,
    // and TankSpawner's) fall back to this default — the old config default, so nothing changes.
    int DEFAULT_CRUISE_ALTITUDE = 35;
    String TAG_HELI_COMMAND = "tacz_sewv_heli_command";
    String TAG_HELI_LAND_POS = "tacz_sewv_heli_land_pos";
    String TAG_HELI_CRUISE_ALT = "tacz_sewv_heli_cruise_alt";
    /**
     * Ordnance the last radio fire mission asked this pilot for
     * ({@link com.neoalive.tacz_sewv.item.PlaneAttackMode}). Persistent for the same reason the
     * flight command is: it is a standing instruction, not a per-tick decision, and a pilot that
     * forgot it on reload would silently revert a bombing mission to guns. Zero — a missing key —
     * is {@code AUTO}, which is the correct answer for every crew nobody ever radioed.
     */
    String TAG_PLANE_ATTACK_MODE = "tacz_sewv_plane_attack_mode";

    default void sewv$setHeliCommand(int command) {
        ((Entity) this).getPersistentData().putInt(TAG_HELI_COMMAND, command);
    }

    default int sewv$getHeliCommand() {
        return ((Entity) this).getPersistentData().getInt(TAG_HELI_COMMAND);
    }

    default void sewv$setHeliLandPos(BlockPos pos) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (pos == null) {
            tag.remove(TAG_HELI_LAND_POS);
        } else {
            tag.putLong(TAG_HELI_LAND_POS, pos.asLong());
        }
    }

    default BlockPos sewv$getHeliLandPos() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_HELI_LAND_POS) ? BlockPos.of(tag.getLong(TAG_HELI_LAND_POS)) : null;
    }

    default void sewv$setCruiseAltitude(int altitude) {
        ((Entity) this).getPersistentData().putInt(TAG_HELI_CRUISE_ALT, altitude);
    }

    default int sewv$getCruiseAltitude() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_HELI_CRUISE_ALT) ? tag.getInt(TAG_HELI_CRUISE_ALT) : DEFAULT_CRUISE_ALTITUDE;
    }

    default void sewv$setPlaneAttackMode(PlaneAttackMode mode) {
        ((Entity) this).getPersistentData().putInt(TAG_PLANE_ATTACK_MODE,
                mode == null ? PlaneAttackMode.AUTO.ordinal() : mode.ordinal());
    }

    default PlaneAttackMode sewv$getPlaneAttackMode() {
        return PlaneAttackMode.byOrdinal(
                ((Entity) this).getPersistentData().getInt(TAG_PLANE_ATTACK_MODE));
    }
}
