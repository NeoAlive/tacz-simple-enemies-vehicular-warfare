package com.neoalive.tacz_sewv.invasion;

/**
 * Who won a match that ran out of time — pure, so it is checked headless
 * ({@code ./gradlew selfCheckInvasion}).
 * <p>
 * A team <b>occupies</b> when it holds something it took from the enemy: a point the enemy owned
 * before it, or the enemy's team_base. Occupation outranks point count — a team that broke into
 * enemy ground beats one that only spread across neutral points. When exactly one team occupies,
 * it wins; otherwise (both, or neither) the team holding more capture points wins, and equal
 * counts — all points still neutral included — are a tie.
 */
public final class MatchOutcome {

    private MatchOutcome() {}

    public enum Result { TEAM_A, TEAM_B, TIE }

    /** The reason a result was reached, for the announcement. */
    public enum Reason { OCCUPATION, POINTS, TIE }

    public record Verdict(Result result, Reason reason) {}

    public static Verdict decide(boolean occupiesA, boolean occupiesB, int pointsA, int pointsB) {
        if (occupiesA != occupiesB) {
            return new Verdict(occupiesA ? Result.TEAM_A : Result.TEAM_B, Reason.OCCUPATION);
        }
        if (pointsA != pointsB) {
            return new Verdict(pointsA > pointsB ? Result.TEAM_A : Result.TEAM_B, Reason.POINTS);
        }
        return new Verdict(Result.TIE, Reason.TIE);
    }
}
