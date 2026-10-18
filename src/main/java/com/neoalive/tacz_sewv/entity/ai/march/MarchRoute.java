package com.neoalive.tacz_sewv.entity.ai.march;

import java.util.Arrays;

/**
 * The line a march walks: a polyline in world XZ, addressed by arc length.
 *
 * <p>Everything a march lays is a point at some arc length on this line, which is what lets a column bend
 * round a hill like a road march instead of swinging as one rigid bar. Arc length runs from 0 at the first
 * point; a query past either end is extrapolated straight along the end leg (or along the starting heading
 * while there is no leg yet), so slots trailing the start of the route — the tail of a column that has only
 * just stepped off — still have somewhere to be.
 *
 * <p>Pure: headless-testable.
 */
public final class MarchRoute {

    private double[] xs = new double[8];
    private double[] zs = new double[8];
    /** Arc length at point i. */
    private double[] cum = new double[8];
    private int n;
    private final double startFx;
    private final double startFz;

    /** A route starting at {@code (x, z)} heading along {@code (fx, fz)}, normalised; degenerate faces north. */
    public MarchRoute(double x, double z, double fx, double fz) {
        double len = Math.sqrt(fx * fx + fz * fz);
        this.startFx = len < 1.0e-9 ? 0.0 : fx / len;
        this.startFz = len < 1.0e-9 ? -1.0 : fz / len;
        this.xs[0] = x;
        this.zs[0] = z;
        this.n = 1;
    }

    /** Add a point. One that would make a zero-length leg is ignored. */
    public void append(double x, double z) {
        double dx = x - this.xs[this.n - 1];
        double dz = z - this.zs[this.n - 1];
        double leg = Math.sqrt(dx * dx + dz * dz);
        if (leg < 1.0e-6) return;
        if (this.n == this.xs.length) {
            this.xs = Arrays.copyOf(this.xs, this.n * 2);
            this.zs = Arrays.copyOf(this.zs, this.n * 2);
            this.cum = Arrays.copyOf(this.cum, this.n * 2);
        }
        this.xs[this.n] = x;
        this.zs[this.n] = z;
        this.cum[this.n] = this.cum[this.n - 1] + leg;
        this.n++;
    }

    public int points() {
        return this.n;
    }

    public double length() {
        return this.cum[this.n - 1];
    }

    public double endX() {
        return this.xs[this.n - 1];
    }

    public double endZ() {
        return this.zs[this.n - 1];
    }

    /** {@code {x, z}} at arc length {@code s}. */
    public double[] pointAt(double s) {
        int leg = legAt(s);
        if (leg < 0) {
            // Before the start, or no leg at all yet: straight back along the start heading / first leg.
            double[] t = tangentAt(0.0);
            return new double[] {this.xs[0] + t[0] * s, this.zs[0] + t[1] * s};
        }
        double from = this.cum[leg];
        double legLen = this.cum[leg + 1] - from;
        double u = (s - from) / legLen; // > 1 on the last leg past the end: extrapolates
        return new double[] {
                this.xs[leg] + (this.xs[leg + 1] - this.xs[leg]) * u,
                this.zs[leg] + (this.zs[leg + 1] - this.zs[leg]) * u};
    }

    /** Unit {@code {fx, fz}} of the leg under arc length {@code s}. */
    public double[] tangentAt(double s) {
        if (this.n < 2) return new double[] {this.startFx, this.startFz};
        int leg = Math.max(0, legAt(s));
        double dx = this.xs[leg + 1] - this.xs[leg];
        double dz = this.zs[leg + 1] - this.zs[leg];
        double len = this.cum[leg + 1] - this.cum[leg];
        return new double[] {dx / len, dz / len};
    }

    /** Index of the leg containing {@code s}: the first or last leg past either end, -1 before 0 or with no leg. */
    private int legAt(double s) {
        if (this.n < 2 || s < 0.0) return -1;
        int lo = 0;
        int hi = this.n - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (this.cum[mid] <= s) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }
}
