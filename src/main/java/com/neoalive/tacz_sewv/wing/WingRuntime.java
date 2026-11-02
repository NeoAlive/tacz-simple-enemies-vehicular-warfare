package com.neoalive.tacz_sewv.wing;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import org.joml.Vector3d;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.flight.HullStateBridge;
import com.neoalive.tacz_sewv.heli.IFlightDynamics;
import com.neoalive.tacz_sewv.heli.WorldObstacleField;
import com.neoalive.tacz_sewv.wing.control.WingCore;
import com.neoalive.tacz_sewv.wing.data.WingAirframes;
import com.neoalive.tacz_sewv.wing.guidance.WingModeSelector;
import com.neoalive.tacz_sewv.wing.guidance.WingProcedureId;
import com.neoalive.tacz_sewv.wing.guidance.WingProcedureStack;
import com.neoalive.tacz_sewv.wing.guidance.WingProcedures;
import com.neoalive.tacz_sewv.wing.guidance.WingSituation;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingEnv;
import com.neoalive.tacz_sewv.wing.physics.WingPhysics;
import com.neoalive.tacz_sewv.wing.physics.WingState;

/**
 * Everything one AI-flown fixed-wing hull needs (server side): airframe, physics, controller,
 * procedure stack and terrain guard ({@link WingCore}), plus the bookkeeping that keeps our state
 * and the entity's agreeing ({@link HullStateBridge}, shared with the helicopter). Attached by the
 * plane drive goal while the hull is airborne in a mode the runtime flies, ticked from
 * {@code MixinWingEngine} inside the hull's own {@code travel()}, detached otherwise.
 *
 * <p>Per tick: reconcile with the entity, pick up guidance (or hold safe if it went stale), run six
 * sub-steps, publish the displacement, attitude and SBW's visual/engine fields back to the entity.
 */
