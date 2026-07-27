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
        battleFieldCentroidMatchesStage2MeanForElection();

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

    /**
     * Stage 3 regression seam: when {@link BattleField} friendly centroid equals the Stage-2
     * arithmetic mean of member positions, election fitness (via centrality) and therefore the
     * elected commander must be identical. Prevents a drifted centroid formula from hiding
     * behind the influence map.
     */
    private static void battleFieldCentroidMatchesStage2MeanForElection() {
        // Symmetric battlefield: friendlies at mean (10, 20), enemies opposite.
        List<UnitPos> units = List.of(
                new UnitPos(1, 0, 5, 20),
                new UnitPos(2, 0, 15, 20),
                new UnitPos(3, 0, 10, 20),
                new UnitPos(10, 1, 10, 80),
                new UnitPos(11, 1, 12, 80)
        );
        InfluenceMap map = new InfluenceMap();
        BattleField bf = new BattleField();
        map.rebuildAndDerive(bf, units, 0, 12.0, 256, 24.0);
        assertTrue(bf.populated, "BF must populate for centroid seam");

        double meanX = (5 + 15 + 10) / 3.0;
        double meanZ = 20.0;
        assertNear(meanX, bf.friendlyCentroidX, 1.0e-9, "BF friendly cx == Stage-2 mean");
        assertNear(meanZ, bf.friendlyCentroidZ, 1.0e-9, "BF friendly cz == Stage-2 mean");

        UtilityWeights w = UtilityWeights.fallback();
        double maxRadius = 64.0;
        // Member 1 at edge, 2 at edge, 3 at centroid — 3 must win on centrality when other knobs equal.
        double c1Mean = CommanderFitness.centrality(5, 20, meanX, meanZ, maxRadius);
        double c3Mean = CommanderFitness.centrality(10, 20, meanX, meanZ, maxRadius);
        double c1Bf = CommanderFitness.centrality(5, 20, bf.friendlyCentroidX, bf.friendlyCentroidZ, maxRadius);
        double c3Bf = CommanderFitness.centrality(10, 20, bf.friendlyCentroidX, bf.friendlyCentroidZ, maxRadius);
        assertNear(c1Mean, c1Bf, 1.0e-12, "centrality(edge) identical via BF centroid");
        assertNear(c3Mean, c3Bf, 1.0e-12, "centrality(centre) identical via BF centroid");

        double f1 = CommanderFitness.score(1.0, 1.0, c1Bf, 1.0, w);
        double f3 = CommanderFitness.score(1.0, 1.0, c3Bf, 1.0, w);
        List<Election.Candidate> members = List.of(
                new Election.Candidate(1, true, f1),
                new Election.Candidate(3, true, f3)
        );
        Integer viaBf = Election.electCommander(members, null, MARGIN, null, 1);

        double f1s2 = CommanderFitness.score(1.0, 1.0, c1Mean, 1.0, w);
        double f3s2 = CommanderFitness.score(1.0, 1.0, c3Mean, 1.0, w);
        Integer viaMean = Election.electCommander(List.of(
                new Election.Candidate(1, true, f1s2),
                new Election.Candidate(3, true, f3s2)
        ), null, MARGIN, null, 1);

        assertEq(viaMean, viaBf, "election identical for Stage-2 mean vs BF-sourced centroid");
        assertEq(3, viaBf, "centroid member wins when health/ammo/allies tied");
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

    private static void assertNear(double expected, double actual, double tol, String label) {
        assert Math.abs(actual - expected) <= tol
                : label + ": expected ~" + expected + " ±" + tol + " got " + actual;
    }
}
