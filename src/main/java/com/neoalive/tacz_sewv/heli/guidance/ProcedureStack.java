package com.neoalive.tacz_sewv.heli.guidance;

import java.util.function.DoubleFunction;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * Runs the active procedure and hands over between procedures with the C2 blend of
 * {@link ReferenceBlend} (plan sections 4.7-4.8). Phase 2 holds one slot; the DetNav top slot
 * arrives with the attack procedures.
 *
 * <ul>
 * <li>On a switch the outgoing procedure keeps being sampled for the blend window (procedures are
 *     defined past completion). A switch while already blending freezes the current blended
 *     sample as a constant-acceleration extrapolation, so the nesting depth never grows.</li>
 * <li>{@link #complete} is never true on the tick a procedure began (minimum life one tick).</li>
 * <li>A snapshot older than {@link #STALE_SECONDS} means the pilot goal stopped feeding guidance:
 *     the stack switches itself to {@link ProcedureId#SAFETY_HOLD}. That is a failure path; the
 *     goal refreshes every tick.</li>
 * </ul>
 */
public final class ProcedureStack {

    public static final double STALE_SECONDS = 1.0;
    private static final double TB_MIN = 0.75, TB_MAX = 4.0;

    private final Airframe af;
    /** Largest acceleration a hand-over may add: 0.3 g. */
    private final double blendAccel;
    private HeliProcedure active;
    private long beganTick = Long.MIN_VALUE;
    private DoubleFunction<HeliReference> outgoing;
    private double blendStart, blendLength;
    private HeliReference last;

    public ProcedureStack(Airframe af, double g) {
        this.af = af;
        this.blendAccel = 0.3 * g;
    }

    public ProcedureId activeId() {
        return active == null ? null : active.id();
    }

    public HeliProcedure active() {
        return active;
    }

    /** Switch to {@code id} (falling back to HOVER_HOLD when it cannot begin), blending from the current reference. */
    public void switchTo(ProcedureId id, HeliState s, Situation sit, double t, long tick) {
        HeliProcedure next = Procedures.create(id, af);
        if (!next.canBegin(s, sit)) next = Procedures.create(ProcedureId.HOVER_HOLD, af);
        if (active != null) {
            HeliReference now = refAt(t, s, sit);
            outgoing = blending(t) ? extrapolation(now) : procedureFeed(active, s, sit);
            next.begin(s, sit, t);
            HeliReference in = next.refAt(t, s, sit);
            double dp = new Vector3d(in.p()).distance(now.p()), dv = new Vector3d(in.v()).distance(now.v());
            blendStart = t;
            blendLength = ReferenceBlend.window(dp, dv, TB_MIN, TB_MAX, 0.5 * af.vMaxH, blendAccel);
        } else {
            next.begin(s, sit, t);
            outgoing = null;
        }
        active = next;
        beganTick = tick;
    }

    private static DoubleFunction<HeliReference> procedureFeed(HeliProcedure p, HeliState s, Situation sit) {
        return tt -> p.refAt(tt, s, sit);
    }

    private static DoubleFunction<HeliReference> extrapolation(HeliReference r) {
        Vector3d p0 = new Vector3d(r.p()), v0 = new Vector3d(r.v()), a0 = new Vector3d(r.a());
        double t0 = r.t();
        return tt -> {
            double d = tt - t0;
            return new HeliReference(tt, new Vector3d(p0).fma(d, v0).fma(0.5 * d * d, a0),
                    new Vector3d(v0).fma(d, a0), a0, r.yaw() + r.yawRate() * d, r.yawRate());
        };
    }

    private boolean blending(double t) {
        return outgoing != null && t - blendStart < blendLength;
    }

    /** The reference at {@code t}, blended while a hand-over is in progress. */
    public HeliReference refAt(double t, HeliState s, Situation sit) {
        HeliReference in = active.refAt(t, s, sit);
        last = blending(t) ? ReferenceBlend.blend(outgoing.apply(t), in, blendStart, blendLength, t) : in;
        if (!blending(t)) outgoing = null;
        return last;
    }

    /** True when the active procedure reports completion, never on the tick it began. */
    public boolean complete(HeliState s, Situation sit, double t, long tick) {
        return active != null && tick > beganTick && active.isComplete(s, sit, t);
    }

    public static boolean stale(Situation sit, double t) {
        return sit == null || t - sit.time > STALE_SECONDS;
    }

    public HeliControl.EngineCmd engine() {
        return active == null ? HeliControl.EngineCmd.HOLD : active.engine();
    }

    public boolean groundBarrier(double t) {
        return active == null || active.groundBarrier(t);
    }

    public boolean flying() {
        return active == null || active.flying();
    }
}
