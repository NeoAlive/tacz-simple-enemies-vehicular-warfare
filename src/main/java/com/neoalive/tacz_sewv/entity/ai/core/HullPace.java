package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;

import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.maneuver.GearChoice;

/**
 * Real top speeds per hull type, learned from the hulls themselves. The data rates are only a
 * prior: FCP hulls are Java subclasses that may drive differently from SBW's engine, and the rate
 * to blocks-per-tick factor {@link #K} is a calibration, not a read. A type is sampled only on a
 * steady straight run (one key held {@link #HOLD_TICKS}+ ticks, flat ground, speed settled), so a
 * march duty cycle or a hill never pollutes it. Server thread only.
 */
public final class HullPace {

    /** Data rate to blocks per tick, for a type not yet measured. Calibrated live. */
    public static final double K = 1.0;
    static final int HOLD_TICKS = 20;
    private static final double ALPHA = 0.1;
    private static final double STEADY_EPS = 0.002;
    private static final double FLAT_PITCH_DEG = 5.0;
    private static final double PIVOT_MAX_SPEED = 0.03;
    private static final int LOG_EVERY = 50;

    private static final ConcurrentHashMap<EntityType<?>, Rec> BY_TYPE = new ConcurrentHashMap<>();

    /** Measured averages for one hull type; NaN until the first sample. */
    public static final class Rec {
        final String name;
        volatile double vFwd = Double.NaN;
        volatile double vBack = Double.NaN;
        volatile double yawRate = Double.NaN;
        int samples;

        Rec(String name) {
            this.name = name;
        }

        public double measuredForward() {
            return this.vFwd;
        }

        public double measuredBackward() {
            return this.vBack;
        }

        public double measuredPivotDegPerTick() {
            return this.yawRate;
        }
    }

    /** Per-driver run state: how long the current key pattern has been held. */
    public static final class Sampler {
        private int fwdHeld;
        private int backHeld;
        private int pivotHeld;
        private double lastSpeed = Double.NaN;
        private float lastYaw;

        public void sample(VehicleEntity v, Rec rec, double steering) {
            if (rec == null) return;
            double speed = v.getDeltaMovement().horizontalDistance();
            float yaw = v.getYRot();
            boolean grounded = v.onGround() && !v.isInFluidType() && Math.abs(v.getXRot()) < FLAT_PITCH_DEG;
            boolean f = v.forwardInputDown();
            boolean b = v.backInputDown();
            boolean l = v.leftInputDown();
            boolean r = v.rightInputDown();
            boolean steady = !Double.isNaN(this.lastSpeed) && Math.abs(speed - this.lastSpeed) < STEADY_EPS;

            this.fwdHeld = grounded && f && !b && !l && !r ? this.fwdHeld + 1 : 0;
            this.backHeld = grounded && b && !f && !l && !r ? this.backHeld + 1 : 0;
            this.pivotHeld = grounded && (l ^ r) && !f && !b && speed < PIVOT_MAX_SPEED ? this.pivotHeld + 1 : 0;

            if (this.fwdHeld >= HOLD_TICKS && steady) {
                rec.vFwd = ema(rec.vFwd, speed);
                note(rec, steering);
            } else if (this.backHeld >= HOLD_TICKS && steady) {
                rec.vBack = ema(rec.vBack, speed);
                note(rec, steering);
            } else if (this.pivotHeld >= HOLD_TICKS) {
                rec.yawRate = ema(rec.yawRate, Math.abs(Mth.wrapDegrees(yaw - this.lastYaw)));
                note(rec, steering);
            }
            this.lastSpeed = speed;
            this.lastYaw = yaw;
        }
    }

    private HullPace() {}

    public static Rec of(EntityType<?> type) {
        return BY_TYPE.computeIfAbsent(type, t -> new Rec(EntityType.getKey(t).toString()));
    }

    /** Top speed ahead: measured, else the data rate scaled by {@link #K}. NaN if neither. */
    public static double forward(Rec rec, double fwdRate) {
        if (rec != null && rec.vFwd > 0.0) return rec.vFwd;
        return fwdRate > 0.0 ? fwdRate * K : Double.NaN;
    }

    /** Top speed astern: measured, else the forward speed scaled by the data ratio. */
    public static double backward(Rec rec, double fwdRate, double backRate) {
        if (rec != null && rec.vBack > 0.0) return rec.vBack;
        double fwd = forward(rec, fwdRate);
        return fwd > 0.0 && fwdRate > 0.0 && backRate > 0.0 ? fwd * backRate / fwdRate : Double.NaN;
    }

    private static double ema(double prev, double x) {
        return Double.isNaN(prev) ? x : prev + ALPHA * (x - prev);
    }

    private static void note(Rec rec, double steering) {
        if (rec.samples++ % LOG_EVERY != 0 || !SewvDiag.groundPathingVerbose()) return;
        SewvDiag.pathing("pace type={} vFwd={} vBack={} pivotDeg={} pivotModelDeg={} samples={}",
                rec.name, rec.vFwd, rec.vBack, rec.yawRate, GearChoice.pivotSteadyDegPerTick(steering), rec.samples);
    }
}
