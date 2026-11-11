package com.neoalive.tacz_sewv.fpas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

/**
 * Where to shoot a target from: a ring of candidates around it at the weapon's range, nearest to the
 * shooter first, pushed apart from spots other allies already took. The caller supplies the world
 * test (footing and line of sight); this class only orders and filters, so it is pure.
 */
public final class FiringSpots {

    public static final int BEARINGS = 16;
    /** Ring radii as fractions of the weapon range: just inside it first, then a closer fallback. */
    private static final double[] RINGS = {0.85, 0.55};
    /** Two spots closer than this compete for the same ground. */
    public static final double SPACING = 6.0;
    /** Travel cost added per already-claimed spot inside {@link #SPACING}. */
    private static final double CROWD_COST = 40.0;

    private FiringSpots() {}

    /** Candidates around (tx, tz), cheapest first: travel from (ux, uz) plus crowding. */
    public static List<double[]> rank(double ux, double uz, double tx, double tz, double range,
                                      List<double[]> claimed) {
        List<double[]> out = new ArrayList<>(BEARINGS * RINGS.length);
        for (double ring : RINGS) {
            double r = range * ring;
            for (int i = 0; i < BEARINGS; i++) {
                double a = Math.PI * 2 * i / BEARINGS;
                out.add(new double[] {tx + Math.cos(a) * r, tz + Math.sin(a) * r});
            }
        }
        out.sort(Comparator.comparingDouble(p -> cost(p, ux, uz, claimed)));
        return out;
    }

    static double cost(double[] p, double ux, double uz, List<double[]> claimed) {
        double c = Math.hypot(p[0] - ux, p[1] - uz);
        for (double[] q : claimed) {
            if (Math.hypot(p[0] - q[0], p[1] - q[1]) < SPACING) c += CROWD_COST;
        }
        return c;
    }

    /** The first ranked candidate {@code usable} accepts, or null when none does. */
    @Nullable
    public static double[] pick(double ux, double uz, double tx, double tz, double range, List<double[]> claimed,
                                Predicate<double[]> usable) {
        for (double[] p : rank(ux, uz, tx, tz, range, claimed)) {
            if (usable.test(p)) return p;
        }
        return null;
    }

    /** A walking leg toward (sx, sz): at most {@code maxLeg} from (ux, uz), the spot itself when closer. */
    public static double[] leg(double ux, double uz, double sx, double sz, double maxLeg) {
        double dx = sx - ux, dz = sz - uz;
        double d = Math.hypot(dx, dz);
        if (d <= maxLeg) return new double[] {sx, sz};
        return new double[] {ux + dx / d * maxLeg, uz + dz / d * maxLeg};
    }
}
