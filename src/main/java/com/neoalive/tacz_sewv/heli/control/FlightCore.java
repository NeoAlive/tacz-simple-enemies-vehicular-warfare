package com.neoalive.tacz_sewv.heli.control;

import java.util.function.Consumer;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.avoid.AvoidBias;
import com.neoalive.tacz_sewv.heli.avoid.AvoidForce;
import com.neoalive.tacz_sewv.heli.avoid.ObstacleSet;
import com.neoalive.tacz_sewv.heli.avoid.PathProbe;
import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.guidance.ModeSelector;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureStack;
import com.neoalive.tacz_sewv.heli.guidance.Situation;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliEnv;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * One hull's flight stack for one game tick, with no world access: the in-game runtime and the
 * headless self-checks both run exactly this, so the transition rule, the bias and the barrier
 * they are checked with are the ones that fly.
 *
 * <p>Per tick: {@link #guide} (20 Hz, the transition rule), {@link #avoidance} (20 Hz, barrier
 * bands and the bias demand), then {@link #integrate}: six sub-steps of reference, bias, barrier
 * force, controller and physics.
 */
public final class FlightCore {

    public final Airframe af;
    public final double g;
    public final HeliPhysics physics;
    public final HeliController ctl;
    public final HeliState s = new HeliState();
    public final HeliControl u = new HeliControl();
    public final ProcedureStack stack;
    public final AvoidBias bias;
    /** Null: no barrier (bare physics checks). */
    public AvoidForce barrier;
    public ObstacleSet obstacles = ObstacleSet.empty();
    public final Vector3d avoid = new Vector3d();
    /** The last sub-step's (biased) reference. */
    public HeliReference lastRef;

    public FlightCore(Airframe af, double g, double rho, double fuelPerJoule, AvoidForce barrier) {
        this.af = af;
        this.g = g;
        this.physics = new HeliPhysics(af, fuelPerJoule);
        this.ctl = new HeliController(af, g, rho);
        this.stack = new ProcedureStack(af, g);
        this.bias = new AvoidBias(af.vClimb);
        this.barrier = barrier;
    }

    /** 20 Hz: the selector's choice through the transition rule. */
    public void guide(ModeSelector.Choice choice, Situation sit, double t0, long tick) {
        stack.request(choice, s, sit, t0, tick);
    }

    /**
     * 20 Hz: refresh the barrier's bands and the bias demand. The bias is off (and decays smoothly)
     * while the controller is let go or the procedure is putting the hull on the ground.
     */
    public void avoidance(ObstacleSet set, PathProbe.Result path, double standoff, double t0) {
        obstacles = set;
        if (barrier != null) barrier.update(set, s.p, s.v);
        if (path != null && path.blocked() && stack.flying() && stack.groundBarrier(t0)) {
            bias.demand(path.range(), path.yTop(), path.yRef(), path.closure(), path.side(), path.lateral(), standoff);
        } else {
            bias.demand(Double.POSITIVE_INFINITY, 0, 0, 0, 1, 0, 0);
        }
    }

    /** The raw (unbiased) reference at {@code t}: what the path probe extrapolates. */
    public HeliReference rawRef(double t, Situation sit) {
        return stack.refAt(t, s, sit);
    }

    /** Six sub-steps; {@code sink} (nullable) sees every biased reference sample. */
    public void integrate(HeliEnv env, Situation sit, long tick, Consumer<HeliReference> sink) {
        for (int k = 0; k < 6; k++) {
            double t = (6.0 * tick + k) / 120.0;
            bias.advance(HeliPhysics.H);
            HeliReference r = bias.apply(stack.refAt(t, s, sit));
            lastRef = r;
            if (sink != null) sink.accept(r);
            u.engine = stack.engine();
            if (barrier != null) barrier.force(obstacles, s.p, s.v, af.mass, avoid);
            else avoid.zero();
            if (stack.flying()) {
                ctl.step(r, s, env, avoid, u);
            } else {
                u.collective = 0.0;
                u.cLon = u.cLat = u.pedal = 0.0;
                ctl.reset();
            }
            physics.step(s, u, env, avoid);
        }
    }
}
