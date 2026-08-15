package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;

import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;

/**
 * Keeps a boarding {@link PmcCommanderEntity} out of the driver, gunner and Climb ("ledge") seats
 * whenever a different free seat exists, by claiming {@code VehicleEntity.entityIndexOverride} — a
 * public hook {@code addPassenger} already consults before its own "first free slot" scan, and one
 * confirmed unused anywhere else in SuperbWarfare or this mod (safe to claim outright).
 *
 * <p>Deliberately does <b>not</b> move a Commander after the fact ({@code changeSeat} only
 * reassigns the seat-index list, not vanilla's mount-order {@code passengers} list that
 * {@code getFirstPassenger()} — and with it every "who is driving" read in this mod — actually
 * uses; a post-mount reseat would leave the Commander seated in the back while still being treated
 * as the driver everywhere). Steering the initial seat index instead sidesteps that split cleanly.
 */
public final class CommanderSeating {

    private CommanderSeating() {
    }

    /**
     * Installs (or refreshes) the override on {@code vehicle}. Safe to call on every board attempt
     * and safe to leave installed indefinitely — every entity that is not a {@link PmcCommanderEntity}
     * falls straight through to SuperbWarfare's own default assignment.
     */
    public static void install(VehicleEntity vehicle) {
        vehicle.setEntityIndexOverride(entity -> resolveSeat(vehicle, entity));
    }

    private static Integer resolveSeat(VehicleEntity v, Entity entity) {
        if (!(entity instanceof PmcCommanderEntity)) return -1;

        int seats = Math.max(1, v.getMaxPassengers());
        Set<Integer> occupied = new HashSet<>();
        for (Entity passenger : v.getPassengers()) {
            int idx = v.getSeatIndex(passenger);
            if (idx >= 0) occupied.add(idx);
        }

        int fallback = -1; // no alternative — take whatever free seat exists, restricted or not
        for (int seat = 0; seat < seats; seat++) {
            if (occupied.contains(seat)) continue;
            if (fallback == -1) fallback = seat;
            if (!isRestricted(v, seat)) return seat;
        }
        return fallback;
    }

    private static boolean isRestricted(VehicleEntity v, int seat) {
        if (seat == 0) return true; // SuperbWarfare's driver is simply the first passenger
        try {
            SeatInfo info = v.getSeat(seat);
            if (info == null) return false;
            if ("Climb".equals(info.pose)) return true;
            List<String> weapons = info.weapons();
            return weapons != null && !weapons.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }
}