public final class WingRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Roll heartbeat period, ticks (see {@link HullStateBridge#publishPose}). */
    private static final int HEARTBEAT_TICKS = 20;
    /** SBW's planeBreak (airbrake / flap visual) at full deflection. */
    private static final float SBW_PLANE_BREAK_MAX = 60.0F;

    final WingAirframe af;
    final int pilotId;
    /** The airframe table this runtime was built from; a /reload bumps it. */
    final int generation = WingAirframes.generation();
    final double g;
    final WingCore core;
    final WingState s;
    private final HullStateBridge bridge = new HullStateBridge();
    private final double orbitRadius;

    private WingSituation sit;
    private WingModeSelector.Choice requested;
    private boolean seated, warnedNaN;
    private double energyDebt;

    WingRuntime(WingAirframe af, int pilotId, VehicleEntity hull) {
        this.af = af;
        this.pilotId = pilotId;
        this.g = 400.0 * hull.computed().getGravity();
        this.core = new WingCore(af, g, WingAirframe.RHO0, energyCostRate(hull));
        this.s = core.s;
        this.orbitRadius = WingProcedures.orbitRadius(af, g);
    }

    boolean ownedBy(VehicleEntity hull) {
        return !hull.isWreck() && hull.getFirstPassenger() != null && hull.getFirstPassenger().getId() == pilotId;
    }

    public WingAirframe airframe() {
        return af;
    }

    public WingState state() {
        return s;
    }

    public WingProcedureId activeId() {
        return core.stack.activeId();
    }

    /** The orbit radius this airframe flies at cruise (the goal copies it into the snapshot). */
    public double orbitRadius() {
        return orbitRadius;
    }

    public boolean recovering() {
        return core.guard.recovering();
    }

    /** True when the active procedure's geometry allows firing (an attack run's window). */
    public boolean fireWindow() {
        return sit == null || core.stack.fireWindow(s, sit);
    }

    /** The goal's guidance for the next tick: the snapshot and the selector's choice. */
    public void setGuidance(WingSituation situation, WingModeSelector.Choice choice) {
        this.sit = situation;
        this.requested = choice;
    }

    /** One game tick, from inside the hull's {@code travel()}. Returns the flight mode. */
    int tick(VehicleEntity hull) {
        long gt = hull.level().getGameTime();
        double t0 = gt / 20.0;
        if (!seated) seatFull(hull);
        else if (bridge.reconcile(hull, s.p, s.v)) reseat(hull);
        if (hull.onGround()) { // touchdown belongs to SBW; the goal detaches us on its next tick
            bridge.parked(hull);
            return IFlightDynamics.NONE;
        }

        double fuel0 = hull.getEnergy();
        s.fuel = fuel0;
        float max = hull.getMaxHealth();
        if (max > 0.0F && hull.getHealth() < 0.1F * max) s.degraded = true;

        WingSituation guidance = sit != null ? sit : new WingSituation();
        guidance.orbitRadius = orbitRadius;
        if (WingProcedureStack.stale(sit, t0) || requested == null) {
            if (core.stack.activeId() != WingProcedureId.SAFETY_HOLD) {
                if (core.stack.activeId() != null) LOGGER.warn("[sewv wing] #{} guidance went stale; holding", hull.getId());
                core.stack.switchTo(WingProcedureId.SAFETY_HOLD, s, guidance, t0, gt);
            }
        } else {
            core.guide(requested, guidance, t0, gt);
        }

        WingEnv env = new WingEnv(g, WingAirframe.RHO0, new Vector3d(), WorldObstacleField.groundUnder(hull), false);
        Vector3d p0 = new Vector3d(s.p);
        core.fly(env, guidance, gt);
        if (!finite()) {
            if (!warnedNaN) LOGGER.error("[sewv wing] #{} state went non-finite; re-seating and holding", hull.getId());
            warnedNaN = true;
            s.p.set(p0);
            reseat(hull);
            core.stack.switchTo(WingProcedureId.SAFETY_HOLD, s, guidance, t0, gt);
        }
        publish(hull, fuel0, gt);
        if (gt % 20 == 0 && SewvDiag.planeVerbose()) telemetry(hull);
        return IFlightDynamics.FLYING;
    }

    /** First contact: the entity's pose, SBW's power and gear become our state. */
    private void seatFull(VehicleEntity hull) {
        reseat(hull);
        boolean fuelled = hull.getEnergy() > 0;
        s.engine = fuelled ? WingState.Engine.RUN : WingState.Engine.OFF;
        s.throttle = Math.max(0.0, Math.min(1.0, hull.getPower() / WingPhysics.SBW_FULL_POWER));
        s.gear = 1.0 - Math.max(0.0, Math.min(1.0, hull.getSynchedGearRot()));
        s.flap = s.brake = 0.0;
        seated = true;
    }

    private void reseat(VehicleEntity hull) {
        bridge.reseat(hull, s.p, s.v, s.q, s.w);
        core.ctl.reset();
    }

    private void publish(VehicleEntity hull, double fuel0, long gt) {
        bridge.publishPose(hull, s.p, s.q, gt % HEARTBEAT_TICKS == 0);
        boolean running = s.engine == WingState.Engine.RUN;
        hull.setPower((float) (WingPhysics.SBW_FULL_POWER * s.throttle));
        hull.setEngineStart(running);
        hull.setEngineStartOver(running);
        hull.setGearUp(s.gear < 0.5);
        hull.setSynchedGearRot((float) (1.0 - s.gear));
        hull.setPlaneBreak((float) (SBW_PLANE_BREAK_MAX * s.brake));

        double burned = fuel0 - s.fuel;
        if (burned > 0.0 && Double.isFinite(burned)) {
            energyDebt += burned;
            int whole = (int) Math.min(Integer.MAX_VALUE, Math.floor(energyDebt));
            if (whole > 0) {
                hull.consumeEnergy(whole);
                energyDebt -= whole;
            }
        }
    }

    /** The hull's SBW {@code EnergyCostRate}; {@code computed()} is valid from the spawn tick. */
    private static double energyCostRate(VehicleEntity hull) {
        var info = hull.computed().getEngineInfo();
        return info != null && info.has("EnergyCostRate") ? info.get("EnergyCostRate").getAsDouble() : 1.0;
    }

    /** {@code planeCombatDebug}: what the stack is doing, in one line. */
    private void telemetry(VehicleEntity hull) {
        var c = core.ctl;
        LOGGER.info(String.format(java.util.Locale.ROOT,
                "[sewv wing] #%d %s %s V=%.1f y=%.1f thr=%.2f alpha=%.1f beta=%.1f nz=%.2f | cmd V=%.1f phi=%.0f "
                        + "gamma=%.1f | lim alpha=%b n=%b slow=%b gcas=%b",
                hull.getId(), af.name, core.stack.activeId(), s.airspeed, s.p.y, s.throttle,
                Math.toDegrees(s.alpha), Math.toDegrees(s.beta), s.nz, c.speedCmd, Math.toDegrees(c.phiCmd),
                Math.toDegrees(c.gammaCmd), c.alphaLimited, c.nLimited, c.underspeed, core.guard.recovering()));
    }

    private boolean finite() {
        return Double.isFinite(s.p.x + s.p.y + s.p.z + s.v.x + s.v.y + s.v.z + s.q.x + s.q.y + s.q.z + s.q.w
                + s.w.x + s.w.y + s.w.z);
    }
}
