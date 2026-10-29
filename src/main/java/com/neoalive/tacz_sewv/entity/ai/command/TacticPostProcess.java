package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntPredicate;

import javax.annotation.Nullable;


/**
 * Group tactics applied to a play's roles after selection, in {@code CommandCoordinator} order: leapfrog,
 * formation slots ({@link PlayFormations}), commander back, cover points. Pure — positions and the exposure lookup are passed in — so each is
 * headless-testable. Roles in, roles out; unchanged input comes back as the same instance.
 */
final class TacticPostProcess {

    /** Cover snap sample radii (× scale by the caller). */
    static final double COVER_NEAR = 8.0;
    static final double COVER_FAR = 16.0;
    /** A candidate must beat the original point's exposure by this much to move it. */
    private static final double COVER_MIN_GAIN = 0.05;
    /** Distinct points cover-searched per group pass. */
    static final int MAX_COVER_SEARCHES = 16;

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
        return leapfrog(play, previous, roles, fresh, g, arrive, false, id -> true, 0L);
    }

    /** An overwatch member that fired within this many ticks is covering the bound. */
    static final long COVER_FIRE_TICKS = 60;
    /** With nobody firing, the next bound goes anyway after this long: a gate that never opens stalls the group. */
    static final long SWAP_TIMEOUT_TICKS = 160;

    /**
     * Leapfrog with Steve's Army's covering-fire rule: in contact, the movers wait on their points until a member of
     * the overwatch has actually fired recently ({@code firedRecently}, by unit id) — muzzle flash, not the role
     * flag — or until {@link #SWAP_TIMEOUT_TICKS} since the last swap. Out of contact the arrival rule alone applies.
     */
    static Roles leapfrog(PlayId play, @Nullable Roles previous, Roles roles, Roles fresh,
                          GroupSnapshot g, double arrive, boolean inContact, IntPredicate firedRecently,
                          long ticksSinceSwap) {
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
        if (inContact && ticksSinceSwap < SWAP_TIMEOUT_TICKS && !overwatchFiring(previous, firedRecently)) {
            return roles;
        }

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

    private static boolean overwatchFiring(Roles previous, IntPredicate firedRecently) {
        for (Assignment a : previous.assignments) {
            if (a.role == Assignment.Role.OVERWATCH && firedRecently.test(a.unitId)) return true;
        }
        return false;
    }

    /**
     * Commander back: an elected commander never leads a flank, a bound or a withdrawal — it swaps its whole task
     * (role, side, slot, facing) with the nearest member holding a base-of-fire / overwatch / hold role.
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
        out[c] = cmd.withTaskOf(other);
        out[best] = other.withTaskOf(cmd);
        return new Roles(out);
    }

    /**
     * Cover points: holding and withdrawing roles move to the lowest-exposure spot among their point and 8
     * bearings at {@code near} and {@code far}. {@code exposure} answers 0 (masked) .. 1 (open) toward the enemy.
     * Each distinct point is searched once, so members sharing a point stay together.
     */
    static Roles coverPoints(Roles roles, double near, double far, DoubleBinaryOperator exposure) {
        return coverPoints(roles, near, far, 0.0, exposure);
    }

    /**
     * As above, for formation slots: a candidate closer than {@code minSeparation} to any other member's current
     * point — settled or not yet searched — is refused, so a slot can neither fold onto a neighbour's slot nor
     * onto the cover a neighbour already took. (Checking only the members already searched let the first slot
     * of a line snap onto cover beside the second, which then stayed put four blocks away.) Members keep their
     * own point whenever no candidate qualifies, so slots laid {@code minSeparation} apart stay that far apart.
     * At most {@link #MAX_COVER_SEARCHES} searches run per pass (each is 17 exposure reads); the rest keep their
     * point. With {@code minSeparation <= 0} each distinct point is searched once and members sharing it move
     * together.
     */
    static Roles coverPoints(Roles roles, double near, double far, double minSeparation,
                             DoubleBinaryOperator exposure) {
        if (roles == null) return null;
        Assignment[] out = roles.assignments.clone();
        double[][] at = new double[out.length][];
        for (int k = 0; k < out.length; k++) {
            if (snaps(out[k])) at[k] = new double[] {out[k].destX, out[k].destZ};
        }
        boolean spread = minSeparation > 0.0;
        Map<Long, double[]> shared = new HashMap<>();
        int searches = 0;
        boolean changed = false;
        for (int k = 0; k < out.length; k++) {
            if (at[k] == null) continue;
            Assignment a = out[k];
            double[] p;
            long key = Double.doubleToLongBits(a.destX) * 31 + Double.doubleToLongBits(a.destZ);
            if (!spread && shared.containsKey(key)) {
                p = shared.get(key);
            } else if (searches < MAX_COVER_SEARCHES) {
                searches++;
                p = bestCover(a.destX, a.destZ, near, far, spread ? minSeparation : 0.0, at, k, exposure);
                shared.put(key, p);
            } else {
                p = at[k];
            }
            at[k] = p;
            if (p[0] != a.destX || p[1] != a.destZ) {
                out[k] = a.withDest(p[0], p[1]);
                changed = true;
            }
        }
        return changed ? new Roles(out) : roles;
    }

    private static boolean snaps(Assignment a) {
        return (HOLDING.contains(a.role) || a.role == Assignment.Role.WITHDRAW) && Double.isFinite(a.destX);
    }

    private static double[] bestCover(double x, double z, double near, double far, double minSeparation,
                                      double[][] at, int self, DoubleBinaryOperator exposure) {
        double bestX = x;
        double bestZ = z;
        double best = exposure.applyAsDouble(x, z) - COVER_MIN_GAIN;
        for (double r : new double[] {near, far}) {
            for (int b = 0; b < 8; b++) {
                double ang = b * Math.PI / 4.0;
                double px = x + Math.cos(ang) * r;
                double pz = z + Math.sin(ang) * r;
                if (crowds(px, pz, minSeparation, at, self)) continue;
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

    /** True when {@code (x, z)} is within {@code minSeparation} of any other member's current point. */
    private static boolean crowds(double x, double z, double minSeparation, double[][] at, int self) {
        if (minSeparation <= 0.0) return false;
        double minSq = minSeparation * minSeparation;
        for (int j = 0; j < at.length; j++) {
            if (j == self || at[j] == null) continue;
            double dx = at[j][0] - x;
            double dz = at[j][1] - z;
            if (dx * dx + dz * dz < minSq) return true;
        }
        return false;
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
