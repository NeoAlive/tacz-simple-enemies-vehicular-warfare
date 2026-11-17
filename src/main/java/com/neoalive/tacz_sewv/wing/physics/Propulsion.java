package com.neoalive.tacz_sewv.wing.physics;

/**
 * Available thrust. A jet's thrust is nearly flat with speed (an optional linear ram term); a
 * propeller is power-limited, {@code T = eta P / V}, capped at its static thrust so it stays
 * finite at rest.
 */
public final class Propulsion {

    private Propulsion() {}

    /** Thrust at throttle {@code thr} (actual, spooled) and airspeed {@code v}, N. */
    public static double thrust(WingAirframe af, double thr, double v) {
        if (thr <= 0.0) return 0.0;
        if (af.prop) {
            return thr * Math.min(af.staticThrust, af.propEfficiency * af.power / Math.max(v, 1.0));
        }
        return thr * af.staticThrust * Math.max(0.0, 1.0 + af.thrustSpeedK * v);
    }

    /** Full-throttle thrust at {@code v}. */
    public static double maxThrust(WingAirframe af, double v) {
        return thrust(af, 1.0, v);
    }
}
