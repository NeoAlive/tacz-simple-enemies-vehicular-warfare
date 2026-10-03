package com.neoalive.tacz_sewv.heli.avoid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.joml.Vector3dc;

import com.neoalive.tacz_sewv.heli.guidance.HeliReference;

/**
 * The tactical bias's look-ahead (plan section 4.9): the reference's predicted track for the next
 * {@link #HORIZON} seconds, swept with the hull's box, against terrain and other airframes. The
 * world reads are behind {@link Terrain}; everything else is pure.
 *
 * <p><b>Track.</b> Constant speed and constant turn rate from the raw reference (the turn rate is
 * the reference acceleration across the track), constant climb rate. The raw reference, never the
 * biased one: otherwise a climb that clears an obstacle would clear the demand and the bias would
 * settle back into it.
 *
 * <p><b>Airframes.</b> Each is a box moving at its own velocity, compared with the hull's box at the
 * same look-ahead time and inflated by {@link #CLEARANCE}. When two AI-flown hulls conflict only
 * one of them must deviate, or both would climb (or turn) into the same new conflict: the hull
 * with the LOWER entity id yields. An airframe that is not flown by our stack (a player's, a
 * parked hull, a wreck) never yields, so everyone avoids it. The hull's own target airframe is left
 * out by the caller (plan 4.9a: a run flies at its target on purpose); nothing else is.
 *
 * <p>Determinism: traffic is sorted by id; the scan order is fixed.
 */
public final class PathProbe {

    public static final double HORIZON = 3.0, STEP = 0.25, CLEARANCE = 4.0;
    private static final int SUB = 4;

    /** Top of whatever solid blocks the slab [yBottom, yTop] at column (x, z), or NaN when it is clear. */
    public interface Terrain {
        double top(double x, double z, double yBottom, double yTop);
    }

    /** Another airframe: its box now, its velocity, and whether our stack flies it (then it yields by id). */
    public record Traffic(int id, boolean yields, double minX, double minY, double minZ, double maxX, double maxY,
                          double maxZ, double vx, double vy, double vz) {}

    /**
     * @param blocked  something lies on the track within the horizon
     * @param range    arc length to the first blocking sample
     * @param yTop     top of the obstacle there
     * @param yRef     reference altitude there
     * @param closure  hull speed along the track
     * @param side     +1 left, -1 right: the clear side, else the tie-break
     * @param lateral  lateral offset that clears the obstacle
     * @param airframe the obstruction is another airframe
     */
    public record Result(boolean blocked, double range, double yTop, double yRef, double closure, int side,
                         double lateral, boolean airframe) {
        public static final Result CLEAR = new Result(false, Double.POSITIVE_INFINITY, 0, 0, 0, 1, 0, false);
    }

    private PathProbe() {}

    public static Result probe(HeliReference r, Vector3dc hullV, double halfWidth, double height, Terrain terrain,
                               List<Traffic> traffic, int selfId, int tieSide) {
        double vx = r.v().x(), vz = r.v().z(), vh = Math.sqrt(vx * vx + vz * vz);
        if (vh < 1.0) return Result.CLEAR;
        List<Traffic> others = new ArrayList<>(traffic);
        others.sort(Comparator.comparingInt(Traffic::id));
        others.removeIf(o -> o.yields() && o.id() < selfId);

        double chiDot = (vz * r.a().x() - vx * r.a().z()) / (vh * vh);
        double ux = vx / vh, uz = vz / vh;
        double x = r.p().x(), y = r.p().y(), z = r.p().z(), vy = r.v().y();
        double dt = STEP / SUB, lateral = 2.0 * halfWidth + CLEARANCE + 2.0;
        double closure = Math.max(0.0, hullV.x() * ux + hullV.z() * uz);
        for (int i = 1; i * STEP <= HORIZON + 1e-9; i++) {
            for (int k = 0; k < SUB; k++) {
                x += ux * vh * dt;
                z += uz * vh * dt;
                y += vy * dt;
                double a = chiDot * dt, c = StrictMath.cos(a), s = StrictMath.sin(a);
                double nx = ux * c + uz * s;
                uz = -ux * s + uz * c;
                ux = nx;
            }
            double tau = i * STEP;
            double[] hit = blockedTop(x, y, z, tau, halfWidth, height, terrain, others);
            if (Double.isNaN(hit[0])) continue;
            // Left normal of the track here: n_L(u) = (u.z, -u.x).
            double lx = uz * lateral, lz = -ux * lateral;
            boolean leftClear = Double.isNaN(blockedTop(x + lx, y, z + lz, tau, halfWidth, height, terrain, others)[0]);
            boolean rightClear = Double.isNaN(blockedTop(x - lx, y, z - lz, tau, halfWidth, height, terrain, others)[0]);
            int side = leftClear == rightClear ? tieSide : (leftClear ? 1 : -1);
            return new Result(true, vh * tau, hit[0], y, closure, side, lateral, hit[1] != 0.0);
        }
        return Result.CLEAR;
    }

    /**
     * The obstruction for a hull box at (x, y, z) at look-ahead time tau: {top, 1 if an airframe is
     * involved else 0}; top is NaN when clear. Terrain is sampled at the box's corners and centre.
     */
    private static double[] blockedTop(double x, double y, double z, double tau, double hw, double h, Terrain terrain,
                                       List<Traffic> others) {
        double top = Double.NaN, air = 0.0;
        for (double off : new double[] {-hw, 0.0, hw}) {
            for (double offZ : new double[] {-hw, 0.0, hw}) {
                double t = terrain.top(x + off, z + offZ, y - 1.0, y + h + 1.0);
                if (!Double.isNaN(t) && !(t <= top)) top = t;
            }
        }
        double x0 = x - hw - CLEARANCE, x1 = x + hw + CLEARANCE, y0 = y - CLEARANCE, y1 = y + h + CLEARANCE;
        double z0 = z - hw - CLEARANCE, z1 = z + hw + CLEARANCE;
        for (Traffic o : others) {
            double ox = o.vx() * tau, oy = o.vy() * tau, oz = o.vz() * tau;
            if (x1 >= o.minX() + ox && x0 <= o.maxX() + ox && y1 >= o.minY() + oy && y0 <= o.maxY() + oy
                    && z1 >= o.minZ() + oz && z0 <= o.maxZ() + oz) {
                double t = o.maxY() + oy;
                if (!(t <= top)) top = t;
                air = 1.0;
            }
        }
        return new double[] {top, air};
    }
}
