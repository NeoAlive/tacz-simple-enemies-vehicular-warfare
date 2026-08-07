package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.Signal;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;
import com.neoalive.tacz_sewv.map.BattleFieldMarker;

/**
 * Shared play scoring sample from a {@link BattleField} — group-level, not crew Facts.
 */
public final class PlaySignals {

    private PlaySignals() {}

    /** Scratch-free fill of a caller-owned array indexed by {@link Signal} ordinal. */
    public static void sample(BattleField bf, double[] out) {
        java.util.Arrays.fill(out, 0.0);
        out[Signal.BASE.ordinal()] = 1.0;
        if (!bf.populated) return;
        // forceBalance = friendlies/enemies; below 1 → outnumbered signal ramps up.
        if (bf.forceBalance < 1.0) {
            out[Signal.OUTNUMBERED.ordinal()] = Math.min(1.0, 1.0 - bf.forceBalance);
        }
        if (bf.enemyCount > 0) {
            out[Signal.ENEMY_VISIBLE.ordinal()] = 1.0;
        }
        if (bf.forceBalance > 1.2) {
            out[Signal.CONFIDENCE.ordinal()] = Math.min(1.0, (bf.forceBalance - 1.0) / 1.0);
        } else if (bf.forceBalance < 0.8) {
            out[Signal.CONFIDENCE.ordinal()] = -Math.min(1.0, (0.8 - bf.forceBalance) / 0.8);
        }
        out[Signal.TOO_FAR.ordinal()] = bf.openFlankLeft || bf.openFlankRight ? 0.5 : 0.0;
    }

    public static double score(PlayId id, BattleField bf, UtilityWeights weights) {
        double[] s = new double[Signal.VALUES.length];
        sample(bf, s);
        return weights.scorePlay(id, s, Doctrine.NEUTRAL);
    }

    /** Enemy-side open-flank mark world X (left = +1). */
    public static double flankMarkX(BattleField bf, int side) {
        return BattleFieldMarker.flankMarkX(bf.enemyCentroidX, bf.axisX, bf.axisZ, side);
    }

    public static double flankMarkZ(BattleField bf, int side) {
        return BattleFieldMarker.flankMarkZ(bf.enemyCentroidZ, bf.axisX, bf.axisZ, side);
    }
}
