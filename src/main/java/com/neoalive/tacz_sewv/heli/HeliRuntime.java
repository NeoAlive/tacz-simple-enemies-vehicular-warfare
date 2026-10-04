package com.neoalive.tacz_sewv.heli;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Vector3d;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.heli.avoid.AvoidForce;
import com.neoalive.tacz_sewv.heli.avoid.ObstacleSet;
import com.neoalive.tacz_sewv.heli.avoid.PathProbe;
import com.neoalive.tacz_sewv.heli.control.FlightCore;
import com.neoalive.tacz_sewv.heli.data.HeliAirframes;
import com.neoalive.tacz_sewv.heli.guidance.FirePhase;
import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.guidance.ModeSelector;
import com.neoalive.tacz_sewv.heli.guidance.Parity;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureId;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureStack;
import com.neoalive.tacz_sewv.heli.guidance.Situation;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliEnv;
import com.neoalive.tacz_sewv.heli.physics.HeliState;
import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.heli.physics.RotorModel;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHeliRoll;

/**
 * Everything one AI-flown helicopter needs (server side): airframe, physics, controller,
 * procedure stack and barrier, plus the bookkeeping that keeps our state and the entity's agreeing.
 * Attached by {@code DriveHelicopterGoal.start()}, ticked from the engine mixin inside the hull's
 * own {@code travel()}, detached on {@code stop()}.
 *
 * <p>Per tick: reconcile with the entity, pick up guidance, then either hold at rest (SBW owns a
 * parked hull's pose) or run six sub-steps of controller + barrier + physics, and publish the
 * displacement and attitude back to the entity.
 *
 * <p><b>The entity is authoritative for position.</b> {@code move()} resolves collision. A
 * collided axis loses its velocity (inelastic contact). Any jump we did not cause, or an attitude
 * we did not set, means someone else wrote the hull (helipad snap, spawners, carrier ops, ...)
 * and we re-seat from the entity rather than fight it. That covers every outside writer without
 * editing one.
 */
