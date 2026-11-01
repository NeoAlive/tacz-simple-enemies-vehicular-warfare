package com.neoalive.tacz_sewv.wing;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;

import com.neoalive.tacz_sewv.flight.HullStateBridge;
import com.neoalive.tacz_sewv.heli.IFlightDynamics;

/**
 * Everything one AI-flown fixed-wing hull needs (server side): the fixed-wing counterpart of
 * {@code HeliRuntime}. Attached by the plane drive goal once the hull is airborne (SBW owns the
 * ground roll), ticked from {@code MixinWingEngine} inside the hull's own {@code travel()}, and
 * detached at touchdown or when the goal stops.
 *
 * <p>Phase 0 scaffold: the entity bridge and ownership are real; the physics, controller and
 * procedure stack land in later phases. Until they do, {@link #tick} answers {@code NONE} and SBW
 * keeps flying the hull, so attaching a runtime changes nothing in game.
 */
public final class WingRuntime {

    final int pilotId;
    final HullStateBridge bridge = new HullStateBridge();

    WingRuntime(int pilotId) {
        this.pilotId = pilotId;
    }

    boolean ownedBy(VehicleEntity hull) {
        return !hull.isWreck() && hull.getFirstPassenger() != null && hull.getFirstPassenger().getId() == pilotId;
    }

    /** One game tick, from inside the hull's {@code travel()}. Returns the flight mode. */
    int tick(VehicleEntity hull) {
        bridge.parked(hull);
        return IFlightDynamics.NONE;
    }
}
