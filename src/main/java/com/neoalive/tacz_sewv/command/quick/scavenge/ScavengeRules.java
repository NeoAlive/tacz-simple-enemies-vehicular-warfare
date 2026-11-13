package com.neoalive.tacz_sewv.command.quick.scavenge;

/** The pure arithmetic behind Scavenge, pinned by {@code ScavengeSelfCheck}. */
public final class ScavengeRules {

    /** Extra metres a source costs per unit already sent to it, so a group spreads out. */
    public static final double CROWD_PENALTY = 12.0;

    private ScavengeRules() {}

    /** How many more a unit may take under {@code cap} when it already carries {@code carried}. */
    public static int remaining(int cap, int carried) {
        return Math.max(0, cap - Math.max(0, carried));
    }

    /**
     * Pockets are full when no slot is empty and no stack can grow. A unit in that state can take
     * nothing, so it is refused before it walks anywhere.
     */
    public static boolean pocketsFull(int[] counts, int[] maxes) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0 || counts[i] < maxes[i]) return false;
        }
        return true;
    }

    /** Distance a unit would rank a source at, given how many others already head there. */
    public static double rank(double distance, int claimants) {
        return distance + CROWD_PENALTY * Math.max(0, claimants);
    }

    /** The stage after {@code stage}, or null after the last. */
    public static ScavengeStage next(ScavengeStage stage) {
        ScavengeStage[] all = ScavengeStage.values();
        int i = stage.ordinal() + 1;
        return i < all.length ? all[i] : null;
    }
}
