package com.neoalive.tacz_sewv.wing.guidance;

import com.neoalive.tacz_sewv.heli.guidance.ModeSelector.Precedence;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingState;

/**
 * Runs the active fixed-wing procedure and hands over between procedures: the helicopter
 * {@code ProcedureStack}'s transition rule, without its reference blending.
 *
 * <p><b>Transition rule</b> (20 Hz, {@link #request}): the selector's choice replaces the active
 * procedure when it differs and either outranks it, the active one has completed, or the active one
 * is not {@linkplain WingProcedure#sticky sticky} (attack runs and the break are). A procedure that cannot begin falls back to {@link WingProcedureId#ORBIT},
 * which always can.
 *
 * <p><b>No blending, on purpose.</b> The helicopter blends because its controller tracks position
 * and velocity directly, so a reference step is an acceleration demand. The fixed-wing outer loops
 * are rate-limited by construction (L1 caps lateral acceleration through the bank limit, TECS
 * clamps the climb rate and the speed command), so a reference step is already a well-behaved
 * command, and unscheduled references have no along-track position to jump anyway.
 *
 * <p>A snapshot older than {@link #STALE_SECONDS} means the pilot goal stopped feeding guidance; the
 * runtime then switches to {@link WingProcedureId#SAFETY_HOLD}.
 */
public final class WingProcedureStack {

    public static final double STALE_SECONDS = 1.0;

    private final WingAirframe af;
    private final double g;
    private WingProcedure active;
    private Precedence activeClass = Precedence.FREENAV;
    private long beganTick = Long.MIN_VALUE;
    private double beganAt = Double.NaN;

    public WingProcedureStack(WingAirframe af, double g) {
        this.af = af;
        this.g = g;
    }

    public WingProcedureId activeId() {
        return active == null ? null : active.id();
    }

    public WingProcedure active() {
        return active;
    }

    public Precedence activeClass() {
        return activeClass;
    }

    public double beganAt() {
        return beganAt;
    }

    /** Apply the transition rule to the selector's choice. */
    public void request(WingModeSelector.Choice choice, WingState s, WingSituation sit, double t, long tick) {
        if (active == null) {
            install(resolve(choice.id(), s, sit), choice.precedence(), s, sit, t, tick);
            return;
        }
        boolean done = complete(s, sit, t, tick);
        boolean sticky = active.sticky();
        boolean differs = choice.id() != active.id();
        boolean outranks = choice.precedence().ordinal() > activeClass.ordinal();
        if (differs && (outranks || done || !sticky)) {
            install(resolve(choice.id(), s, sit), choice.precedence(), s, sit, t, tick);
        } else if (!differs) {
            activeClass = choice.precedence();
        }
    }

    /** Switch unconditionally (the runtime's safety hold); FreeNav class, so any choice replaces it. */
    public void switchTo(WingProcedureId id, WingState s, WingSituation sit, double t, long tick) {
        install(resolve(id, s, sit), Precedence.FREENAV, s, sit, t, tick);
    }

    private WingProcedure resolve(WingProcedureId id, WingState s, WingSituation sit) {
        WingProcedure p = WingProcedures.create(id, af, g);
        return p.canBegin(s, sit) ? p : WingProcedures.create(WingProcedureId.ORBIT, af, g);
    }

    private void install(WingProcedure next, Precedence cls, WingState s, WingSituation sit, double t, long tick) {
        next.begin(s, sit, t);
        active = next;
        activeClass = cls;
        beganTick = tick;
        beganAt = t;
    }

    public WingReference refAt(double t, WingState s, WingSituation sit) {
        return active.refAt(t, s, sit);
    }

    /** True when the active procedure reports completion, never on the tick it began. */
    public boolean complete(WingState s, WingSituation sit, double t, long tick) {
        return active != null && tick > beganTick && active.isComplete(s, sit, t);
    }

    public static boolean stale(WingSituation sit, double t) {
        return sit == null || t - sit.time > STALE_SECONDS;
    }

    public boolean fireWindow(WingState s, WingSituation sit) {
        return active == null || active.fireWindow(s, sit);
    }

    public boolean gearDown() {
        return active != null && active.gearDown();
    }

    public double flap() {
        return active == null ? 0.0 : active.flap();
    }

    public double brake() {
        return active == null ? 0.0 : active.brake();
    }
}
