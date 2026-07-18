package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Drivetrain facts about the hull a crew is riding, cached for its lifetime.
 *
 * <p>Both are read from {@code data().compute()}, which is far too expensive to call from
 * an AI tick, and neither can change mid-drive — so they are resolved once per hull and
 * held. {@link #attach} is keyed on entity identity, so calling it every tick costs a
 * reference comparison and re-boarding the same hull reuses the answers.
 *
 * <p>Reads are defensive: unreadable hull data must never crash the AI tick, and each
 * fact falls back to the answer whose behaviour is the safe one.
 */
final class HullFacts {

    private VehicleEntity vehicle;
    private boolean helicopter;
    private boolean tracked;
    private boolean ifv;
    private Set<Integer> crewSeats = Set.of(0);

    void attach(VehicleEntity v) {
        if (this.vehicle == v) return;
        this.vehicle = v;
        this.helicopter = computeHelicopter(v);
        this.tracked = computeTracked(v);
        this.ifv = computeIfv(v);
        this.crewSeats = this.ifv ? computeCrewSeats(v) : Set.of(0);
    }

    /** Helicopters and fixed-wing (which subclass {@link EngineInfo.Helicopter}) fly, not drive. */
    boolean isHelicopter() {
        return this.helicopter;
    }

    /** Tracked hulls pivot in place; wheeled ones must roll through a turn. */
    boolean isTracked() {
        return this.tracked;
    }

    /**
     * Carries a dismount squad rather than just a crew, so it puts its infantry on the ground when
     * the shooting starts — see {@link DriveVehicleGoal#dismountSquad}.
     */
    boolean isIfv() {
        return this.ifv;
    }

    /**
     * The seats that stay buttoned up on an IFV: the <b>driver</b> and the <b>turret</b>. Everyone
     * else is a dismount.
     *
     * <p>Deliberately not "every seat that has a weapon", which is the obvious rule and does not
     * survive the actual data: SuperbWarfare's own {@code bmp_2} arms all six of its rear seats
     * with firing-port MGs, so that rule would empty nothing at all from one of the commonest RU
     * hulls, while {@code t_90a} — a tank — has a weaponless seat that rule would empty.
     * Counting weapons finds the turret wherever the pack put it, which matters because that is
     * not a convention either: SBW and MCSP hulls put the turret in seat 0, while FCP's BMPs put a
     * <em>weaponless driver</em> there and the turret in seat 1. Seat 0 is always the driver
     * regardless — SuperbWarfare's driver is simply the first passenger.
     */
    Set<Integer> crewSeats() {
        return this.crewSeats;
    }

    private static Set<Integer> computeCrewSeats(VehicleEntity v) {
        int turret = 0;
        int most = -1;
        try {
            int seats = Math.max(1, v.getMaxPassengers());
            for (int seat = 0; seat < seats; seat++) {
                SeatInfo info = v.getSeat(seat);
                List<String> weapons = info == null ? null : info.weapons();
                int count = weapons == null ? 0 : weapons.size();
                if (count > most) {
                    most = count;
                    turret = seat;
                }
            }
        } catch (Exception ignored) {
            return Set.of(0); // unreadable seats: keep everyone aboard rather than empty the hull
        }
        // Set.of rejects duplicates, and on most hulls the driver IS the turret.
        return turret == 0 ? Set.of(0) : Set.of(0, turret);
    }

    /**
     * Matched on the registry id rather than read from the hull data, because <b>nothing in the
     * vehicle data says "IFV"</b>. Seat counts don't separate them (a T-90A has a spare seat too)
     * and neither do weapons (SuperbWarfare's BMP-2 arms all six of its rear seats with
     * firing-port MGs). The id is the only signal that actually tracks the vehicle class, which
     * is why the clue list is config — an addon's hull is caught without this mod knowing it.
     */
    private static boolean computeIfv(VehicleEntity v) {
        if (!SewvConfig.IFV_DISMOUNTS_ENABLED.get()) return false;
        try {
            String id = ForgeRegistries.ENTITY_TYPES.getKey(v.getType()).toString().toLowerCase(Locale.ROOT);
            for (String clue : SewvConfig.IFV_NAME_CLUES.get()) {
                if (!clue.isBlank() && id.contains(clue.toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean computeHelicopter(VehicleEntity v) {
        try {
            return v.getEngineInfo() instanceof EngineInfo.Helicopter;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean computeTracked(VehicleEntity v) {
        try {
            var data = v.data().compute();
            if (data != null) {
                var trackRotSpeed = data.getEngineInfo().get("TrackRotSpeed");
                return trackRotSpeed != null && trackRotSpeed.getAsInt() > 0;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
