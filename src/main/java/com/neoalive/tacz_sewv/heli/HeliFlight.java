package com.neoalive.tacz_sewv.heli;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.compat.NpcVehicleOverrides;
import com.neoalive.tacz_sewv.heli.data.HeliAirframes;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * Static entry points between Minecraft and the helicopter flight stack: the engine mixin calls
 * {@link #serverTick}/{@link #clientTick}, the pilot goal calls {@link #attach}/{@link #detach}.
 *
 * <p>A hull is ours only while it holds a runtime attached by its CURRENT seat-0 pilot's drive
 * goal. Any other AbstractUnit in the seat (a type without the goal, a goal that stopped) leaves
 * the hull to SBW's engine instead of stranding it.
 */
public final class HeliFlight {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** SBW's propeller visual rate: propellerRot += 30 x synchedPropellerRot per tick. */
    private static final float PROPELLER_VISUAL_RATE = 30.0F;

    private HeliFlight() {}

    public static boolean owns(VehicleEntity hull) {
        HeliRuntime r = ((IFlightDynamics) hull).sewv$heliRuntime();
        return r != null && r.ownedBy(hull);
    }

    public static HeliRuntime runtime(VehicleEntity hull) {
        return ((IFlightDynamics) hull).sewv$heliRuntime();
    }

    /** Server: our flight mode for this tick, or NONE to let SBW's engine run. */
    public static int serverTick(VehicleEntity hull) {
        HeliRuntime r = runtime(hull);
        if (r == null) return IFlightDynamics.NONE;
        if (!r.ownedBy(hull)) {
            detach(hull);
            return IFlightDynamics.NONE;
        }
        return r.tick(hull);
    }

    /**
     * Client: no physics. For an AI-crewed helicopter, cancel SBW's client-side engine (it would
     * re-simulate from synced stick inputs our hulls no longer use), spin the rotor from the synced
     * rotor speed with SBW's own visual rule, and apply the server's roll (roll is not synced by
     * SBW; {@code PacketHeliRoll} carries it). Position, yaw and pitch arrive through SBW's own
     * client interpolation.
     */
    public static int clientTick(VehicleEntity hull) {
        if (!(hull.getFirstPassenger() instanceof AbstractUnit)) return IFlightDynamics.NONE;
        hull.setPropellerRot(hull.getPropellerRot() + PROPELLER_VISUAL_RATE * hull.getSynchedPropellerRot());
        float roll = ((IFlightDynamics) hull).sewv$clientRoll();
        if (!Float.isNaN(roll)) hull.setZRot(roll);
        return IFlightDynamics.FLYING;
    }

    /**
     * Called by the pilot goal's start(), and again by its tick whenever {@link #stale} says the
     * airframe table was reloaded: the replacement runtime re-seats from the entity (pose, velocity,
     * engine state), so a hull in flight keeps flying on the new row. Returns null (SBW keeps flying)
     * if no airframe resolves.
     */
    public static HeliRuntime attach(VehicleEntity hull, AbstractUnit pilot) {
        HeliRuntime existing = runtime(hull);
        if (existing != null && existing.pilotId == pilot.getId() && existing.generation == HeliAirframes.generation()) {
            return existing;
        }
        String id = String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(hull.getType()));
        Airframe af = HeliAirframes.resolve(id, NpcVehicleOverrides.isTransportHeli(hull), NpcVehicleOverrides.isHeavyHeli(hull));
        if (af == null) {
            LOGGER.error("[sewv heli] no airframe table loaded; {} stays on SBW's engine", id);
            return null;
        }
        HeliRuntime r = new HeliRuntime(af, pilot.getId(), hull);
        ((IFlightDynamics) hull).sewv$setHeliRuntime(r);
        return r;
    }

    /** True when the airframe table was reloaded after this runtime was built. */
    public static boolean stale(HeliRuntime r) {
        return r != null && r.generation != HeliAirframes.generation();
    }

    /**
     * Hand the hull back to SBW: if it is airborne with the rotor turning, leave SBW's engine at its
     * own hover power so whoever flies it next (a player, SBW's pilotless idle) does not inherit a
     * stopped rotor in mid-air.
     */
    public static void detach(VehicleEntity hull) {
        IFlightDynamics fd = (IFlightDynamics) hull;
        HeliRuntime r = fd.sewv$heliRuntime();
        if (r == null) return;
        if (!hull.onGround() && r.s.engine == HeliState.Engine.RUN) {
            float hover = (float) Math.min(0.12, hull.computed().getGravity() / 0.66);
            hull.setPower(hover);
            hull.setSynchedPropellerRot(hover);
            hull.setEngineStart(true);
            hull.setEngineStartOver(true);
        }
        hull.setHoverMode(false);
        fd.sewv$setHeliRuntime(null);
        fd.sewv$setFlightMode(IFlightDynamics.NONE);
    }
}
