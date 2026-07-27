package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

import java.util.List;

/**
 * Headless self-check for commander election. Run via {@code ./gradlew selfCheck}.
 *
 * <p>First assertion: Facts-unready members never produce a silent {@code min(id)} election.
 */
public final class ElectionSelfCheck {

    private static final double MARGIN = 0.15;
    private static final int QUORUM = 2;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        // Non-negotiable — must stay first.
        unpopulatedMembersDeferRatherThanElect();

        noIncumbentHighestFitnessWins();
        incumbentKeptInsideMargin();
        challengerBeatsMarginTakesOver();
        tieBreaksOnMinId();
        playerDesignationOverrides();
        deadDesignationFallsThrough();
        commanderWeightsScore();

        System.out.println("command election self-check: OK");
    }

    /**
     * Unpopulated Facts must defer the first election — never elect via min(id) on blank fitness.
     */
    private static void unpopulatedMembersDeferRatherThanElect() {
        // All unready, fitness numbers look like "id 1 would win on min(id)" if someone ranked them.
        List<Election.Candidate> blank = List.of(
                new Election.Candidate(1, false, 0.0),
                new Election.Candidate(2, false, 0.0),
                new Election.Candidate(3, false, 99.0) // high fitness but NOT ready
        );
        Integer deferred = Election.electCommander(blank, null, MARGIN, null, QUORUM);
        assertNull(deferred, "unready members must defer first election (not min(id)=1)");

        // Only one ready, quorum=2 → still defer.
        List<Election.Candidate> oneReady = List.of(
                new Election.Candidate(1, true, 0.1),
                new Election.Candidate(2, false, 0.0)
        );
        assertNull(Election.electCommander(oneReady, null, MARGIN, null, QUORUM),
                "below ready-quorum must defer");

        // Quorum met with ready members → elect among ready only (id 5, not unready id 1).
        List<Election.Candidate> quorumMet = List.of(
                new Election.Candidate(1, false, 9.0),
                new Election.Candidate(5, true, 0.5),
                new Election.Candidate(8, true, 0.4)
        );
        assertEq(5, Election.electCommander(quorumMet, null, MARGIN, null, QUORUM),
                "elect among ready only once quorum met");
    }

    private static void noIncumbentHighestFitnessWins() {
        List<Election.Candidate> members = List.of(
                new Election.Candidate(10, true, 0.4),
                new Election.Candidate(20, true, 0.9),
                new Election.Candidate(30, true, 0.5)
        );
        assertEq(20, Election.electCommander(members, null, MARGIN, null, 1),
                "no incumbent → highest fitness");
    }

    private static void incumbentKeptInsideMargin() {
        List<Election.Candidate> members = List.of(
                new Election.Candidate(1, true, 0.50),
                new Election.Candidate(2, true, 0.50 + MARGIN) // equal to margin boundary: NOT strictly greater
        );
        assertEq(1, Election.electCommander(members, 1, MARGIN, null, 1),
                "challenger at exactly margin must not unseat");

        List<Election.Candidate> below = List.of(
                new Election.Candidate(1, true, 0.50),
                new Election.Candidate(2, true, 0.50 + MARGIN - 0.001)
        );
        assertEq(1, Election.electCommander(below, 1, MARGIN, null, 1),
                "challenger inside margin → incumbent kept");
    }

    private static void challengerBeatsMarginTakesOver() {
        List<Election.Candidate> members = List.of(
                new Election.Candidate(1, true, 0.50),
                new Election.Candidate(2, true, 0.50 + MARGIN + 0.01)
        );
        assertEq(2, Election.electCommander(members, 1, MARGIN, null, 1),
                "challenger beyond margin takes over");
    }

    private static void tieBreaksOnMinId() {
        List<Election.Candidate> members = List.of(
                new Election.Candidate(30, true, 0.7),
                new Election.Candidate(10, true, 0.7),
                new Election.Candidate(20, true, 0.7)
        );
        Integer a = Election.electCommander(members, null, MARGIN, null, 1);
        Integer b = Election.electCommander(members, null, MARGIN, null, 1);
        assertEq(10, a, "exact fitness tie → min(id)");
        assertEq(a, b, "tiebreak is deterministic across calls");
    }

    private static void playerDesignationOverrides() {
        List<Election.Candidate> members = List.of(
                new Election.Candidate(1, true, 0.9),
                new Election.Candidate(2, true, 0.1)
        );
        assertEq(2, Election.electCommander(members, null, MARGIN, 2, 1),
                "player-designated beats higher-fitness auto");
        // Designated need not be Facts-ready for the hook.
        List<Election.Candidate> unreadyDesignee = List.of(
                new Election.Candidate(1, true, 0.9),
                new Election.Candidate(2, false, 0.0)
        );
        assertEq(2, Election.electCommander(unreadyDesignee, null, MARGIN, 2, QUORUM),
                "player-designated in-group wins even if unready");
    }

    private static void deadDesignationFallsThrough() {
        List<Election.Candidate> members = List.of(
                new Election.Candidate(1, true, 0.4),
                new Election.Candidate(2, true, 0.8)
        );
        // Designated id 99 not in group → auto elects 2.
        assertEq(2, Election.electCommander(members, null, MARGIN, 99, 1),
                "dead/out-of-group designation falls through to auto");
    }

    private static void commanderWeightsScore() {
        UtilityWeights w = UtilityWeights.fallback();
        double healthy = CommanderFitness.score(1.0, 1.0, 1.0, 1.0, w);
        double hurt = CommanderFitness.score(0.2, 1.0, 1.0, 1.0, w);
        assertTrue(healthy > hurt, "fallback commander weights must be health-dominant");
    }

    private static void assertNull(Integer v, String label) {
        assert v == null : label + ": expected null got " + v;
    }

    private static void assertEq(int expected, Integer actual, String label) {
        assert actual != null && actual == expected
                : label + ": expected " + expected + " got " + actual;
    }

    private static void assertEq(Integer a, Integer b, String label) {
        assert a != null && a.equals(b) : label + ": " + a + " vs " + b;
    }

    private static void assertTrue(boolean cond, String message) {
        assert cond : message;
    }
}
