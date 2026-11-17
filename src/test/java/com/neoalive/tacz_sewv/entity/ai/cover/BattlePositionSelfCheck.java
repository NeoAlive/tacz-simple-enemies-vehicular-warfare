package com.neoalive.tacz_sewv.entity.ai.cover;

import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.cover.BattlePosition.Verdict;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine.Temperament;

/**
 * Headless check of the battle-position lifecycle: the keep/swap/drop verdict, lease renewal, and
 * the breaker hand-off. Run via {@code ./gradlew selfCheckBattlePosition}.
 */
public final class BattlePositionSelfCheck {

    private static final long NOW = 10_000;

    public static void main(String[] args) {
        verdicts();
        lease();
        working();
        bearings();
        breaker();
        cooldown();
        temperament();
        opportunity();
        System.out.println("BattlePositionSelfCheck OK");
    }

    /** A crew working comfortably from its position. */
    private static Verdict calm(boolean engaged, boolean blocked, double drift, double dist, long lease,
                                long onStation, double pressure, boolean alt) {
        return BattlePosition.decide(engaged, blocked, drift, dist, 70.0, NOW, lease, onStation, pressure, alt);
    }

    private static void verdicts() {
        long lease = NOW + 100;
        assert calm(true, false, 0.1, 80, lease, 100, 0.0, true) == Verdict.KEEP;
        assert calm(false, false, 0.1, 80, lease, 100, 0.0, true) == Verdict.DROP : "engagement over";
        assert calm(true, true, 0.1, 80, lease, 100, 0.0, true) == Verdict.DROP : "ordered away";
        assert calm(true, false, 0.1, 60, lease, 100, 0.0, true) == Verdict.DROP : "target inside the band";
        assert calm(true, false, Math.toRadians(50), 80, lease, 100, 0.0, true) == Verdict.DROP : "fight moved";
        assert calm(true, false, Math.toRadians(40), 80, lease, 100, 0.0, true) == Verdict.KEEP;
        assert calm(true, false, 0.1, 80, NOW - 1, 100, 0.0, true) == Verdict.DROP : "lease expired";
        assert calm(true, false, 0.1, 80, lease, 100, 0.6, true) == Verdict.SWAP : "under fire, has alternate";
        assert calm(true, false, 0.1, 80, lease, 100, 0.6, false) == Verdict.DROP : "under fire, none left";
        assert calm(true, false, 0.1, 80, lease, 10, 0.6, true) == Verdict.KEEP
                : "pressure carried in from the last position does not count before settling";
        assert calm(true, false, 0.1, 80, lease, -1, 0.9, true) == Verdict.KEEP : "en route: pressure ignored";
    }

    private static void lease() {
        long t = 600;
        assert BattlePosition.renewLease(NOW, Long.MIN_VALUE, NOW - 5, t) == NOW : "not arrived: no renew";
        assert BattlePosition.renewLease(NOW, NOW - 50, NOW - 60, t) == NOW : "shot before arrival: no renew";
        assert BattlePosition.renewLease(NOW, NOW - 50, NOW - 10, t) == NOW - 10 + t : "shot from station renews";
        assert BattlePosition.renewLease(NOW + 5000, NOW - 50, NOW - 10, t) == NOW + 5000 : "never shortens";
        assert BattlePosition.renewLease(NOW, NOW - 50, Long.MIN_VALUE, t) == NOW : "never fired";
    }

    /** A silent crew must stop being told to stay, or two facing positions wait each other out. */
    private static void working() {
        long w = BattlePosition.WORK_TICKS;
        assert !BattlePosition.working(NOW, Long.MIN_VALUE, NOW) : "en route is not working the position";
        assert BattlePosition.working(NOW, NOW - w, Long.MIN_VALUE) : "just arrived";
        assert !BattlePosition.working(NOW, NOW - w - 1, Long.MIN_VALUE) : "arrived long ago, never fired";
        assert BattlePosition.working(NOW, NOW - 1000, NOW - w) : "firing from it";
        assert !BattlePosition.working(NOW, NOW - 1000, NOW - w - 1) : "gone quiet";
        assert !BattlePosition.working(NOW, NOW - 1000, NOW - 1001) : "a shot from before arriving does not count";
    }

    private static void bearings() {
        assert Math.abs(BattlePosition.bearingDelta(Math.PI - 0.1, -Math.PI + 0.1) - 0.2) < 1.0E-9 : "wraps";
        assert Math.abs(BattlePosition.bearingDelta(0.3, -0.3) - 0.6) < 1.0E-9;
        assert BattlePosition.bearingDelta(1.0, 1.0) == 0.0;
    }

