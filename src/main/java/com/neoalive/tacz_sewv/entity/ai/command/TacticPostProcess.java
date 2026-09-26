package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;

import javax.annotation.Nullable;

/**
 * Group tactics applied to a play's roles after selection, in {@code CommandCoordinator} order: leapfrog,
 * firing line, commander back, cover points. Pure — positions and the exposure lookup are passed in — so each is
 * headless-testable. Roles in, roles out; unchanged input comes back as the same instance.
 */
final class TacticPostProcess {

    /** Cover snap sample radii (× scale by the caller). */
    static final double COVER_NEAR = 8.0;
    static final double COVER_FAR = 16.0;
    /** A candidate must beat the original point's exposure by this much to move it. */
    private static final double COVER_MIN_GAIN = 0.05;

    private static final Set<Assignment.Role> HOLDING =
            EnumSet.of(Assignment.Role.BASE_OF_FIRE, Assignment.Role.OVERWATCH, Assignment.Role.HOLD);

    private TacticPostProcess() {}

    /**
     * Leapfrog (bounding advance / fighting withdrawal): once every mover of the PREVIOUS pass stands on its point,
     * movers and overwatch swap. Destinations are re-taken from {@code fresh} by role, so the new movers get the
     * next bound (points are relative to the current centroid). {@code previous} must be the same play's committed,
     * post-processed roles; anything else leaves {@code roles} alone.
     */
    static Roles leapfrog(PlayId play, @Nullable Roles previous, Roles roles, Roles fresh,
                          GroupSnapshot g, double arrive) {
        Assignment.Role mover = switch (play) {
            case BOUNDING_OVERWATCH_ADVANCE -> Assignment.Role.MANEUVER;
            case FIGHTING_WITHDRAWAL -> Assignment.Role.WITHDRAW;
            default -> null;
        };
        if (mover == null || previous == null || roles == null) return roles;
        int movers = 0;
        for (Assignment a : previous.assignments) {
            if (a.role != mover) continue;
            int i = indexOf(g, a.unitId);
            if (i < 0) continue;
            movers++;
            if (Math.hypot(g.x[i] - a.destX, g.z[i] - a.destZ) > arrive) return roles;
        }
        if (movers == 0 || roles.count(Assignment.Role.OVERWATCH) == 0) return roles;

        Assignment moverTemplate = firstWith(fresh, mover);
        Assignment overwatchTemplate = firstWith(fresh, Assignment.Role.OVERWATCH);
        if (moverTemplate == null || overwatchTemplate == null) return roles;
        Assignment[] out = new Assignment[roles.assignments.length];
        for (int k = 0; k < out.length; k++) {
            Assignment a = roles.assignments[k];
            Assignment t = a.role == mover ? overwatchTemplate
                    : a.role == Assignment.Role.OVERWATCH ? moverTemplate : null;
            out[k] = t == null ? a : new Assignment(a.unitId, t.role, a.priorityTargetId, null, t.destX, t.destZ);
        }
        return new Roles(out);
    }

    /**
     * Firing line: base-of-fire and overwatch members that share one point are spread across the axis through
     * it, {@code spacing} apart, in lateral order — instead of all converging on the same spot.
     */
    static Roles firingLine(BattleField bf, Roles roles, GroupSnapshot g, double spacing) {
        if (roles == null) return null;
        Assignment[] out = roles.assignments.clone();
        boolean changed = false;
        for (Assignment.Role role : new Assignment.Role[] {Assignment.Role.BASE_OF_FIRE, Assignment.Role.OVERWATCH}) {
            List<Integer> idx = new ArrayList<>();
            for (int k = 0; k < out.length; k++) {
                if (out[k].role == role && Double.isFinite(out[k].destX) && indexOf(g, out[k].unitId) >= 0) {
                    idx.add(k);
                }
            }
            if (idx.size() < 2) continue;
            double leftX = -bf.axisZ;
            double leftZ = bf.axisX;
            idx.sort((p, q) -> {
                int ip = indexOf(g, out[p].unitId);
                int iq = indexOf(g, out[q].unitId);
                int c = Double.compare(g.x[ip] * leftX + g.z[ip] * leftZ, g.x[iq] * leftX + g.z[iq] * leftZ);
                return c != 0 ? c : Integer.compare(out[p].unitId, out[q].unitId);
            });
            double cx = out[idx.get(0)].destX;
            double cz = out[idx.get(0)].destZ;
            int n = idx.size();
            for (int s = 0; s < n; s++) {
                Assignment a = out[idx.get(s)];
                double offset = (s - (n - 1) / 2.0) * spacing;
                out[idx.get(s)] = new Assignment(a.unitId, a.role, a.priorityTargetId, a.flankSide,
                        cx + leftX * offset, cz + leftZ * offset);
            }
            changed = true;
        }
        return changed ? new Roles(out) : roles;
    }

