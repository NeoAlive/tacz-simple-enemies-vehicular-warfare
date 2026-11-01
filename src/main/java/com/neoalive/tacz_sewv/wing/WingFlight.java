package com.neoalive.tacz_sewv.wing;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.flight.HullStateBridge;
import com.neoalive.tacz_sewv.heli.IFlightDynamics;
import com.neoalive.tacz_sewv.wing.data.WingAirframes;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;

/**
 * Static entry points between Minecraft and the fixed-wing flight stack, the counterpart of
 * {@code HeliFlight}: {@code MixinWingEngine} calls {@link #serverTick}/{@link #clientTick}, the
 * plane drive goal calls {@link #attach}/{@link #detach}.
 *
 * <p>A hull is ours only while it holds a runtime attached by its CURRENT seat-0 pilot and
 * {@code wingFlightModel} is on. Anything else leaves the hull to SBW's {@code aircraftEngine}.
 */
public final class WingFlight {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** SBW's propeller visual rate: propellerRot += 30 x power per tick (aircraftEngine). */
    private static final float PROPELLER_VISUAL_RATE = 30.0F;
    /** A client treats the hull as server-flown while roll packets are this fresh (2 heartbeats). */
    private static final long CLIENT_OWNERSHIP_TICKS = 40;

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
     * Client: no physics. While the server is flying an AI-crewed hull (roll packets arriving, see
     * {@code HullStateBridge}), cancel SBW's client engine, which would otherwise re-simulate the
     * hull from stick inputs our runtime never sets, and redo the visuals that engine computes:
     * the synced roll, the propeller, the gear. Position, yaw and pitch arrive through SBW's own
     * client sync. Control-surface (flap) animation is not reproduced: nothing syncs it.
     */
    public static int clientTick(VehicleEntity hull, EngineInfo.Aircraft engine) {
        if (!(hull.getFirstPassenger() instanceof AbstractUnit)) return IFlightDynamics.NONE;
        IFlightDynamics fd = (IFlightDynamics) hull;
        long mark = fd.sewv$clientRollTime();
        if (mark == Long.MIN_VALUE || hull.level().getGameTime() - mark > CLIENT_OWNERSHIP_TICKS) {
            return IFlightDynamics.NONE;
        }
        float roll = fd.sewv$clientRoll();
        if (!Float.isNaN(roll)) hull.setZRot(roll);
        hull.setPropellerRot(hull.getPropellerRot() + PROPELLER_VISUAL_RATE * hull.getPower());
        if (engine != null && engine.getHasGear()) hull.setGearRot(hull.getSynchedGearRot() * engine.getGearRotateAngle());
        return IFlightDynamics.FLYING;
    }

    /**
     * Attach a runtime for this pilot (idempotent), or null when the flight model is off or no
     * airframe resolves. A runtime built before an airframe-table reload is replaced; the new one
     * re-seats from the entity, so a hull in flight keeps flying on the new row.
     */
    public static WingRuntime attach(VehicleEntity hull, AbstractUnit pilot) {
        if (!enabled()) return null;
        WingRuntime existing = runtime(hull);
        if (existing != null && existing.pilotId == pilot.getId() && existing.generation == WingAirframes.generation()) {
            return existing;
        }
        String id = String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(hull.getType()));
        var info = hull.computed().getEngineInfo();
        boolean hasGear = info == null || !info.has("HasGear") || info.get("HasGear").getAsBoolean();
        WingAirframe af = WingAirframes.resolve(id, hasGear, hull.getBbWidth());
        if (af == null) {
            LOGGER.error("[sewv wing] no airframe table loaded; {} stays on SBW's engine", id);
            return null;
        }
        WingRuntime r = new WingRuntime(af, pilot.getId(), hull);
        ((IFlightDynamics) hull).sewv$setWingRuntime(r);
        return r;
    }

    /**
     * Hand the hull back to SBW. Airborne, leave SBW's engine in a state that keeps flying (power
     * on, engine started, gear up) so whoever flies it next (the legacy goal path, a player, SBW's
     * own pilotless behaviour) does not inherit a dead engine in mid-air; and tell tracking
     * clients to resume their own engine.
     */
    public static void detach(VehicleEntity hull) {
        IFlightDynamics fd = (IFlightDynamics) hull;
        WingRuntime r = fd.sewv$wingRuntime();
        if (r == null) return;
        if (!hull.onGround() && hull.getEnergy() > 0) {
            hull.setPower(Math.max(hull.getPower(), 1.0F));
            hull.setEngineStart(true);
            hull.setEngineStartOver(true);
        }
        fd.sewv$setWingRuntime(null);
        fd.sewv$setFlightMode(IFlightDynamics.NONE);
        HullStateBridge.sendRelease(hull);
    }
}
