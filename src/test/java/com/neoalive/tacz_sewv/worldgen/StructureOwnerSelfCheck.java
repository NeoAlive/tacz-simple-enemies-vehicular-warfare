package com.neoalive.tacz_sewv.worldgen;

import java.util.EnumSet;
import java.util.Set;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Self-check for native-structure ownership. Run via {@code ./gradlew selfCheck}.
 *
 * <p>Plain {@code main} + {@code assert}. Covers the pure half only ({@link StructureOwner});
 * finding the structure around a position needs a live level and is in the in-game matrix.
 */
public final class StructureOwnerSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        tokens();
        explicitOwnerWins();
        singleFactionNeverRolls();
        mixedIsDeterministicAndReachesBoth();
        noRuUsMeansNoOwner();
        System.out.println("StructureOwnerSelfCheck OK");
    }

    private static void tokens() {
        assert StructureOwner.tokenOf("forest_ru_comms") == TankFaction.RU;
        assert StructureOwner.tokenOf("desert_us_comms") == TankFaction.US;
        assert StructureOwner.tokenOf("forest_pmc_airdrop") == TankFaction.PMC;
        assert StructureOwner.tokenOf("forest_helipad") == null;
        assert StructureOwner.tokenOf("sniper_structure") == null;
        // Whole tokens only: "focus" must not read as US, "rush" not as RU.
        assert StructureOwner.tokenOf("focus_rush") == null;
        // Both named: ambiguous, so no declared owner.
        assert StructureOwner.tokenOf("ru_us_border") == null;
        assert StructureOwner.tokenOf("camp/US/tower") == TankFaction.US;
    }

    private static void explicitOwnerWins() {
        Set<TankFaction> both = EnumSet.of(TankFaction.RU, TankFaction.US);
        for (long seed = 0; seed < 50; seed++) {
            assert StructureOwner.resolve(TankFaction.RU, both, seed) == TankFaction.RU;
            assert StructureOwner.resolve(TankFaction.US, both, seed) == TankFaction.US;
        }
    }

    private static void singleFactionNeverRolls() {
        for (long seed = 0; seed < 50; seed++) {
            assert StructureOwner.resolve(null, EnumSet.of(TankFaction.RU), seed) == TankFaction.RU;
            assert StructureOwner.resolve(null, EnumSet.of(TankFaction.US, TankFaction.PMC), seed) == TankFaction.US;
        }
    }

    private static void mixedIsDeterministicAndReachesBoth() {
        Set<TankFaction> both = EnumSet.of(TankFaction.RU, TankFaction.US);
        int ru = 0;
        for (long chunk = 0; chunk < 400; chunk++) {
            long seed = StructureOwner.seed(1234L, chunk, "tacz_sewv:forest_helipad".hashCode());
            TankFaction first = StructureOwner.resolve(null, both, seed);
            assert first == StructureOwner.resolve(null, both, seed) : "same seed must give the same owner";
            if (first == TankFaction.RU) ru++;
        }
        // A fair coin over 400 structures: nowhere near all one faction.
        assert ru > 120 && ru < 280 : "mixed roll is lopsided: " + ru + "/400 RU";
    }

    private static void noRuUsMeansNoOwner() {
        assert StructureOwner.resolve(null, EnumSet.of(TankFaction.PMC), 7L) == null;
        assert StructureOwner.resolve(null, EnumSet.noneOf(TankFaction.class), 7L) == null;
    }

    private StructureOwnerSelfCheck() {}
}