    private static void breaker() {
        Vec3 a = new Vec3(0, 64, 0);
        Vec3 b = new Vec3(20, 64, 0);

        BattlePosition withAlt = new BattlePosition();
        withAlt.latch(new CoverQuery.BattlePick(a, b, true), 0.0, NOW, 1.0);
        assert withAlt.onBreaker(NOW) : "first breaker swaps";
        assert withAlt.point() == b && !withAlt.hasAlternate();
        assert withAlt.onBreaker(NOW + 100) : "same episode keeps the swap";
        assert !withAlt.onBreaker(NOW + BattlePosition.BREAKER_SWAP_WINDOW + 1) : "later episode gives it up";
        assert !withAlt.latched();

        BattlePosition alone = new BattlePosition();
        alone.latch(new CoverQuery.BattlePick(a, null, false), 0.0, NOW, 1.0);
        assert !alone.onBreaker(NOW) : "no alternate: the orbit runs";
        assert !alone.latched() : "and the silent spot is not driven back to";

        BattlePosition pressed = new BattlePosition();
        pressed.latch(new CoverQuery.BattlePick(a, b, true), 0.0, NOW, 1.0);
        assert pressed.swap(NOW) && !pressed.swap(NOW + 1) : "one alternate, used once";
    }

    private static void cooldown() {
        BattlePosition bp = new BattlePosition();
        assert bp.mayPick(NOW);
        bp.latch(null, 0.0, NOW, 1.0);
        assert !bp.latched() : "nothing worth taking";
        assert !bp.mayPick(NOW + BattlePosition.PICK_COOLDOWN - 1) : "open ground is not re-raycast every refresh";
        assert bp.mayPick(NOW + BattlePosition.PICK_COOLDOWN);

        bp.latch(new CoverQuery.BattlePick(new Vec3(0, 64, 0), null, false), 0.0, NOW, 1.0);
        bp.drop(NOW + 50);
        assert !bp.mayPick(NOW + 50 + BattlePosition.PICK_COOLDOWN - 1) : "a drop starts the cooldown";
        assert bp.mayPick(NOW + 50 + BattlePosition.PICK_COOLDOWN);
    }

    /**
     * One faction's crews must not all share a temperament, or they all dig in together. Across many hulls of a
     * neutral doctrine every band shows up, the answer is a pure function of the seed, and doctrine moves the centre.
     */
    private static void temperament() {
        int[] counts = new int[Temperament.values().length];
        double sumNeutral = 0, sumBold = 0, sumCareful = 0;
        // AGGRESSION, PRESERVATION, COHESION, INITIATIVE, SUPPORT_RELIANCE, TARGET_PRIORITY, MANEUVER, RISK_TOLERANCE
        Doctrine bold = Doctrine.ofAxes(new int[] {4, -3, 0, 0, 0, 0, 0, 4});
        Doctrine careful = Doctrine.ofAxes(new int[] {-4, 4, 0, 0, 0, 0, 0, -4});
        int n = 2000;
        for (long seed = 0; seed < n; seed++) {
            long s = seed * 0x9E3779B97F4A7C15L;
            double c = Doctrine.caution(Doctrine.NEUTRAL, s);
            assert c >= 0.0 && c <= 1.0;
            assert c == Doctrine.caution(Doctrine.NEUTRAL, s) : "deterministic";
            counts[Temperament.of(c).ordinal()]++;
            sumNeutral += c;
            sumBold += Doctrine.caution(bold, s);
            sumCareful += Doctrine.caution(careful, s);
        }
        for (Temperament t : Temperament.values()) {
            assert counts[t.ordinal()] > n / 10 : t + " is too rare: " + counts[t.ordinal()];
        }
        assert Math.abs(sumNeutral / n - 0.5) < 0.03 : "neutral doctrine centres on 0.5: " + sumNeutral / n;
        assert sumBold / n < sumNeutral / n - 0.1 : "an aggressive doctrine is bolder";
        assert sumCareful / n > sumNeutral / n + 0.1 : "a preserving doctrine is more careful";
    }

    /** Cover is not the default: bold never, steady only when it is going badly, cautious always. */
    private static void opportunity() {
        double bold = 0.1, steady = 0.5, careful = 0.9;
        // A calm, even, healthy fight.
        assert !BattlePosition.wantsPosition(bold, 0.0, 1.0, 1.0, 50);
        assert !BattlePosition.wantsPosition(steady, 0.0, 1.0, 1.0, 50) : "steady pushes while it is going well";
        assert BattlePosition.wantsPosition(careful, 0.0, 1.0, 1.0, 50);
        // Each reason a steady crew goes to ground.
        assert BattlePosition.wantsPosition(steady, 0.4, 1.0, 1.0, 50) : "under fire";
        assert BattlePosition.wantsPosition(steady, 0.0, 0.5, 1.0, 50) : "outnumbered";
        assert BattlePosition.wantsPosition(steady, 0.0, 1.0, 0.4, 50) : "hurt";
        assert BattlePosition.wantsPosition(steady, 0.0, 1.0, 1.0, 30) : "rattled";
        // Bold never, whatever happens.
        assert !BattlePosition.wantsPosition(bold, 0.9, 0.2, 0.2, 0);
        assert BattlePosition.leaseScale(0.0) == 0.5 && BattlePosition.leaseScale(1.0) == 1.5;
    }
}
