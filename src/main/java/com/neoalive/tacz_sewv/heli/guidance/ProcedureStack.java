package com.neoalive.tacz_sewv.heli.guidance;

import java.util.function.DoubleFunction;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * Runs the active procedure and hands over between procedures (plan sections 4.7, 4.8, 4.11).
 *
 * <p><b>Transition rule</b> (20 Hz, {@link #request}). The selector's choice P replaces the active
 * procedure A when P differs and either P's precedence class is higher, A has completed, or A is not
 * an attack procedure. So an attack procedure (DetNav) runs to completion unless something of a
 * higher class (survival, an order) pre-empts it, which is what stops FireLoop and FireStill
 * chattering; every other procedure follows the selector at once (the selector already carries
 * hysteresis). On completion an attack or survival procedure whose id is chosen again is begun
 * afresh (the next pass of a run, the next evade leg); a completed FreeNav procedure the selector
 * still wants simply continues its last segment.
 *
 * <p><b>Fallbacks</b> when a procedure cannot begin: FIRE_STILL to FIRE_LOOP to FIRE_RUN to
 * HOVER_HOLD; FIRE_LOOP outside its entry band first tries the tangent-point approach; anything
 * else to HOVER_HOLD, which is always feasible. A resolved procedure of the same kind as the active
 * one does not restart it.
 *
 * <p><b>Engagements.</b> Each completed attack procedure (not the approach) counts one engage
 * cycle; the count resets on a target change. A FireRun's exit axis is handed to the next FireRun
 * against the same target, and a FireStill's station to the next FireStill, so a re-begin after the
 * dwell continues on the same station instead of re-solving it. A target hand-off during an attack procedure calls
 * {@link HeliProcedure#retarget}; if the procedure cannot follow, it is begun afresh.
 *
 * <p>Blending: on a switch the outgoing procedure keeps being sampled for the blend window
 * (procedures are defined past completion). A switch while already blending freezes the current
 * blended sample as a constant-acceleration extrapolation, so the nesting depth never grows.
 * {@link #complete} is never true on the tick a procedure began (minimum life one tick). A snapshot
 * older than {@link #STALE_SECONDS} means the pilot goal stopped feeding guidance; the runtime then
 * switches to {@link ProcedureId#SAFETY_HOLD}.
 */
public final class ProcedureStack {

    public static final double STALE_SECONDS = 1.0;
    private static final double TB_MIN = 0.75, TB_MAX = 4.0;

    private final Airframe af;
    private final double g;
    /** Largest acceleration a hand-over may add: 0.3 g. */
    private final double blendAccel;
    private HeliProcedure active;
    private ModeSelector.Precedence activeClass = ModeSelector.Precedence.FREENAV;
    private int activeTarget = -1;
    private long beganTick = Long.MIN_VALUE;
    private double beganAt = Double.NaN;
    private DoubleFunction<HeliReference> outgoing;
    private double blendStart, blendLength;
    private HeliReference last;
    /** The snapshot the active procedure was last sampled with: an outgoing procedure keeps it. */
    private Situation lastSit;
    private int engageCycle, lastTarget = -1;
    private double[] runAxis, stillStation;

    public ProcedureStack(Airframe af, double g) {
        this.af = af;
        this.g = g;
        this.blendAccel = 0.3 * g;
    }

    public ProcedureId activeId() {
        return active == null ? null : active.id();
    }

    public HeliProcedure active() {
        return active;
    }

    public ModeSelector.Precedence activeClass() {
        return activeClass;
    }

    /** Sim time the active procedure began (re-begins included). */
    public double beganAt() {
        return beganAt;
    }

    public int engageCycle() {
        return engageCycle;
    }

    /** Apply the transition rule to the selector's choice. */
    public void request(ModeSelector.Choice choice, HeliState s, Situation sit, double t, long tick) {
        if (sit.targetId != lastTarget) {
            lastTarget = sit.targetId;
            engageCycle = 0;
            runAxis = null;
            stillStation = null;
        }
        if (active == null) {
            HeliProcedure first = resolve(choice.id(), s, sit);
            install(first, classOf(choice, first), s, sit, t, tick);
            return;
        }
        boolean done = complete(s, sit, t, tick);
        boolean attack = activeClass == ModeSelector.Precedence.ATTACK;
        if (done && attack && !(active instanceof Procedures.LoopApproach)) {
            engageCycle++;
            if (active instanceof Procedures.FireRun run) runAxis = run.exitAxis();
            if (active instanceof Procedures.FireStill still) stillStation = still.station();
        }
        if (!done && attack && sit.targetValid && sit.targetId != activeTarget) {
            activeTarget = sit.targetId;
            if (!active.retarget(sit, t)) {
                HeliProcedure again = resolve(active.id(), s, sit);
                install(again, classOf(new ModeSelector.Choice(active.id(), activeClass), again), s, sit, t, tick);
                return;
            }
        }
        boolean differs = choice.id() != active.id();
        boolean outranks = choice.precedence().ordinal() > activeClass.ordinal();
        boolean rebegin = done && !differs && (attack || activeClass == ModeSelector.Precedence.SURVIVAL);
        if ((differs && (outranks || done || !attack)) || rebegin) {
            HeliProcedure next = resolve(choice.id(), s, sit);
            if (!done && next.getClass() == active.getClass() && next.id() == active.id()) {
                activeClass = classOf(choice, next);
                return;
            }
            install(next, classOf(choice, next), s, sit, t, tick);
        } else if (!differs) {
            activeClass = choice.precedence();
        }
    }

    /**
     * The precedence a resolved procedure runs under: the choice's, unless a fallback left the
     * chosen family. A hover standing in for an attack that could not begin is FreeNav: it never
     * completes, so under the attack class it would hold the hull until something outranked it.
     */
    private static ModeSelector.Precedence classOf(ModeSelector.Choice choice, HeliProcedure p) {
        boolean sameFamily = p.id() == choice.id() || p instanceof Procedures.LoopApproach
                || (isAttack(choice.id()) && isAttack(p.id()));
        return sameFamily ? choice.precedence() : ModeSelector.Precedence.FREENAV;
    }

    private static boolean isAttack(ProcedureId id) {
        return id == ProcedureId.FIRE_STILL || id == ProcedureId.FIRE_LOOP || id == ProcedureId.FIRE_RUN;
    }

    /** Switch unconditionally (the runtime's safety hold); FreeNav class, so any choice replaces it. */
    public void switchTo(ProcedureId id, HeliState s, Situation sit, double t, long tick) {
        install(resolve(id, s, sit), ModeSelector.Precedence.FREENAV, s, sit, t, tick);
    }

    private HeliProcedure resolve(ProcedureId id, HeliState s, Situation sit) {
        for (ProcedureId k = id; ; k = fallback(k)) {
            HeliProcedure p = Procedures.create(k, af, g);
            if (p.canBegin(s, sit)) return p;
            if (k == ProcedureId.FIRE_LOOP) {
                HeliProcedure approach = new Procedures.LoopApproach(af, g);
                if (approach.canBegin(s, sit)) return approach;
            }
        }
    }

    private static ProcedureId fallback(ProcedureId id) {
        return switch (id) {
            case FIRE_STILL -> ProcedureId.FIRE_LOOP;
            case FIRE_LOOP -> ProcedureId.FIRE_RUN;
            default -> ProcedureId.HOVER_HOLD;
        };
    }

    private void install(HeliProcedure next, ModeSelector.Precedence cls, HeliState s, Situation sit, double t, long tick) {
        if (next instanceof Procedures.FireRun run) run.hint(runAxis);
        if (next instanceof Procedures.FireStill still) still.hint(stillStation);
        if (active != null) {
            Situation old = lastSit != null ? lastSit : sit;
            HeliReference now = refAt(t, s, old);
            // The outgoing procedure keeps flying the snapshot it knew: the new one may not carry its
            // fields at all (an attack snapshot has no hold point or destination).
            outgoing = blending(t) ? extrapolation(now) : procedureFeed(active, s, old);
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
        activeClass = cls;
        activeTarget = sit.targetId;
        beganTick = tick;
        beganAt = t;
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
        lastSit = sit;
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

    public FirePhase firePhase() {
        return active == null ? FirePhase.NONE : active.firePhase();
    }

    public boolean fireWindow(HeliState s, Situation sit) {
        return active == null || active.fireWindow(s, sit);
    }

    public double[] lookahead() {
        return active == null ? null : active.lookahead();
    }
}
