package com.neoalive.tacz_sewv.entity.ai.command;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Pure play-selection with hysteresis. {@code !stillValid} aborts immediately; otherwise an
 * incumbent holds through {@code minPlayTicks} unless a challenger beats it by {@code switchMargin}.
 */
public final class PlaySelection {

    public record Result(PlayId play, Roles roles, boolean aborted, boolean switched, String reason) {}

    private PlaySelection() {}

    /**
     * @param nowTick        server tick count (same clock as command cadence)
     * @param playStarted    tick when the incumbent was committed, or {@link Long#MIN_VALUE} if none
     * @param incumbentRoles last roles (for stillValid wing checks); may be null
     */
    public static Result select(BattleField bf, GroupSnapshot group,
                                @Nullable PlayId incumbent, long playStarted, long nowTick,
                                @Nullable Roles incumbentRoles,
                                int minPlayTicks, double switchMargin,
                                UtilityWeights weights) {
        if (incumbent != null) {
            Play cur = Plays.of(incumbent);
            Roles roles = incumbentRoles != null ? incumbentRoles : new Roles(new Assignment[0]);
            if (!cur.stillValid(bf, group, roles)) {
                Result fresh = pickBest(bf, group, weights, null);
                return new Result(fresh.play, fresh.roles, true, true,
                        "abort:" + incumbent.key + "→" + fresh.play.key);
            }
        }

        Result best = pickBest(bf, group, weights, incumbent);

        if (incumbent == null) {
            return new Result(best.play, best.roles, false, true, "commit:" + best.play.key);
        }

        if (playStarted != Long.MIN_VALUE && nowTick - playStarted < minPlayTicks) {
            Roles roles = sticky(incumbentRoles, Plays.of(incumbent).assignRoles(bf, group));
            return new Result(incumbent, roles, false, false, "hold-min:" + incumbent.key);
        }

        double incScore = Plays.of(incumbent).feasible(bf, group)
                ? Plays.of(incumbent).score(bf, group, weights)
                : Double.NEGATIVE_INFINITY;
        double bestScore = Plays.of(best.play).score(bf, group, weights);
        if (best.play != incumbent && bestScore > incScore + switchMargin) {
            return new Result(best.play, best.roles, false, true,
                    "switch:" + incumbent.key + "→" + best.play.key);
        }

        Roles roles = sticky(incumbentRoles, Plays.of(incumbent).assignRoles(bf, group));
        return new Result(incumbent, roles, false, false, "keep:" + incumbent.key);
    }

    /**
     * While a play holds, a member keeps the role and side it already had; only the destination is refreshed,
     * from the fresh deal's assignment with that same role and side. Re-dealing every pass by lateral order
     * (the old behaviour) turned a flanker into base of fire the moment its run changed the lateral order.
     * A member with no previous role, or whose role no longer exists in the fresh deal, takes the fresh one.
     */
    static Roles sticky(@Nullable Roles previous, Roles fresh) {
        if (previous == null || previous.size() == 0 || fresh == null) return fresh;
        java.util.Map<Integer, Assignment> prev = new java.util.HashMap<>();
        for (Assignment a : previous.assignments) prev.put(a.unitId, a);
        java.util.Map<String, Assignment> byRole = new java.util.HashMap<>();
        for (Assignment a : fresh.assignments) byRole.putIfAbsent(roleKey(a.role, a.flankSide), a);

        Assignment[] out = new Assignment[fresh.assignments.length];
        for (int i = 0; i < out.length; i++) {
            Assignment a = fresh.assignments[i];
            Assignment p = prev.get(a.unitId);
            Assignment template = p == null ? null
                    : (p.role == a.role && p.flankSide == a.flankSide) ? a
                    : byRole.get(roleKey(p.role, p.flankSide));
            out[i] = template == null ? a
                    : new Assignment(a.unitId, p.role, a.priorityTargetId, p.flankSide, template.destX, template.destZ);
        }
        return new Roles(out);
    }

    private static String roleKey(Assignment.Role role, @Nullable Assignment.FlankSide side) {
        return role + "/" + side;
    }

    private static Result pickBest(BattleField bf, GroupSnapshot group, UtilityWeights weights,
                                   @Nullable PlayId preferTie) {
        PlayId bestId = PlayId.HOLD_DEFEND;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (Play play : Plays.menu()) {
            if (!play.feasible(bf, group)) continue;
            double s = play.score(bf, group, weights);
            if (!found || s > bestScore || (s == bestScore && winsTie(play.id(), bestId, preferTie))) {
                bestId = play.id();
                bestScore = s;
                found = true;
            }
        }
        Roles roles = Plays.of(bestId).assignRoles(bf, group);
        return new Result(bestId, roles, false, true, "best:" + bestId.key);
    }

    /** Prefer the incumbent on an exact tie; otherwise {@code min(ordinal)}. */
    private static boolean winsTie(PlayId candidate, PlayId current, @Nullable PlayId prefer) {
        if (prefer != null) {
            if (candidate == prefer) return true;
            if (current == prefer) return false;
        }
        return candidate.ordinal() < current.ordinal();
    }
}
