package com.neoalive.tacz_sewv.wing;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.heli.IFlightDynamics;

/**
 * Static entry points between Minecraft and the fixed-wing flight stack, the counterpart of
 * {@code HeliFlight}: {@code MixinWingEngine} calls {@link #serverTick}/{@link #clientTick}, the
 * plane drive goal calls {@link #attach}/{@link #detach}.
 *
 * <p>A hull is ours only while it holds a runtime attached by its CURRENT seat-0 pilot and
 * {@code wingFlightModel} is on. Anything else leaves the hull to SBW's {@code aircraftEngine}.
 */
public final class WingFlight {

    private WingFlight() {}

    public static boolean enabled() {
        return SewvConfig.WING_FLIGHT_MODEL.get();
    }

    public static WingRuntime runtime(VehicleEntity hull) {
        return ((IFlightDynamics) hull).sewv$wingRuntime();
    }

    public static boolean owns(VehicleEntity hull) {
        WingRuntime r = runtime(hull);
        return r != null && r.ownedBy(hull);
    }

    /** Server: our flight mode for this tick, or NONE to let SBW's engine run. */
    public static int serverTick(VehicleEntity hull) {
        WingRuntime r = runtime(hull);
        if (r == null) return IFlightDynamics.NONE;
        if (!enabled() || !r.ownedBy(hull)) {
            detach(hull);
            return IFlightDynamics.NONE;
        }
        return r.tick(hull);
    }

    /**
     * Client: no physics. Until the server integrator flies hulls (later phases) this always lets
     * SBW's client engine run; it then becomes the HeliFlight.clientTick shape (synced roll, visuals,
     * SBW's client re-simulation cancelled).
     */
    public static int clientTick(VehicleEntity hull) {
        return IFlightDynamics.NONE;
    }

    /** Attach a runtime for this pilot (idempotent), or null when the flight model is off. */
    public static WingRuntime attach(VehicleEntity hull, AbstractUnit pilot) {
        if (!enabled()) return null;
        WingRuntime existing = runtime(hull);
        if (existing != null && existing.pilotId == pilot.getId()) return existing;
        WingRuntime r = new WingRuntime(pilot.getId());
        ((IFlightDynamics) hull).sewv$setWingRuntime(r);
        return r;
    }

    /** Hand the hull back to SBW. */
    public static void detach(VehicleEntity hull) {
        IFlightDynamics fd = (IFlightDynamics) hull;
        if (fd.sewv$wingRuntime() == null) return;
        fd.sewv$setWingRuntime(null);
        fd.sewv$setFlightMode(IFlightDynamics.NONE);
    }
}