    /**
     * Commander back: an elected commander never leads a flank, a bound or a withdrawal — it swaps role, side and
     * destination with the nearest member holding a base-of-fire / overwatch / hold role.
     */
    static Roles commanderBack(Roles roles, GroupSnapshot g, int commanderId) {
        if (roles == null) return null;
        int c = -1;
        for (int k = 0; k < roles.assignments.length; k++) {
            if (roles.assignments[k].unitId == commanderId) c = k;
        }
        if (c < 0) return roles;
        Assignment cmd = roles.assignments[c];
        if (cmd.role != Assignment.Role.MANEUVER && cmd.role != Assignment.Role.WITHDRAW) return roles;
        int ci = indexOf(g, commanderId);
        if (ci < 0) return roles;
        int best = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int k = 0; k < roles.assignments.length; k++) {
            Assignment a = roles.assignments[k];
            int i = indexOf(g, a.unitId);
            if (k == c || i < 0 || !HOLDING.contains(a.role)) continue;
            double d = Math.hypot(g.x[i] - g.x[ci], g.z[i] - g.z[ci]);
            if (d < bestD || (d == bestD && best >= 0 && a.unitId < roles.assignments[best].unitId)) {
                bestD = d;
                best = k;
            }
        }
        if (best < 0) return roles;
        Assignment other = roles.assignments[best];
        Assignment[] out = roles.assignments.clone();
        out[c] = new Assignment(cmd.unitId, other.role, cmd.priorityTargetId, other.flankSide, other.destX, other.destZ);
        out[best] = new Assignment(other.unitId, cmd.role, other.priorityTargetId, cmd.flankSide, cmd.destX, cmd.destZ);
        return new Roles(out);
    }

    /**
     * Cover points: holding and withdrawing roles move to the lowest-exposure spot among their point and 8
     * bearings at {@code near} and {@code far}. {@code exposure} answers 0 (masked) .. 1 (open) toward the enemy.
     * Each distinct point is searched once, so members sharing a point stay together.
     */
    static Roles coverPoints(Roles roles, double near, double far, DoubleBinaryOperator exposure) {
        if (roles == null) return null;
        Map<Long, double[]> snapped = new HashMap<>();
        Assignment[] out = roles.assignments.clone();
        boolean changed = false;
        for (int k = 0; k < out.length; k++) {
            Assignment a = out[k];
            if (!(HOLDING.contains(a.role) || a.role == Assignment.Role.WITHDRAW) || !Double.isFinite(a.destX)) continue;
            long key = Double.doubleToLongBits(a.destX) * 31 + Double.doubleToLongBits(a.destZ);
            double[] p = snapped.computeIfAbsent(key, x -> bestCover(a.destX, a.destZ, near, far, exposure));
            if (p[0] != a.destX || p[1] != a.destZ) {
                out[k] = new Assignment(a.unitId, a.role, a.priorityTargetId, a.flankSide, p[0], p[1]);
                changed = true;
            }
        }
        return changed ? new Roles(out) : roles;
    }

    private static double[] bestCover(double x, double z, double near, double far, DoubleBinaryOperator exposure) {
        double bestX = x;
        double bestZ = z;
        double best = exposure.applyAsDouble(x, z) - COVER_MIN_GAIN;
        for (double r : new double[] {near, far}) {
            for (int b = 0; b < 8; b++) {
                double ang = b * Math.PI / 4.0;
                double px = x + Math.cos(ang) * r;
                double pz = z + Math.sin(ang) * r;
                double e = exposure.applyAsDouble(px, pz);
                if (e < best) {
                    best = e;
                    bestX = px;
                    bestZ = pz;
                }
            }
        }
        return new double[] {bestX, bestZ};
    }

    @Nullable
    private static Assignment firstWith(Roles roles, Assignment.Role role) {
        for (Assignment a : roles.assignments) {
            if (a.role == role) return a;
        }
        return null;
    }

    private static int indexOf(GroupSnapshot g, int unitId) {
        for (int i = 0; i < g.memberIds.length; i++) {
            if (g.memberIds[i] == unitId) return i;
        }
        return -1;
    }
}