public final class HeliRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Publish deadband: an angle change smaller than this is not written (Komodo's ROT_EPS). */
    private static final double ANGLE_DEADBAND = 0.05;
    /**
     * Vanilla {@code Entity.move} drops any displacement with lengthSqr <= 1e-7 (|d| <= 3.16e-4 m,
     * javap-verified): the hull simply does not move. Below that we keep our own integrated
     * position and publish the accumulated residual next tick, so slow motion is never lost.
     */
    private static final double VANILLA_MIN_MOVE_SQR = 1.0E-7;
    /** An outside write: position off our prediction by this much without a collision, or angles by this. */
    private static final double RESEAT_DISTANCE = 0.5, RESEAT_ANGLE = 0.5;
    /** SBW's own synchedPropellerRot ceiling, used to scale the synced rotor speed. */
    private static final float SBW_ROTOR_MAX = 0.12F;

    final Airframe af;
    final int pilotId;
    /** The airframe table this runtime was built from; a /reload bumps it (plan section 5). */
    final int generation = HeliAirframes.generation();
    final double g;
    /** The pure per-tick flight stack (shared with the headless self-checks). */
    final FlightCore core;
    final HeliState s;
    final ProcedureStack stack;
    private final double halfWidth, height;

    Situation sit;
    ModeSelector.Choice requested;
    private boolean seated, restLatched, failed, warnedNaN;
    private Vector3d lastDm;
    /** Our intended position after the last publish, and the entity position we published from. */
    final Vector3d predicted = new Vector3d();
    private final Vector3d pubFrom = new Vector3d();
    private float pubYaw, pubPitch, pubRoll, sentRoll = Float.NaN;
    private double energyDebt;

    HeliRuntime(Airframe resolved, int pilotId, VehicleEntity hull) {
        this.g = 400.0 * hull.computed().getGravity();
        Airframe af = resolved;
        double tMax = Airframe.RHO0 * af.area * af.tipSpeed * af.tipSpeed * RotorModel.maxCt(af);
        if (tMax < 1.05 * af.mass * this.g) {
            LOGGER.warn("[sewv heli] {} row '{}' cannot lift at this hull's gravity {} m/s^2; scaling its C_T table by {}",
                    hull.getType(), af.name, g, this.g / 24.0);
            af = af.withCtScale(this.g / 24.0);
            tMax *= this.g / 24.0;
        }
        this.af = af;
        this.pilotId = pilotId;
        this.halfWidth = hull.getBbWidth() / 2.0;
        this.height = hull.getBbHeight();
        double aC = Math.max(g * StrictMath.tan(af.tiltMax), tMax / af.mass - g);
        this.core = new FlightCore(af, g, Airframe.RHO0, fuelPerJoule(af, g, hull),
                new AvoidForce(aC, af.vMaxH, af.vDescent, 2.0, halfWidth, height, 1.0, 0.05));
        this.s = core.s;
        this.stack = core.stack;
    }

    boolean ownedBy(VehicleEntity hull) {
        return !hull.isWreck() && hull.getFirstPassenger() != null && hull.getFirstPassenger().getId() == pilotId;
    }

    public ProcedureId activeId() {
        return stack.activeId();
    }

    public HeliState state() {
        return s;
    }

    public Airframe airframe() {
        return af;
    }

    public double gravity() {
        return g;
    }

    /** Attack procedures completed against the current target (the selector's engage cycle). */
    public int engageCycle() {
        return stack.engageCycle();
    }

    public FirePhase firePhase() {
        return stack.firePhase();
    }

    /** The active procedure lets the guns fire (pitch-free cone, the run's window). */
    public boolean fireWindow() {
        return sit == null || stack.fireWindow(s, sit);
    }

    /** A point ahead on the active procedure's route (terrain look-ahead), or null. */
    public double[] lookahead() {
        return stack.lookahead();
    }

    /** Sim time the active procedure began. */
    public double procedureBegan() {
        return stack.beganAt();
    }

    HeliReference lastRef() {
        return core.lastRef;
    }

    /** The goal's guidance for the next tick: the snapshot and the selector's choice. */
    public void setGuidance(Situation situation, ModeSelector.Choice choice) {
        this.sit = situation;
        this.requested = choice;
    }

    /** One game tick, from inside the hull's {@code travel()}. Returns the flight mode. */
    int tick(VehicleEntity hull) {
        long gt = hull.level().getGameTime();
        double t0 = gt / 20.0;
        if (!seated) seatFull(hull);
        else reconcile(hull);

        double fuel0 = hull.getEnergy();
        s.fuel = fuel0;
        float max = hull.getMaxHealth();
        if (!failed && max > 0.0F && hull.getHealth() < 0.1F * max) {
            failed = true; // O4: engine and tail rotor both lost; the airframe autorotates and spins
            s.engine = HeliState.Engine.FAILED;
            s.tailFailed = true;
        }
        HeliEnv env = new HeliEnv(g, Airframe.RHO0, new Vector3d(), WorldObstacleField.groundUnder(hull), hull.onGround());

        Situation guidance = sit != null ? sit : new Situation();
        if (ProcedureStack.stale(sit, t0)) {
            if (stack.activeId() != ProcedureId.SAFETY_HOLD) {
                if (stack.activeId() != null) LOGGER.warn("[sewv heli] #{} guidance went stale; holding", hull.getId());
                stack.switchTo(ProcedureId.SAFETY_HOLD, s, guidance, t0, gt);
            }
        } else if (requested != null) {
            core.guide(requested, guidance, t0, gt);
        }

        boolean wantsEngine = stack.engine() == HeliControl.EngineCmd.START;
        if (restLatched && !wantsEngine) {
            publishParked(hull);
            return IFlightDynamics.PARKED;
        }
        if (restLatched) {
            restLatched = false;
            reseatPose(hull); // SBW owned the pose while parked
        } else if (env.onGround() && (s.engine == HeliState.Engine.OFF || s.engine == HeliState.Engine.FAILED)
                && s.omega < 0.05 * af.omegaN && s.v.length() < 0.05 && !wantsEngine) {
            restLatched = true;
            publishParked(hull);
            return IFlightDynamics.PARKED;
        }

        ObstacleSet obstacles = WorldObstacleField.build(hull, core.barrier, s.p, s.v, core.barrier.detectRadius(),
                stack.groundBarrier(t0));
        PathProbe.Result path = WorldObstacleField.probePath(hull, core.rawRef(t0, guidance), s.v, halfWidth, height,
                guidance.targetHullId, Parity.side(pilotId));
        core.avoidance(obstacles, path, halfWidth + 3.0, t0);
        Vector3d p0 = new Vector3d(s.p);
        core.integrate(env, guidance, gt, null);
        if (!finite()) {
            if (!warnedNaN) LOGGER.error("[sewv heli] #{} state went non-finite; re-seating and holding", hull.getId());
            warnedNaN = true;
            s.p.set(p0);
            reseatPose(hull);
            stack.switchTo(ProcedureId.SAFETY_HOLD, s, guidance, t0, gt);
        }
        publish(hull, fuel0);
        Downwash.push(hull, af, s);
        if (gt % 20 == 0 && SewvDiag.heliFlightVerbose()) telemetry(hull);
        return IFlightDynamics.FLYING;
    }

    // --- Entity <-> state ------------------------------------------------------------------------

    /** First contact: the entity's pose and SBW's engine flags become our state. */
    private void seatFull(VehicleEntity hull) {
        reseatPose(hull);
        boolean running = hull.getEngineStartOver();
        s.engine = running ? HeliState.Engine.RUN : HeliState.Engine.OFF;
        s.omega = running ? af.omegaN : 0.0;
        s.omegaTarget = s.omega;
        s.govErrPrev = Double.NaN;
        s.theta0 = running && !hull.onGround() ? RotorModel.hoverCollective(af, g, Airframe.RHO0) : 0.0;
        s.pedal = 0.0;
        seated = true;
    }

    /** Adopt the entity's pose (position, velocity, attitude); body rates zero. */
    private void reseatPose(VehicleEntity hull) {
        Vec3 dm = hull.getDeltaMovement();
        s.p.set(hull.getX(), hull.getY(), hull.getZ());
        s.v.set(dm.x * 20.0, dm.y * 20.0, dm.z * 20.0);
        s.q.set(McPose.toQuat(hull.getYRot(), hull.getXRot(), hull.getRoll()));
        s.w.zero();
        core.ctl.reset();
        pubYaw = hull.getYRot();
        pubPitch = hull.getXRot();
        pubRoll = hull.getRoll();
        predicted.set(s.p);
        lastDm = null;
    }

    private void reconcile(VehicleEntity hull) {
        if (lastDm != null && lastDm.lengthSquared() <= VANILLA_MIN_MOVE_SQR
                && pubFrom.equals(hull.getX(), hull.getY(), hull.getZ())) {
            s.p.set(predicted); // vanilla dropped a sub-threshold move: keep the residual
            return;
        }
        Vec3 dmNow = hull.getDeltaMovement();
        if (lastDm != null) {
            if (lastDm.x != 0.0 && dmNow.x == 0.0) s.v.x = 0.0;
            if (lastDm.z != 0.0 && dmNow.z == 0.0) s.v.z = 0.0;
            if (hull.verticalCollision && lastDm.y != 0.0 && dmNow.y == 0.0) s.v.y = 0.0;
        }
        boolean collided = hull.horizontalCollision || hull.verticalCollision;
        double jump = predicted.distance(hull.getX(), hull.getY(), hull.getZ());
        boolean attitudeWritten = Math.abs(McPose.wrapDeg(hull.getYRot() - pubYaw)) > RESEAT_ANGLE
                || Math.abs(hull.getXRot() - pubPitch) > RESEAT_ANGLE
                || Math.abs(McPose.wrapDeg(hull.getRoll() - pubRoll)) > RESEAT_ANGLE;
        if ((jump > RESEAT_DISTANCE && !collided) || attitudeWritten) {
            reseatPose(hull);
        } else {
            s.p.set(hull.getX(), hull.getY(), hull.getZ());
        }
    }

    private void publish(VehicleEntity hull, double fuel0) {
        // From the entity, not from p0: p0 may carry a residual vanilla dropped last tick.
        pubFrom.set(hull.getX(), hull.getY(), hull.getZ());
        Vector3d dm = new Vector3d(s.p).sub(pubFrom);
        hull.setDeltaMovement(dm.x, dm.y, dm.z);
        lastDm = dm;
        predicted.set(s.p);

        McPose.Euler e = McPose.toEuler(s.q, new McPose.Euler(pubYaw, pubPitch, pubRoll));
        if (Math.abs(McPose.wrapDeg(e.yawDeg() - pubYaw)) >= ANGLE_DEADBAND) {
            hull.setYRot((float) e.yawDeg());
            pubYaw = hull.getYRot();
        }
        if (Math.abs(e.pitchDeg() - pubPitch) >= ANGLE_DEADBAND) {
            hull.setXRot((float) e.pitchDeg());
            pubPitch = hull.getXRot();
        }
        if (Math.abs(McPose.wrapDeg(e.rollDeg() - pubRoll)) >= ANGLE_DEADBAND) {
            hull.setZRot((float) e.rollDeg());
            pubRoll = hull.getRoll();
        }
        if (Float.isNaN(sentRoll) || Math.abs(McPose.wrapDeg(pubRoll - sentRoll)) >= ANGLE_DEADBAND) {
            sentRoll = pubRoll;
            NetworkHandler.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> hull),
                    new PacketHeliRoll(hull.getId(), pubRoll));
        }

        boolean powered = s.engine == HeliState.Engine.START || s.engine == HeliState.Engine.RUN;
        hull.setPower((float) (SBW_ROTOR_MAX * s.theta0));
        hull.setSynchedPropellerRot((float) (SBW_ROTOR_MAX * s.omega / af.omegaN));
        hull.setEngineStart(powered);
        hull.setEngineStartOver(s.engine == HeliState.Engine.RUN);
        hull.setHoverMode(false);

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

    /** At rest: SBW's gravity and landing-gear alignment hold the hull; only bleed horizontal drift. */
    private void publishParked(VehicleEntity hull) {
        Vec3 dm = hull.getDeltaMovement();
        hull.setDeltaMovement(dm.x * 0.85, dm.y, dm.z * 0.85);
        hull.setPower(0.0F);
        hull.setSynchedPropellerRot((float) (SBW_ROTOR_MAX * s.omega / af.omegaN));
        hull.setEngineStart(false);
        hull.setEngineStartOver(false);
        hull.setHoverMode(false);
        lastDm = null;
        predicted.set(hull.getX(), hull.getY(), hull.getZ());
    }

    /**
     * Hover-anchored fuel burn (plan section 4.3): chosen so a nominal hover burns exactly what
     * SBW's own engine did (EnergyCostRate x 8.3333 x p_hover, p_hover = 0.0909, i.e. 0.7576 x
     * EnergyCostRate per tick), then scales with real shaft power.
     */
    private static double fuelPerJoule(Airframe af, double g, VehicleEntity hull) {
        // computed() is valid from the spawn tick; getEngineInfo() is null until the first travel().
        var info = hull.computed().getEngineInfo();
        double rate = info != null && info.has("EnergyCostRate") ? info.get("EnergyCostRate").getAsDouble() : 1.0;
        double pHover = RotorModel.hoverPower(af, g, Airframe.RHO0);
        return 20.0 * af.hoverBurnFraction * rate / (pHover / af.efficiency + af.idlePower);
    }

    /** heliFlightDebug: what the stack is doing, in one line. */
    private void telemetry(VehicleEntity hull) {
        HeliReference r = core.lastRef;
        if (r == null) return;
        var ctl = core.ctl;
        LOGGER.info(String.format(java.util.Locale.ROOT,
                "[sewv heli] #%d %s(%s) %.1fs engine=%s rotor=%.2f coll=%.2f | p=(%.1f,%.1f,%.1f) v=%.1f | "
                        + "err p=%.2f v=%.2f yaw=%.1fdeg | bias=%.1f/%.1f barrier=%.2fg | sat thrust=%b tilt=%b auto=%b",
                hull.getId(), stack.activeId(), stack.activeClass(), r.t() - stack.beganAt(), s.engine,
                s.omega / af.omegaN, s.theta0, s.p.x, s.p.y, s.p.z, s.v.length(),
                s.p.distance(r.p().x(), r.p().y(), r.p().z()), s.v.distance(r.v().x(), r.v().y(), r.v().z()),
                McPose.wrapDeg(Math.toDegrees(r.yaw() - headingRad())),
                core.bias.altitude(), core.bias.lateral(), core.avoid.length() / (af.mass * g),
                ctl.thrustSaturated, ctl.tiltSaturated, ctl.autorotationLaw));
    }

    private double headingRad() {
        Vector3d f = s.q.transform(new Vector3d(0, 0, 1));
        return StrictMath.atan2(-f.x, f.z);
    }

    private boolean finite() {
        return Double.isFinite(s.p.x + s.p.y + s.p.z + s.v.x + s.v.y + s.v.z + s.q.x + s.q.y + s.q.z + s.q.w
                + s.w.x + s.w.y + s.w.z + s.omega);
    }
}
