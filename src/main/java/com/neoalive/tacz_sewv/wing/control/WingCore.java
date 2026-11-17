package com.neoalive.tacz_sewv.wing.control;

import com.neoalive.tacz_sewv.wing.avoid.TerrainGuard;
import com.neoalive.tacz_sewv.wing.guidance.WingModeSelector;
import com.neoalive.tacz_sewv.wing.guidance.WingProcedureStack;
import com.neoalive.tacz_sewv.wing.guidance.WingReference;
import com.neoalive.tacz_sewv.wing.guidance.WingSituation;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingControl;
import com.neoalive.tacz_sewv.wing.physics.WingEnv;
import com.neoalive.tacz_sewv.wing.physics.WingPhysics;
import com.neoalive.tacz_sewv.wing.physics.WingState;

/**
 * One hull's fixed-wing flight stack for one game tick, with no world access: the in-game runtime
 * and the headless self-checks both run exactly this, the counterpart of {@code FlightCore}.
 *
 * <p>Per tick: {@link #guide} (20 Hz, the transition rule), then {@link #fly}: six sub-steps of
 * procedure reference, terrain guard, controller and physics. {@link #integrate} runs the same
 * controller and physics against any {@link ReferenceSource} (the controller checks use it).
 */
public final class WingCore {

    /** Where the reference comes from each sub-step: a procedure stack in game, a test path headless. */
    @FunctionalInterface
    public interface ReferenceSource {
        /** The reference at sim time {@code t}; null lets go of the stick (sticks neutral, throttle held). */
        WingReference at(double t, WingState s);
    }

    public final WingAirframe af;
    public final double g;
    public final WingPhysics physics;
    public final WingController ctl;
    public final WingState s = new WingState();
    public final WingControl u = new WingControl();
    public final WingProcedureStack stack;
    public final TerrainGuard guard;
    /** The last sub-step's reference (after the terrain guard). */
    public WingReference lastRef;

    public WingCore(WingAirframe af, double g, double rho, double energyCostRate) {
        this.af = af;
        this.g = g;
        this.physics = new WingPhysics(af, energyCostRate);
        this.ctl = new WingController(af, g, rho);
        this.stack = new WingProcedureStack(af, g);
        this.guard = new TerrainGuard(af, g, rho);
    }

    /** 20 Hz: the selector's choice through the transition rule. */
    public void guide(WingModeSelector.Choice choice, WingSituation sit, double t0, long tick) {
        stack.request(choice, s, sit, t0, tick);
    }

    /** Six sub-steps of the procedure stack, guarded against terrain, through controller and physics. */
    public void fly(WingEnv env, WingSituation sit, long tick) {
        u.gearDown = stack.gearDown();
        u.flap = stack.flap();
        u.brakeCmd = stack.brake();
        integrate(env, (t, st) -> guard.apply(stack.refAt(t, st, sit), st, sit), tick);
    }

    /** Six sub-steps of reference, controller and physics. */
    public void integrate(WingEnv env, ReferenceSource source, long tick) {
        for (int k = 0; k < 6; k++) {
            double t = (6.0 * tick + k) / 120.0;
            WingReference r = source.at(t, s);
            lastRef = r;
            if (r != null) {
                ctl.step(r, s, env, u);
            } else {
                u.neutral();
                ctl.reset();
            }
            physics.step(s, u, env);
        }
    }
}
